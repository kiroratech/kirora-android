package com.kirora.androidsdk.push

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kirora.androidsdk.Kirora
import com.kirora.androidsdk.identity.Identity
import com.kirora.androidsdk.identity.SignedInUser
import com.kirora.androidsdk.model.AuthFailureReason
import com.kirora.androidsdk.model.KiroraApiError
import com.kirora.androidsdk.model.KiroraPushOptions
import com.kirora.androidsdk.model.PushPermission
import com.kirora.androidsdk.model.PushStatus
import com.kirora.androidsdk.model.UserTokenError
import com.kirora.androidsdk.net.KiroraApiService
import com.kirora.androidsdk.net.models.DeviceRegistrationRequest
import com.kirora.androidsdk.net.models.PushApp
import com.kirora.androidsdk.utils.GlobalPreferences
import com.kirora.androidsdk.utils.KiroraLogger
import com.kirora.androidsdk.utils.KiroraPreferences
import com.kirora.androidsdk.utils.PendingDetach
import com.kirora.androidsdk.utils.PushRegistration
import com.kirora.androidsdk.utils.RegistrationDetails
import com.kirora.androidsdk.utils.toApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import retrofit2.Response
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Push registration, sign-out detachment, the device's own push choice and marketing consent.
 *
 * Registrations and detachments run one at a time, in the order asked for. A detachment names the
 * `lastSeenAt` of the last registration, so a late retry can't undo a newer one: the server's answer
 * decides, with no local bookkeeping. A registration happens at `identify()`
 * and again only when the user, the push token, a device detail or the day changes, so a signed-in
 * start otherwise costs no push calls.
 */
