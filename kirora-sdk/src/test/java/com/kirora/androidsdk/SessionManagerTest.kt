package com.kirora.androidsdk

import android.content.Context
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.kirora.androidsdk.session.SessionManager
import com.kirora.androidsdk.utils.KiroraPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SessionManagerTest {

    private lateinit var context: Context
    private lateinit var preferences: KiroraPreferences
    private val tracked = mutableListOf<Pair<String, Map<String, Any?>?>>()
    private var opens = 0
    private var trackingAllowed = true
    private var owner: String? = null
    private var now = 1_000_000_000L

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        SessionManager.resetForTesting()
        preferences = KiroraPreferences(context, "session-test")
        preferences.clearSession()
        preferences.setInstalled(false)
        tracked.clear()
        opens = 0
        trackingAllowed = true
        owner = null
    }

    private val names get() = tracked.map { it.first }

    /** Started as Kirora starts it: lifecycle watched, stored visit read (off the main thread there). */
    private fun SessionManager.begun() = also { start(); ensureBegun() }

    private fun manager(
        trackSessions: Boolean = true,
        lifecycle: Boolean = false,
        version: String = "1.0.0",
        build: String = "1",
        processLifecycle: () -> Lifecycle = { ProcessLifecycleOwner.get().lifecycle }
    ) =
        SessionManager(
            preferences = preferences,
            trackSessions = trackSessions,
            trackAppLifecycle = lifecycle,
            appVersion = version,
            appBuild = build,
            trackEvent = { name, properties -> tracked.add(name to properties) },
            owner = { owner },
            onBackground = {},
            onAppOpen = { opens++ },
            isTrackingAllowed = { trackingAllowed },
            clock = { now },
            processLifecycle = processLifecycle
        )

    @Test
    fun appOpensAreReportedOnEveryReturnToTheForegroundAndAreNotEvents() {
        val m = manager(trackSessions = false).begun()
        m.onStart(ProcessLifecycleOwner.get())
        m.onStop(ProcessLifecycleOwner.get())
        m.onStart(ProcessLifecycleOwner.get())
        assertEquals(2, opens)
        assertTrue(tracked.isEmpty())
    }

    @Test
    fun sessionStartedIsQueuedBeforeTheInstallWhenEachEventCapturesItsActivity() {
        // As the client does: tracking an event first captures activity, then queues the event.
        var m: SessionManager? = null
        m = SessionManager(
            preferences, trackSessions = true, trackAppLifecycle = true, appVersion = "1", appBuild = "1",
            trackEvent = { name, properties -> m!!.onActivity(); tracked.add(name to properties) },
            owner = { null }, onBackground = {}, processLifecycle = { ProcessLifecycleOwner.get().lifecycle }
        )
        m.ensureBegun()
        assertEquals(listOf("Session Started", "Application Installed"), names)
    }

    @Test
    fun theInstallIsTrackedOnceConsentBecomesReadableAfterStart() {
        // Storage locked until the first unlock: consent couldn't be read at start.
        trackingAllowed = false
        val m = manager(trackSessions = false, lifecycle = true).begun()
        assertTrue(tracked.isEmpty())
        // Readable now, and allowed: no grant, just the next activity.
        trackingAllowed = true
        m.onActivity()
        m.onActivity()
        assertEquals(listOf("Application Installed"), names)
    }

    @Test
    fun nothingWhileTrackingIsDisallowedAndNothingSkippedIsMadeUp() {
        trackingAllowed = false
        val m = manager(lifecycle = true).begun()
        m.onStart(ProcessLifecycleOwner.get())
        assertEquals(0, opens)
        assertTrue(tracked.isEmpty())
        assertNull(preferences.getSessionId())

        trackingAllowed = true
        m.onConsentGranted()
        assertEquals(listOf("Session Started", "Application Installed"), names)
        assertEquals(0, opens) // the open before consent is not reported afterwards
    }

    @Test
    fun aVisitIsRestoredWithinThirtyMinutesAndRenewedAfter() {
        val first = manager().begun()
        val id = first.sessionId
        assertNotNull(id)

        now += 5 * 60 * 1000L
        val restored = manager().begun()
        assertEquals(id, restored.sessionId)
        assertEquals(1, names.count { it == "Session Started" })

        now += 35 * 60 * 1000L
        val renewed = manager().begun()
        assertNotEquals(id, renewed.sessionId)
        assertEquals(2, names.count { it == "Session Started" })
    }

    @Test
    fun aReturnAfterThirtyMinutesInTheBackgroundBeginsANewVisit() {
        val m = manager(lifecycle = true).begun()
        val app = ProcessLifecycleOwner.get()
        m.onStart(app)
        val first = m.sessionId
        m.onStop(app)
        now += SessionManager.SESSION_TIMEOUT_MS + 60_000
        tracked.clear()
        m.onStart(app)
        assertNotEquals(first, m.sessionId)
        assertEquals(listOf("Session Started", "Application Opened"), names)
        assertEquals(true, tracked.last().second?.get("from_background"))

        // Back within 30 minutes: the same visit.
        val second = m.sessionId
        m.onStop(app)
        now += SessionManager.SESSION_TIMEOUT_MS - 60_000
        m.onStart(app)
        assertEquals(second, m.sessionId)
    }

    @Test
    fun aLaunchStillWaitingForStartUpWhenItsClientIsReplacedIsCountedByTheReplacement() {
        val owner = FakeProcess()
        val held = mutableListOf<() -> Unit>()
        fun manager(hold: Boolean) = SessionManager(
            preferences, trackSessions = false, trackAppLifecycle = true, appVersion = "1", appBuild = "1",
            trackEvent = { name, properties -> tracked.add(name to properties) }, owner = { null },
            onBackground = {}, onAppOpen = { opens++ }, processLifecycle = { owner.lifecycle },
            whenStartedUp = { change -> if (hold) held += change else change() }
        )
        val first = manager(hold = true).begun()
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START) // waits for first's start-up
        first.stop()
        manager(hold = false).begun() // the replacement hears the start replayed
        held.forEach { it() } // the first's, run late: it's stopped, and counts nothing
        assertEquals(1, opens)
        assertEquals(listOf(false), tracked.filter { it.first == "Application Opened" }.map { it.second?.get("from_background") })
    }

    @Test
    fun aReplacementsReplayedStartHeldForStartUpIsStillNotCountedAgain() {
        val owner = FakeProcess()
        val held = mutableListOf<() -> Unit>()
        fun manager(hold: Boolean) = SessionManager(
            preferences, trackSessions = false, trackAppLifecycle = true, appVersion = "1", appBuild = "1",
            trackEvent = { name, properties -> tracked.add(name to properties) }, owner = { null },
            onBackground = {}, onAppOpen = { opens++ }, processLifecycle = { owner.lifecycle },
            whenStartedUp = { change -> if (hold) held += change else change() }
        )
        val first = manager(hold = false).begun()
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        first.stop()
        // The replacement's start-up hasn't read storage yet: the replayed start waits for it.
        manager(hold = true).begun()
        assertEquals(1, held.size)
        held.forEach { it() }
        assertEquals(1, opens)
        assertEquals(1, names.count { it == "Application Opened" })
    }

    @Test
    fun aReplacementStartedInTheForegroundDoesntCountTheLaunchAgain() {
        val owner = FakeProcess()
        fun manager() = SessionManager(
            preferences, trackSessions = false, trackAppLifecycle = true, appVersion = "1", appBuild = "1",
            trackEvent = { name, properties -> tracked.add(name to properties) }, owner = { null },
            onBackground = {}, onAppOpen = { opens++ }, processLifecycle = { owner.lifecycle }
        )
        val first = manager().begun()
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals("the launch, counted once", 1, opens)
        first.stop()
        // A hot reload: the replacement hears the same start replayed, and counts nothing.
        val second = manager().begun()
        assertEquals(1, opens)
        assertEquals(1, names.count { it == "Application Opened" })

        // A real return from the background still counts, once.
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals(2, opens)
        assertEquals(true, tracked.last { it.first == "Application Opened" }.second?.get("from_background"))

        // Destroyed in the background, and a new client on the next return: that return counts.
        second.stop()
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        manager().start()
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals(3, opens)
    }

    @Test
    fun foregroundTimeNeverEndsAVisit() {
        val m = manager().begun()
        m.onStart(ProcessLifecycleOwner.get())
        val id = m.sessionId
        now += 2 * 60 * 60 * 1000L
        m.onActivity()
        assertEquals(id, m.sessionId)
    }

    @Test
    fun aVisitBegunBeforeSignInBecomesTheUsersAndAnotherUserStartsAnother() {
        val m = manager().begun()
        val id = m.sessionId
        owner = "user-a"
        m.onActivity()
        assertEquals(id, m.sessionId)
        assertEquals("user-a", m.currentOwner)

        owner = "user-b"
        m.onActivity()
        assertNotEquals(id, m.sessionId)
        assertEquals("user-b", m.currentOwner)
    }

    @Test
    fun resetEndsTheVisit() {
        val m = manager().begun()
        val id = m.sessionId
        m.reset()
        assertNull(m.sessionId)
        assertNull(preferences.getSessionId())
        m.onActivity()
        assertNotEquals(id, m.sessionId)
    }

    @Test
    fun noSessionWhenSessionsAreOff() {
        val m = manager(trackSessions = false).begun()
        m.onActivity()
        assertNull(m.sessionId)
        assertTrue(tracked.isEmpty())
    }

    @Test
    fun lifecycleEventsCarryTheDocumentedProperties() {
        manager(trackSessions = false, lifecycle = true).begun()
        assertEquals("Application Installed" to mapOf("version" to "1.0.0", "build" to "1"), tracked.single())

        tracked.clear()
        val m = manager(trackSessions = false, lifecycle = true, version = "2.0.0", build = "2").begun()
        assertEquals(
            "Application Updated" to mapOf("version" to "2.0.0", "build" to "2", "previous_version" to "1.0.0", "previous_build" to "1"),
            tracked.single()
        )

        tracked.clear()
        m.onStart(ProcessLifecycleOwner.get())
        m.onStop(ProcessLifecycleOwner.get())
        m.onStart(ProcessLifecycleOwner.get())
        assertEquals(
            listOf(
                "Application Opened" to mapOf("from_background" to false, "version" to "2.0.0", "build" to "2"),
                "Application Backgrounded" to null,
                "Application Opened" to mapOf("from_background" to true, "version" to "2.0.0", "build" to "2")
            ),
            tracked
        )
    }

    @Test
    fun aManagerStoppedBeforeItsObserverWasAddedNeverAddsIt() {
        val process = FakeProcess()
        val m = manager(processLifecycle = { process.lifecycle })
        // Made off the main thread: adding the observer is posted. Destroyed before it runs.
        Thread { m.start() }.apply { start() }.join()
        m.stop()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("only the process watcher, never this manager", true, process.registry.observerCount <= 1)
    }
}

/** The app process's lifecycle, driven by the test. */
private class FakeProcess : LifecycleOwner {
    val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle get() = registry
}
