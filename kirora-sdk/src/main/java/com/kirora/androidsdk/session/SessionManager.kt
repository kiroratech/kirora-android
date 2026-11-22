package com.kirora.androidsdk.session

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.kirora.androidsdk.utils.KiroraLogger
import com.kirora.androidsdk.utils.KiroraPreferences
import java.util.UUID

/**
 * Visits: activity is a tracked event or the app returning to the foreground. A visit ends after
 * 30 minutes without activity, but time in the foreground never ends one. The visit is kept on
 * the device, so a killed app continues it, but only for its owner: a different user signing in,
 * or a sign-out, starts a new one. Nothing is stored while tracking is disallowed.
 */
internal class SessionManager(
    private val preferences: KiroraPreferences,
    private val trackSessions: Boolean,
    private val trackAppLifecycle: Boolean,
    private val appVersion: String,
    private val appBuild: String,
    private val trackEvent: (name: String, properties: Map<String, Any?>?) -> Unit,
    /** The `sub` of the user signed in now, or null. */
    private val owner: () -> String?,
    private val onBackground: () -> Unit,
    private val onForeground: () -> Unit = {},
    /** The app opened or came back to the foreground. Not an event; see `KiroraAppOpenReporter`. */
    private val onAppOpen: () -> Unit = {},
    private val isTrackingAllowed: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
    private val processLifecycle: () -> Lifecycle = { ProcessLifecycleOwner.get().lifecycle }
) : DefaultLifecycleObserver {

    @Volatile
    var sessionId: String? = null
        private set

    private var sessionOwner: String? = null

    @Volatile
    private var foreground = false
    /** The start the lifecycle replays to a new observer was already counted by the client this one replaced. */
    private var skipReplayedStart = false
    private var lastActivity = 0L
    private var lastPersisted = 0L

    @Volatile
    private var stopped = false

    // [ensureBegun]'s two flags: [begun] when it starts (under the lock, since the visit it tracks
    // comes back in on the same thread), [ready] when it has finished (the lock-free path after).
    private var begun = false

    @Volatile
    private var ready = false

    /**
     * Reads the stored visit (continuing it or starting one) and the app version, once. Done off
     * the main thread at start; anything needing the visit before that does it first, and another
     * thread doing it meanwhile is waited for.
     */
    fun ensureBegun() {
        if (ready) return
        synchronized(this) {
            if (begun) return
            begun = true
            try {
                beginTracking()
            } finally {
                ready = true
            }
        }
    }

    /** Watches the app's lifecycle; the stored visit is read by [ensureBegun]. */
    fun start() {
        runOnMain {
            // Stopped before this ran (a client made off the main thread, destroyed at once): no observer.
            if (stopped) return@runOnMain
            val lifecycle = processLifecycle()
            if (watching !== lifecycle) {
                watching = lifecycle
                lifecycle.addObserver(processWatcher)
            }
            // addObserver replays a start already under way at once, on this thread.
            skipReplayedStart = foregroundCounted
            lifecycle.addObserver(this)
            skipReplayedStart = false
        }
    }

    private fun beginTracking() {
        if (!isTrackingAllowed()) return
        if (trackSessions) restoreOrStart()
        if (trackAppLifecycle) checkAppInstallOrUpdate()
    }

    /** Consent arrived after start: tracking starts from now; nothing skipped before is made up. */
    fun onConsentGranted() {
        if (trackSessions) onActivity()
        if (trackAppLifecycle) checkAppInstallOrUpdate()
    }

    fun stop() {
        stopped = true
        runOnMain { processLifecycle().removeObserver(this) }
    }

    /** Called for every tracked event, before it is captured. */
    @Synchronized
    fun onActivity() {
        ensureBegun()
        if (!trackSessions || !isTrackingAllowed()) return
        val now = clock()
        val current = owner()
        val ownerChanged = current != null && sessionOwner != null && current != sessionOwner
        if (sessionId == null || ownerChanged || (!foreground && isExpired(now))) {
            startNewSession(current)
        } else {
            if (sessionOwner == null && current != null) {
                // Begun before sign-in: the visit is this user's from now on.
                sessionOwner = current
                preferences.setSessionOwner(current)
            }
            touch(now)
        }
    }

    /** Forgets the visit; the next activity begins a new one. For a sign-out, a user change or a consent withdrawal. */
    @Synchronized
    fun reset() {
        sessionId = null
        sessionOwner = null
        lastActivity = 0L
        preferences.clearSession()
    }

    /** The stored visit's owner, so a sign-in as someone else can end it. */
    val currentOwner: String?
        @Synchronized get() = sessionOwner

    @Synchronized
    private fun restoreOrStart() {
        val now = clock()
        lastActivity = preferences.getLastActivityTime()
        val stored = preferences.getSessionId()
        val storedOwner = preferences.getSessionOwner()
        val current = owner()
        val sameOwner = current == null || storedOwner == null || storedOwner == current
        if (!stored.isNullOrEmpty() && lastActivity > 0 && !isExpired(now) && sameOwner) {
            sessionId = stored
            sessionOwner = storedOwner ?: current
            touch(now)
            KiroraLogger.d("Restored active session.")
        } else {
            startNewSession(current)
        }
    }

    @Synchronized
    private fun startNewSession(newOwner: String?) {
        val newSessionId = UUID.randomUUID().toString()
        sessionId = newSessionId
        sessionOwner = newOwner
        preferences.setSessionId(newSessionId)
        preferences.setSessionOwner(newOwner)
        touch(clock(), persist = true)
        trackEvent("Session Started", null)
        KiroraLogger.d("Started new session.")
    }

    private fun isExpired(now: Long) = lastActivity > 0 && now - lastActivity >= SESSION_TIMEOUT_MS

    private fun touch(now: Long, persist: Boolean = false) {
        lastActivity = now
        // Persisting on every event would hit disk constantly; a few seconds of drift is harmless.
        if (persist || now - lastPersisted > PERSIST_INTERVAL_MS) {
            lastPersisted = now
            preferences.setLastActivityTime(now)
        }
    }

    private fun checkAppInstallOrUpdate() {
        if (!preferences.isInstalled()) {
            preferences.setInstalled(true)
            preferences.setLastApp(appVersion, appBuild)
            trackEvent("Application Installed", mapOf("version" to appVersion, "build" to appBuild))
            return
        }
        val lastVersion = preferences.getLastAppVersion()
        val lastBuild = preferences.getLastAppBuild()
        if (lastVersion != appVersion || (lastBuild != null && lastBuild != appBuild)) {
            preferences.setLastApp(appVersion, appBuild)
            trackEvent(
                "Application Updated",
                mapOf(
                    "version" to appVersion,
                    "build" to appBuild,
                    "previous_version" to lastVersion,
                    "previous_build" to lastBuild
                )
            )
        }
    }

    private fun runOnMain(block: () -> Unit) {
        val run = {
            try {
                block()
            } catch (e: Throwable) {
                KiroraLogger.w("Could not update the process lifecycle observer", e)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run() else Handler(Looper.getMainLooper()).post { run() }
    }

    override fun onStart(owner: LifecycleOwner) {
        if (stopped) return
        val fromBackground = try {
            countStart()
        } finally {
            foreground = true
        }
        if (fromBackground != null && trackAppLifecycle) {
            trackEvent(
                "Application Opened",
                mapOf("from_background" to fromBackground, "version" to appVersion, "build" to appBuild)
            )
        }
    }

    /**
     * Counts a start as an app open and activity, answering whether it was a return from the
     * background; null for one not to report. Runs while the app still counts as in the
     * background, so a visit that ran out while the app was away ends.
     */
    private fun countStart(): Boolean? {
        // A replacement client (a hot reload, say): the same launch, not another open.
        if (skipReplayedStart) return null
        foregroundCounted = true
        // The first start of this process is a cold launch; any later one is a return from the background.
        val fromBackground = processStarted
        processStarted = true
        onForeground()
        if (!isTrackingAllowed()) return null
        onAppOpen()
        // Returning to the foreground is activity: it keeps the visit or begins one.
        onActivity()
        return fromBackground
    }

    override fun onStop(owner: LifecycleOwner) {
        if (stopped) return
        foreground = false
        if (isTrackingAllowed()) {
            synchronized(this) {
                // The 30 minutes count from the moment the app leaves the foreground.
                if (sessionId != null) touch(clock(), persist = true)
            }
            if (trackAppLifecycle) trackEvent("Application Backgrounded", null)
        }
        // The last chance to send before the OS may end the process.
        onBackground()
    }

    companion object {
        // Shared by every client in the process, so a replacement doesn't count a launch or a
        // return again, and knows a later start is a return from the background. Main thread only.
        private var processStarted = false
        private var foregroundCounted = false
        /** The lifecycle [processWatcher] watches: the process's, once. */
        private var watching: Lifecycle? = null

        /** Ends the counted foreground even while no client is listening (between a destroy() and the next client). */
        private val processWatcher = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                foregroundCounted = false
            }
        }

        internal fun resetForTesting() {
            processStarted = false
            foregroundCounted = false
        }

        const val SESSION_TIMEOUT_MS = 30 * 60 * 1000L
        private const val PERSIST_INTERVAL_MS = 5_000L
    }
}