internal class KiroraPushManager(
    private val context: Context,
    private val preferences: KiroraPreferences,
    private val global: GlobalPreferences,
    private val apiService: KiroraApiService,
    private val scope: CoroutineScope,
    private val identity: Identity,
    private val pushOptions: KiroraPushOptions?,
    private val trustedBaseUrl: HttpUrl?,
    private val listeners: PushListeners = PushListeners(),
    private val bridge: KiroraPushBridge? = null,
    private val registrationDetails: () -> RegistrationDetails = { RegistrationDetails() },
    /** A background token failure, with the token it concerns. */
    private val onAuthFailure: (AuthFailureReason, SignedInUser) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val pushEnabled = pushOptions != null
    private val operations = Mutex()
    private val cleanups: MutableSet<Cleanup> = ConcurrentHashMap.newKeySet()

    /** The user whose latest registration failed, so they don't count as registered. */
    @Volatile
    private var failedFor: SignedInUser? = null

    @Volatile
    private var destroyed = false

    /**
     * A signed-out user's detachment, retried in the background with their [token] (memory only)
     * or, with none held, the installation's device [secret]. Also kept on the device
     * ([persisted]), so it's retried with the secret after a restart.
     */
    private class Cleanup(
        /** Dropped for [secret] once Kirora refuses it (it expired while retrying, say). */
        @Volatile var token: String?,
        val installationId: String,
        /** The last successful registration's, so a late retry can't undo a newer registration. */
        val lastSeenAt: String?,
        val secret: String,
        val giveUpAt: Long
    ) {
        var retryDelayMs = FIRST_CLEANUP_RETRY_MS
        var job: Job? = null

        val authorization: String
            get() = token?.let { "Bearer $it" } ?: "Device $secret"

        val persisted: PendingDetach
            get() = PendingDetach(installationId, lastSeenAt, secret, giveUpAt)
    }


    fun isKiroraPush(data: Map<String, String>): Boolean = KiroraPushDisplay.isKiroraPush(data)

    // ---- Registration -------------------------------------------------------------------

    /** A new FCM token: kept, and registered for the signed-in user. */
    fun onNewToken(token: String) {
        global.setPushToken(token)
        tokenChecked = true
        val user = identity.user ?: return
        scope.launch { registerLogged(user) }
    }

    /** Registers in the background, logging a failure. */
    suspend fun registerLogged(user: SignedInUser) {
        try {
            register(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UserTokenError) {
            KiroraLogger.e("Push registration failed", e)
            onAuthFailure(AuthFailureReason.FETCH_FAILED, user)
        } catch (e: KiroraApiError) {
            KiroraLogger.e("Push registration failed", e)
            if (e.status == 401) onAuthFailure(AuthFailureReason.REFUSED, user)
        } catch (e: Exception) {
            KiroraLogger.e("Push registration failed", e)
        }
    }

    /**
     * Registers this device for [user], or tells Kirora notifications are denied. Gives up as soon
     * as someone else is signed in, so an earlier user's registration never lands after a later
     * one's. Throws [KiroraApiError] or [UserTokenError] for the caller to decide on.
     */
    suspend fun register(user: SignedInUser) {
        if (!pushEnabled) return
        operations.withLock {
            try {
                registerAs(user)
                if (failedFor === user) failedFor = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedFor = user
                throw e
            }
        }
    }

    private suspend fun registerAs(user: SignedInUser) {
        if (destroyed || identity.user !== user || preferences.isPushDisabled()) return
        // Kirora refused their token and nothing can replace it but identify(): quietly not now.
        if (user.waitingForToken) return
        val details = registrationDetails()
        val detailsKey = details.key()
        if (!notificationsEnabled()) return reportDenied(user, details, detailsKey)
        val token = currentToken()
        if (token == null) {
            KiroraLogger.d("No push token yet; the device registers once Firebase has one.")
            return
        }
        val last = preferences.getPushRegistration()
        if (last != null && last.permission == GRANTED && last.userSubject == user.subject && last.target == token &&
            last.details == detailsKey && clock() - last.at < REGISTRATION_REFRESH_MS &&
            last.pushAppId == (pushOptions?.pushAppId ?: last.pushAppId)
        ) return
        val app = choosePushApp(user) ?: return
        val pushAppId = app.pushAppId ?: return
        if (app.targetKind != null && app.targetKind != FCM_TOKEN) {
            KiroraLogger.e("Kirora's Android push app expects a ${app.targetKind}; this SDK registers an FCM token. Not registering.")
            return
        }
        if (identity.user !== user) return
        val installationId = preferences.getDeviceId()
        val request = DeviceRegistrationRequest(
            pushAppId = pushAppId,
            permission = GRANTED,
            targetKind = FCM_TOKEN,
            target = token,
            deviceSecret = preferences.getDeviceSecret(),
            appVersion = details.appVersion,
            osVersion = details.osVersion,
            locale = details.locale,
            timezone = details.timezone
        )
        val response = authorized(user) { apiService.registerDevice(installationId, request, it) }
        if (!response.isSuccessful) throw response.toApiError()
        user.accepted()
        // Saved even if the user changed meanwhile: it records who Kirora now has.
        preferences.setPushRegistration(
            PushRegistration(
                userSubject = user.subject,
                pushAppId = pushAppId,
                target = token,
                permission = GRANTED,
                details = detailsKey,
                at = clock(),
                lastSeenAt = response.body()?.data?.lastSeenAt
            )
        )
        KiroraLogger.d("Registered this device for push with ${app.name ?: pushAppId}.")
    }

    /**
     * Tells Kirora notifications are off on this device, so it stops counting it as reachable and
     * the device moves to [user] if someone else registered it. Only a device registered before
     * can say so: the denial names that registration's `lastSeenAt`.
     */
    private suspend fun reportDenied(user: SignedInUser, details: RegistrationDetails, detailsKey: String) {
        val last = preferences.getPushRegistration() ?: return
        val lastSeenAt = last.lastSeenAt ?: return
        val reported = last.permission == DENIED && last.userSubject == user.subject && last.details == detailsKey
        if (reported && clock() - last.at < REGISTRATION_REFRESH_MS) return
        val installationId = preferences.getDeviceId()
        val request = DeviceRegistrationRequest(
            pushAppId = last.pushAppId,
            permission = DENIED,
            lastSeenAt = lastSeenAt,
            deviceSecret = preferences.getDeviceSecret(),
            appVersion = details.appVersion,
            osVersion = details.osVersion,
            locale = details.locale,
            timezone = details.timezone
        )
        val response = authorized(user) { apiService.registerDevice(installationId, request, it) }
        if (!response.isSuccessful) {
            // 409: registered again somewhere this device doesn't know. 404: not this user's device.
            if (response.code() == 404 || response.code() == 409) {
                preferences.setPushRegistration(null)
                return
            }
            throw response.toApiError()
        }
        preferences.setPushRegistration(
            last.copy(
                userSubject = user.subject,
                target = null,
                permission = DENIED,
                details = detailsKey,
                at = clock(),
                lastSeenAt = response.body()?.data?.lastSeenAt ?: lastSeenAt
            )
        )
        KiroraLogger.d("Told Kirora notifications are off on this device.")
    }

    private suspend fun choosePushApp(user: SignedInUser): PushApp? {
        val response = authorized(user) { apiService.getPushApp("android", pushOptions?.pushAppId, it) }
        if (response.code() == 404) {
            KiroraLogger.e("No ready Android push app in this workspace; not registering.")
            return null
        }
        if (!response.isSuccessful) throw response.toApiError()
        return response.body()?.data?.takeIf { !it.pushAppId.isNullOrEmpty() }
    }

    /** [Identity.authorized], with a token waiting for identify() answered as Kirora would: 401. */
    private suspend fun <T> authorized(user: SignedInUser, call: suspend (authorization: String) -> Response<T>): Response<T> =
        identity.authorized(user, call) ?: throw KiroraApiError(401, "UNAUTHORIZED", "Kirora refused the user token: call identify() with a new one")

    /** Whether the kept token was checked with Firebase (or just came from it) in this process. */
    @Volatile
    private var tokenChecked = false

    /**
     * The token to register: the kept one, checked with Firebase once per process, since a refresh
     * whose `onNewToken` never arrived would otherwise leave a dead token registered. The kept one
     * when Firebase can't answer in time.
     */
    private suspend fun currentToken(): String? {
        val cached = global.getPushToken()
        if (cached != null && (tokenChecked || bridge == null)) return cached
        val fresh = if (cached == null) fetchFcmToken() else withTimeoutOrNull(TOKEN_CHECK_MS) { fetchFcmToken() }
        if (fresh != null) tokenChecked = true
        if (fresh != null && fresh != cached) global.setPushToken(fresh)
        return fresh ?: cached
    }

    private suspend fun fetchFcmToken(): String? {
        val b = bridge
        if (b == null) {
            KiroraLogger.e("Push needs the android-sdk-push artifact; no push token available.")
            return null
        }
        return try {
            b.fetchToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            KiroraLogger.d("FCM token unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    // ---- Status and the device's own choice ------------------------------------------------

    /**
     * Never prompts. The platform's permission first (Kirora's default channel turned off counts as
     * denied); with it granted, whether [user] is registered here.
     */
    suspend fun status(user: SignedInUser?): PushStatus {
        if (!pushEnabled || bridge == null) return PushStatus.UNSUPPORTED
        if (!notificationsEnabled()) {
            val prompted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || preferences.wasPermissionDenied() || runtimePermissionGranted()
            return if (prompted) PushStatus.DENIED else PushStatus.UNANSWERED
        }
        // Kirora's channel turned off: nothing is shown, and only the system settings can change that.
        if (defaultChannelBlocked()) return PushStatus.DENIED
        // Waits for a registration or detachment under way.
        operations.withLock { }
        val last = preferences.getPushRegistration()
        val registered = user != null && identity.user === user && failedFor !== user && !preferences.isPushDisabled() &&
            last != null && last.permission == GRANTED && last.userSubject == user.subject
        return if (registered) PushStatus.ENABLED else PushStatus.DISABLED
    }

    /**
     * The device's own push choice, kept for whoever signs in. Off detaches it from [user] (retried
     * as a sign-out's detachment is); on registers again if permission is granted, never prompting.
     */
    suspend fun setEnabled(enabled: Boolean, user: SignedInUser?) {
        if (enabled) {
            preferences.setPushDisabled(false)
            if (user != null && notificationsEnabled()) registerLogged(user)
            return
        }
        preferences.setPushDisabled(true)
        // With nobody signed in (after a relaunch, before identify()), by the device secret.
        if (user != null) detach(user, deadline = null) else detachLastRegistration(deadline = null)
    }

    /**
     * The token in this device's current registration for [user]; null before sign-in, while push
     * is turned off, once detached (or detaching), after a failed registration, or when denied.
     */
    fun registeredToken(user: SignedInUser?): String? {
        if (!pushEnabled || user == null || identity.user !== user || failedFor === user || preferences.isPushDisabled()) return null
        val last = preferences.getPushRegistration() ?: return null
        return if (last.permission == GRANTED && last.userSubject == user.subject) last.target else null
    }

    /** The signed-in user's push registration, if any: the push app it names. */
    fun registeredPushAppId(subject: String): String? {
        if (!pushEnabled) return null
        val last = preferences.getPushRegistration() ?: return null
        return if (last.userSubject == subject && last.permission == GRANTED) last.pushAppId else null
    }

    internal val pendingCleanups: Int
        get() = cleanups.size

    /** True while the device id is still needed: a registration or a detachment still to finish. */
    val needsDeviceId: Boolean
        get() = preferences.getPushRegistration() != null || cleanups.isNotEmpty() || preferences.getPendingDetaches().isNotEmpty()

    // ---- Detachment -----------------------------------------------------------------------

    /**
     * Detaches this device from [user], who is signing out, if it was registered for them, with
     * their own token (never refreshed through the next user). Returns once the first attempt ends,
     * by [deadline] (epoch ms) when given. A network error, 429 or 5xx is retried in the
     * background: 30 seconds, doubling to 5 minutes, never sooner than `Retry-After`, for 24 hours.
     */
    suspend fun detach(user: SignedInUser, deadline: Long?) = detachCaptured(deadline) { captureCleanup(user) }

    /**
     * [detach] with nobody signed in (a sign-out after a relaunch, before `identify()`): the
     * device's last registration, whoever made it, by its device secret.
     */
    suspend fun detachLastRegistration(deadline: Long?) = detachCaptured(deadline) {
        val last = preferences.getPushRegistration() ?: return@detachCaptured null
        // Made by whoever signed in meanwhile (an identify() while logout() waited): theirs, not to detach.
        if (identity.user?.subject == last.userSubject) return@detachCaptured null
        // Every registration sent the installation's secret, so it can be detached by it.
        capture(null, last)
    }

    private suspend fun detachCaptured(deadline: Long?, capture: () -> Cleanup?) {
        if (!pushEnabled) return
        // Not cancelled with the caller: behind a registration still under way (a token refresh,
        // say), it must still detach what that registration leaves, whenever it finishes.
        val work = scope.launch {
            operations.withLock {
                val cleanup = capture() ?: return@withLock
                val remaining = deadline?.let { it - clock() }
                if (remaining == null || remaining <= 0) {
                    // Nobody is waiting any more: a background attempt, retried as usual.
                    attemptCleanup(cleanup, first = true)
                } else if (withTimeoutOrNull(remaining) { attemptCleanup(cleanup, first = true) } == null) {
                    scheduleRetry(cleanup, null)
                }
            }
        }
        if (deadline == null) {
            work.join()
        } else {
            withTimeoutOrNull((deadline - clock()).coerceAtLeast(1L)) { work.join() }
        }
    }

    /**
     * Under [operations], so it sees the outcome of every registration started before it: the
     * registration [user] last made here, now to detach, or null if there is none.
     */
    private fun captureCleanup(user: SignedInUser): Cleanup? {
        val last = preferences.getPushRegistration()
        // Registered for someone else since: not this user's to detach.
        if (last == null || last.userSubject != user.subject) return null
        // A token Kirora already refused would only be refused again: straight to the device secret.
        return capture(if (user.waitingForToken) null else user.token, last)
    }

    private fun capture(token: String?, last: PushRegistration): Cleanup {
        val cleanup = Cleanup(token, preferences.getDeviceId(), last.lastSeenAt, preferences.getDeviceSecret(), clock() + CLEANUP_GIVE_UP_MS)
        cleanups.add(cleanup)
        // Kept on the device before the first attempt, so even a process ended now retries it.
        preferences.updatePendingDetach(cleanup.persisted, add = true)
        // Forgotten now, whatever the first attempt does: the user signing in again must register
        // again, and that registration finishes this cleanup instead of being undone by it.
        preferences.setPushRegistration(null)
        return cleanup
    }

    /** Finished, whatever the outcome: neither retried here nor after a restart. */
    private fun finish(cleanup: Cleanup) {
        cleanups.remove(cleanup)
        preferences.updatePendingDetach(cleanup.persisted, add = false)
    }

    /**
     * Resumes the detachments a previous process left unfinished, with their device secrets, within
     * the 24 hours from each sign-out.
     */
    fun resumePendingDetaches() {
        if (!pushEnabled) return
        val now = clock()
        preferences.getPendingDetaches().forEach { pending ->
            if (cleanups.any { it.persisted.sameAs(pending) }) return@forEach
            val cleanup = Cleanup(null, pending.installationId, pending.lastSeenAt, pending.secret, pending.giveUpAt)
            if (now >= pending.giveUpAt) {
                finish(cleanup)
                return@forEach
            }
            cleanups.add(cleanup)
            scope.launch { operations.withLock { attemptCleanup(cleanup, first = true) } }
        }
    }

    private suspend fun attemptCleanup(cleanup: Cleanup, first: Boolean) {
        // Done meanwhile, by a registration that took the device over.
        if (cleanup !in cleanups || destroyed) return
        val error: KiroraApiError? = try {
            val response = apiService.deleteDevice(cleanup.installationId, cleanup.lastSeenAt, cleanup.authorization)
            when {
                response.isSuccessful -> null
                // The user registered this device again since: that registration stays, and Kirora
                // detached only the older ones. Nothing left to retry.
                response.code() == 409 -> {
                    KiroraLogger.d("The device was registered again since; Kirora kept that registration.")
                    null
                }
                else -> response.toApiError()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KiroraApiError(0, null, "Network error: ${e.javaClass.simpleName}")
        }
        // The user's token refused: on at once with the device secret. (A 401 to the
        // secret itself is terminal: one matching nothing answers 204, so this can't loop.)
        if (error?.status == 401 && cleanup.token != null) {
            cleanup.token = null
            KiroraLogger.d("The signed-out user's token was refused; detaching with the device secret.")
            return attemptCleanup(cleanup, first)
        }
        if (error == null) {
            finish(cleanup)
            KiroraLogger.d("Detached this device from the signed-out user.")
            return
        }
        // A network error (status 0), 429 or 5xx is worth repeating; 401, 403, 404 or any other refusal is not.
        if (error.status != 0 && !error.retryable) {
            finish(cleanup)
            KiroraLogger.e("Couldn't detach this device from the signed-out user: HTTP ${error.status}")
            return
        }
        if (first) KiroraLogger.d("Couldn't detach this device yet; retrying in the background.")
        scheduleRetry(cleanup, error.retryAfterMs)
    }

    private fun scheduleRetry(cleanup: Cleanup, retryAfterMs: Long?) {
        if (destroyed || cleanup !in cleanups) return
        val waitMs = max(cleanup.retryDelayMs, retryAfterMs ?: 0L)
        if (clock() + waitMs > cleanup.giveUpAt) {
            finish(cleanup)
            KiroraLogger.e("Gave up detaching this device from the signed-out user after 24 hours.")
            return
        }
        cleanup.retryDelayMs = min(cleanup.retryDelayMs * 2, MAX_CLEANUP_RETRY_MS)
        cleanup.job?.cancel()
        cleanup.job = scope.launch {
            delay(waitMs)
            operations.withLock { attemptCleanup(cleanup, first = false) }
        }
    }

    /** Stops detachments still being retried. */
    fun destroy() {
        destroyed = true
        cleanups.forEach { it.job?.cancel() }
        cleanups.clear()
    }

    // ---- Permission -----------------------------------------------------------------------

    fun requestPushPermission(caller: Context, callback: (PushPermission) -> Unit) {
        if (!pushEnabled) {
            callback(PushPermission.UNSUPPORTED)
            return
        }
        if (notificationsEnabled()) {
            permissionAnswered(PushPermission.GRANTED, callback)
            return
        }
        val b = bridge
        if (b == null) {
            KiroraLogger.e("Push needs the android-sdk-push artifact; permission request unsupported.")
            callback(PushPermission.UNSUPPORTED)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || runtimePermissionGranted()) {
            // Notifications are off in the system settings: there is no prompt to show.
            permissionAnswered(PushPermission.DENIED, callback)
            return
        }
        b.requestNotificationPermission(caller) { granted ->
            when (granted) {
                true -> permissionAnswered(if (notificationsEnabled()) PushPermission.GRANTED else PushPermission.DENIED, callback)
                false -> permissionAnswered(PushPermission.DENIED, callback)
                null -> callback(PushPermission.UNANSWERED)
            }
        }
    }

    /** `granted` turns the device's push back on and registers; `denied` is reported to Kirora. */
    private fun permissionAnswered(answer: PushPermission, callback: (PushPermission) -> Unit) {
        preferences.setPermissionDenied(answer == PushPermission.DENIED)
        if (answer == PushPermission.GRANTED) preferences.setPushDisabled(false)
        val user = identity.user
        if (user == null) {
            callback(answer)
            return
        }
        scope.launch {
            registerLogged(user)
            callback(answer)
        }
    }

    private fun runtimePermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun defaultChannelBlocked(): Boolean = try {
        KiroraPushDisplay.defaultChannelBlocked(context, PushDisplayConfig.of(pushOptions))
    } catch (_: Throwable) {
        false
    }

    private fun notificationsEnabled(): Boolean = try {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    } catch (_: Throwable) {
        false
    }

    // ---- Incoming notifications ----------------------------------------------------------

    /** Draws a Kirora push (anything else is left alone); true when it was Kirora's. */
    fun onMessageReceived(data: Map<String, String>): Boolean =
        KiroraPushDisplay.handle(
            context,
            data,
            listeners,
            PushDisplayConfig.of(pushOptions),
            Kirora.foregroundHandler ?: pushOptions?.onForegroundNotification
        )

    /** [Kirora.handleNotificationIntent]. Safe to call repeatedly for one intent; one reopened from Recents is not a tap. */
    @Suppress("DEPRECATION")
    fun handleNotificationIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return false
        return try {
            val extras = intent.extras ?: return false
            if (extras.getBoolean(HANDLED_EXTRA, false)) return false
            if (extras.getString("kirora") != "true") return false
            val data = mutableMapOf<String, String>()
            for (key in extras.keySet()) {
                extras.get(key)?.let { data[key] = it.toString() }
            }
            intent.putExtra(HANDLED_EXTRA, true)
            val report = Kirora.reportNotificationTap(context, data["trackingUrl"], clicked = false)
            // The intent is marked handled, so this is its only delivery: it happens even if the
            // client is destroyed during the wait (cut short then). ATOMIC: runs even if cancelled
            // before it starts, so the finally always delivers.
            scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.ATOMIC) {
                try {
                    Kirora.awaitTapReport(report)
                } finally {
                    Kirora.deliverNotificationTap(data)
                }
            }
            true
        } catch (e: Throwable) {
            // An intent from outside the app can carry extras that fail to unparcel.
            KiroraLogger.w("Could not read notification intent", e)
            false
        }
    }

    companion object {
        private const val GRANTED = "granted"
        private const val DENIED = "denied"
        private const val FCM_TOKEN = "fcm_token"
        private const val HANDLED_EXTRA = "kirora.handled"
        /** How long a registration waits for Firebase to confirm the kept token. */
        private const val TOKEN_CHECK_MS = 5_000L
        /** Registered again at most daily for the same user, token and details. */
        private const val REGISTRATION_REFRESH_MS = 24L * 60 * 60 * 1000
        private const val FIRST_CLEANUP_RETRY_MS = 30_000L
        private const val MAX_CLEANUP_RETRY_MS = 300_000L
        private const val CLEANUP_GIVE_UP_MS = 24L * 60 * 60 * 1000
    }
}

private fun RegistrationDetails.key(): String = "$appVersion|$osVersion|$locale|$timezone"

internal data class KiroraNotificationButton(val title: String, val url: String)

internal fun parseButtonsJson(jsonStr: String): List<KiroraNotificationButton> {
    if (jsonStr.isBlank()) return emptyList()
    return try {
        val list = mutableListOf<KiroraNotificationButton>()
        val array = com.google.gson.JsonParser.parseString(jsonStr).asJsonArray
        for (element in array) {
            val obj = element.asJsonObject
            val title = obj.get("title")?.asString ?: continue
            val url = obj.get("url")?.asString ?: continue
            // Buttons open web pages: https only.
            if (!url.startsWith("https://", ignoreCase = true)) continue
            list.add(KiroraNotificationButton(title, url))
        }
        list
    } catch (e: Exception) {
        KiroraLogger.w("Failed to parse push notification buttons", e)
        emptyList()
    }
}
