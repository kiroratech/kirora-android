package com.kirora.androidsdk.net

import com.kirora.androidsdk.db.AppOpenDao
import com.kirora.androidsdk.db.AppOpenEntity
import com.kirora.androidsdk.identity.Identity
import com.kirora.androidsdk.identity.SignedInUser
import com.kirora.androidsdk.model.AuthFailureReason
import com.kirora.androidsdk.model.UserTokenError
import com.kirora.androidsdk.net.models.AppOpenRequest
import com.kirora.androidsdk.utils.JsonSnapshot
import com.kirora.androidsdk.utils.KiroraLogger
import com.kirora.androidsdk.utils.KiroraPreferences
import com.kirora.androidsdk.utils.RetryAfter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Reports app opens, so Kirora can tell an app opened after a push from one opened by tapping it
 * (an influenced open). Only while behavioural tracking is allowed, only for a signed-in user on a
 * device registered for push, and at most one in 30 minutes per device.
 *
 * Not an event: its own call and queue, never counted against the plan's events. Each open is
 * bound at capture to the user and the push app, kept across restarts, retried with backoff
 * (never sooner than `Retry-After`) and dropped unsent after 7 days.
 */
internal class KiroraAppOpenReporter(
    private val dao: AppOpenDao?,
    private val preferences: KiroraPreferences,
    private val apiService: () -> KiroraApiService?,
    private val scope: CoroutineScope,
    private val identity: Identity,
    /** The push app [subject]'s device registration names, or null when not registered for them. */
    private val registeredPushAppId: (subject: String) -> String?,
    private val trackingAllowed: () -> Boolean,
    /** A background token failure, with the token it concerns. */
    private val onAuthFailure: (AuthFailureReason, SignedInUser) -> Unit = { _, _ -> },
    /**
     * With `trackSessions` on, the 30-minute spacing is kept in storage with the visit; with it
     * off, in memory only, so nothing behavioural is stored for it.
     */
    private val persistSpacing: Boolean = true,
    /** Deletions the database refused, carried out before it is read again. */
    private val purges: PendingPurges = PendingPurges(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** The last open recorded; written through to storage only when [persistSpacing]. */
    @Volatile
    private var lastOpen = if (persistSpacing) preferences.getLastAppOpen() else 0L

    init {
        // Kept by an earlier start with sessions on: not needed, and not to be kept.
        if (!persistSpacing) preferences.clearLastAppOpen()
    }

    private val flushMutex = Mutex()
    private val inFlight: MutableSet<Job> = ConcurrentHashMap.newKeySet()

    /** A cold launch happens before the app has signed the user in; it waits for that, in memory. */
    @Volatile
    private var pending: Long? = null

    @Volatile
    private var backoffMs = FIRST_RETRY_MS

    @Volatile
    private var nextRetryAt = 0L

    /** The server's `Retry-After`: no sooner, whatever else resets the backoff. */
    @Volatile
    private var holdUntil = 0L

    @Volatile
    private var destroyed = false

    private var retryJob: Job? = null

    /** The app opened or returned to the foreground. */
    fun onAppOpen() {
        if (destroyed || !trackingAllowed()) return
        val now = clock()
        if (withinWindow(now)) return
        val user = identity.user
        if (user == null) pending = now else record(user, now)
    }

    /** After a sign-in has registered the device: a launch that waited for it is reported, and anything queued is sent. */
    fun onSignedIn(user: SignedInUser) {
        val waiting = pending
        pending = null
        if (waiting != null && clock() - waiting <= PENDING_MAX_AGE_MS && trackingAllowed()) record(user, waiting)
        flush()
    }

    /** Drops what is queued for [subject], as they sign out. */
    fun clearOwnedBy(subject: String) {
        pending = null
        val d = dao ?: return
        launchTracked {
            purges.purgeOwner(d, subject)
        }
    }

    /** Tracking withdrawn: forget every queued open and stop sending. */
    fun clearAll() {
        pending = null
        lastOpen = 0L
        preferences.clearLastAppOpen()
        inFlight.forEach { it.cancel() }
        retryJob?.cancel()
        val d = dao ?: return
        scope.launch { purges.purgeAll(d) }
    }

    fun destroy() {
        destroyed = true
        inFlight.forEach { it.cancel() }
        retryJob?.cancel()
    }

    /** Back online: our own backoff no longer applies (a `Retry-After` still does). */
    fun onNetworkAvailable() {
        backoffMs = FIRST_RETRY_MS
        nextRetryAt = 0L
        flush()
    }

    private fun withinWindow(at: Long): Boolean {
        val last = lastOpen
        return last > 0 && at - last in 0 until WINDOW_MS
    }

    private fun record(user: SignedInUser, at: Long) {
        val d = dao ?: return
        // Only a device registered for push, with the push app it registered with.
        val pushAppId = registeredPushAppId(user.subject) ?: return
        if (withinWindow(at)) return
        lastOpen = at
        if (persistSpacing) preferences.setLastAppOpen(at)
        launchTracked {
            if (!purges.settle(d)) return@launchTracked
            d.insertAndTrim(AppOpenEntity(occurredAt = at, pushAppId = pushAppId, owner = user.subject), MAX_QUEUED)
            doFlush()
        }
    }

    /** Sends the signed-in user's waiting opens. Safe to call at any time; one flush runs at once. */
    fun flush() {
        launchTracked { doFlush() }
    }

    private fun launchTracked(block: suspend () -> Unit) {
        if (destroyed) return
        val job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KiroraLogger.w("Could not report app opens", e)
            }
        }
        inFlight.add(job)
        job.invokeOnCompletion { inFlight.remove(job) }
    }

    private suspend fun doFlush() = flushMutex.withLock {
        val d = dao ?: return@withLock
        val user = identity.user ?: return@withLock
        if (!trackingAllowed() || clock() < max(nextRetryAt, holdUntil)) return@withLock
        val service = apiService() ?: return@withLock
        if (!purges.settle(d)) return@withLock
        d.deleteOlderThan(clock() - MAX_AGE_MS)
        val deviceId = preferences.getDeviceId()
        while (true) {
            val batch = d.oldestFor(user.subject, BATCH)
            if (batch.isEmpty()) return@withLock
            for (open in batch) {
                if (identity.user !== user || !trackingAllowed() || destroyed) return@withLock
                when (send(service, user, deviceId, open)) {
                    Result.DONE, Result.DROP -> d.delete(open.id)
                    Result.RETRY -> return@withLock
                }
            }
        }
    }

    private enum class Result { DONE, DROP, RETRY }

    private suspend fun send(service: KiroraApiService, user: SignedInUser, deviceId: String, open: AppOpenEntity): Result {
        val body = AppOpenRequest(JsonSnapshot.formatIso8601(open.occurredAt), open.pushAppId)
        val token = user.token
        var response = post(service, deviceId, body, token) ?: return Result.RETRY
        if (response.code() == 401) {
            val fresh = try {
                identity.refresh(user, token)
            } catch (_: UserTokenError) {
                onAuthFailure(AuthFailureReason.FETCH_FAILED, user)
                null
            }
            if (fresh != null && fresh != token) response = post(service, deviceId, body, fresh) ?: return Result.RETRY
            if (response.code() == 401) onAuthFailure(AuthFailureReason.REFUSED, user)
        }
        val code = response.code()
        return when {
            response.isSuccessful -> {
                user.accepted()
                backoffMs = FIRST_RETRY_MS
                nextRetryAt = 0L
                Result.DONE
            }
            // 404: not registered to this user in that push app (the next start registers it). 400: out of range.
            code == 404 || code == 400 -> {
                KiroraLogger.d("App open dropped: HTTP $code")
                Result.DROP
            }
            code == 401 || code == 408 || code == 425 || code == 429 || code >= 500 -> {
                KiroraLogger.w("Failed to report app open: HTTP $code; will retry.")
                applyBackoff(response)
                Result.RETRY
            }
            else -> {
                KiroraLogger.w("App open refused: HTTP $code")
                Result.DROP
            }
        }
    }

    private suspend fun post(service: KiroraApiService, deviceId: String, body: AppOpenRequest, token: String): Response<Unit>? = try {
        service.postAppOpen(deviceId, body, "Bearer $token")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        KiroraLogger.d("App open will be retried: ${e.javaClass.simpleName}")
        applyBackoff(null)
        null
    }

    private fun applyBackoff(response: Response<*>?) {
        var delayMs = backoffMs
        RetryAfter.parse(response?.headers(), clock())?.let {
            delayMs = max(it, delayMs)
            holdUntil = clock() + it
        }
        nextRetryAt = clock() + delayMs
        backoffMs = min(backoffMs * 2, MAX_RETRY_MS)
        // Nothing else may come along to retry, so the retry waits for itself.
        if (retryJob?.isActive != true && !destroyed) {
            retryJob = scope.launch {
                delay(delayMs)
                flush()
            }
        }
    }

    internal companion object {
        /** The visit window, shared with `SessionManager`, so opens and visits agree. */
        const val WINDOW_MS = 30 * 60 * 1000L
        private const val FIRST_RETRY_MS = 30_000L
        private const val MAX_RETRY_MS = 300_000L
        private const val MAX_QUEUED = 50
        private const val BATCH = 20
        /** Kirora accepts an open up to 7 days old. */
        private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val PENDING_MAX_AGE_MS = 5 * 60 * 1000L
    }
}
