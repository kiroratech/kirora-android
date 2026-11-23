package com.kirora.androidsdk

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import androidx.lifecycle.Lifecycle
import com.kirora.androidsdk.model.KiroraOptions
import com.kirora.androidsdk.model.KiroraPushOptions
import com.kirora.androidsdk.net.FakeKiroraApiService
import com.kirora.androidsdk.push.KiroraPushTrampolineActivity
import com.kirora.androidsdk.utils.AppLifecycle
import com.kirora.androidsdk.utils.KiroraPreferences
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** The risks the audit left open, each fixed: what used to go wrong no longer does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class KnownRisksTest : KiroraTestBase() {

    @Test
    fun eventsTrackedAsTheClientStartsKeepTheirOrder() = runBlocking {
        val kirora = Kirora(context, KiroraOptions(clientKey = "kirora_pk_order", trackSessions = false), KiroraEnvironment(pushBridge = bridge, apiService = fake))
        // At once, before start-up has read consent: these go through the queue's own reads.
        (1..5).forEach { kirora.track("Early $it") }
        kirora.awaitReady()
        kirora.track("Late")
        kirora.identify(jwt("user-a"))
        kirora.flush()
        waitUntil(message = "all six sent") { fake.sentEventNames.size >= 6 }
        assertEquals((1..5).map { "Early $it" } + "Late", fake.sentEventNames.filter { it.startsWith("Early") || it == "Late" })
    }

    @Test
    fun anEventTrackedBeforeStartUpStillWaitsForRequiredConsent() = runBlocking {
        val kirora = Kirora(
            context,
            KiroraOptions(clientKey = "kirora_pk_required", trackSessions = false, requireTrackingConsent = true),
            KiroraEnvironment(pushBridge = bridge, apiService = fake)
        )
        kirora.track("Before any answer")
        kirora.awaitReady()
        delay(100)
        assertEquals(0, kirora.queued())
    }

    /** A client whose start-up and event queue are both held, so an event tracked now is captured on the queue. */
    private fun heldClient(options: KiroraOptions, test: (kirora: Kirora, release: () -> Unit) -> Unit) {
        val startUp = java.util.concurrent.Executors.newSingleThreadExecutor()
        val startUpGate = java.util.concurrent.CountDownLatch(1)
        val queueGate = java.util.concurrent.CountDownLatch(1)
        startUp.execute { startUpGate.await() }
        Kirora.holdQueueForTesting(queueGate)
        try {
            val kirora = Kirora(context, options, KiroraEnvironment(io = startUp.asCoroutineDispatcher(), pushBridge = bridge, apiService = fake))
            test(kirora) {
                startUpGate.countDown()
                queueGate.countDown()
            }
        } finally {
            startUpGate.countDown()
            queueGate.countDown()
            startUp.shutdown()
        }
    }

    @Test
    fun anEarlyEventKeepsTheLanguageOfItsMoment() {
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("en-GB"))
            heldClient(KiroraOptions(clientKey = "kirora_pk_early_locale", trackSessions = false)) { kirora, release ->
                kirora.track("Early")
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag("fr-FR")) // changed before the queue gets to it
                release()
                runBlocking {
                    kirora.awaitReady()
                    kirora.identify(jwt("user-a"))
                    kirora.flush()
                    waitUntil(message = "sent") { "Early" in fake.sentEventNames }
                }
                assertEquals("en-GB", fake.sentEvent("Early").context.asJsonObject.get("locale").asString)
            }
        } finally {
            java.util.Locale.setDefault(before)
        }
    }

    @Test
    fun aGrantGivenAfterAnEarlyEventDoesntReachBackToIt() = heldClient(
        KiroraOptions(clientKey = "kirora_pk_grant_later", trackSessions = false, requireTrackingConsent = true)
    ) { kirora, release ->
        kirora.track("Before the grant")
        kirora.setTrackingConsent(true)
        kirora.track("After the grant")
        release()
        runBlocking {
            kirora.awaitReady()
            kirora.identify(jwt("user-a"))
            kirora.flush()
            waitUntil(message = "the event after the grant") { "After the grant" in fake.sentEventNames }
        }
        assertEquals(listOf("After the grant"), fake.sentEventNames)
    }

    @Test
    fun anEarlyEventComesAfterTheSessionStartedItBegan() = heldClient(
        KiroraOptions(clientKey = "kirora_pk_early_visit", trackSessions = true)
    ) { kirora, release ->
        kirora.track("First")
        release()
        runBlocking {
            kirora.awaitReady()
            kirora.identify(jwt("user-a"))
            kirora.flush()
            waitUntil(message = "both sent") { "First" in fake.sentEventNames }
        }
        assertEquals(listOf("Session Started", "First"), fake.sentEventNames.filter { it == "Session Started" || it == "First" })
    }

    @Test
    fun anAppOpenHeldByRetryAfterIsSentWhenTheHoldEndsAfterARestart() = runBlocking {
        val push = KiroraOptions(clientKey = "kirora_pk_test", trackSessions = false, push = KiroraPushOptions())
        val first = start(push)
        first.identify(jwt("user-a"))
        fake.appOpenResponder = { FakeKiroraApiService.error(429, "RATE_LIMITED", retryAfterSeconds = 1) }
        first.appOpens!!.onAppOpen()
        waitUntil(message = "the held attempt") { fake.appOpens.size == 1 }
        first.destroy()

        fake.appOpenResponder = null
        val second = start(push, clearQueue = false)
        second.identify(jwt("user-a"))
        // Nothing else happens: the restored hold's own timer sends it once the second has passed.
        waitUntil(message = "sent when the hold ends") { fake.appOpens.size == 2 }
    }

    @Test
    fun aForegroundChangeHeldForStartUpIsJudgedByTheConsentOfItsMoment() {
        val process = fakeProcess()
        heldClient(
            KiroraOptions(clientKey = "kirora_pk_open_before_grant", trackSessions = false, trackAppLifecycle = true, requireTrackingConsent = true)
        ) { kirora, release ->
            shadowOf(Looper.getMainLooper()).idle() // the observer is added
            process.handleLifecycleEvent(Lifecycle.Event.ON_START) // before any answer
            kirora.setTrackingConsent(true)
            release()
            runBlocking {
                kirora.awaitReady()
                delay(200)
                val names = kirora.databaseForTesting!!.eventDao().unowned().map { it.name }
                assertTrue("the open before the grant isn't collected: $names", "Application Opened" !in names)
            }
        }
    }

    @Test
    fun anEarlyEventKeepsTheVisitOfItsMomentWhenSomeoneElseSignsInAtOnce() {
        val sessions = KiroraOptions(clientKey = "kirora_pk_test", trackSessions = true)
        runBlocking {
            val first = start(sessions)
            first.identify(jwt("user-a"))
            first.track("A1")
            first.flush()
            first.destroy()
        }
        heldClient(sessions) { kirora, release ->
            kirora.track("Before sign-in")
            runBlocking {
                val signIn = launch { kirora.identify(jwt("user-b")) }
                waitUntil(message = "B signed in") { kirora.isIdentified }
                release()
                signIn.join()
                kirora.flush()
                waitUntil(message = "sent") { "Before sign-in" in fake.sentEventNames }
            }
            val context = fake.sentEvent("Before sign-in").context.asJsonObject
            assertTrue("B's events never join A's visit, nor does A's event join B's: $context", !context.has("sessionId"))
        }
    }

    private fun fakeProcess(): androidx.lifecycle.LifecycleRegistry {
        val owner = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        Kirora.processLifecycle = { owner.registry }
        return owner.registry
    }

    @Test
    fun anEarlyEventSurvivesTheClientBeingDestroyedBeforeItIsCaptured() {
        val options = KiroraOptions(clientKey = "kirora_pk_destroyed_early", trackSessions = false)
        heldClient(options) { kirora, release ->
            kirora.track("Before destroy")
            kirora.destroy()
            release()
        }
        runBlocking {
            val next = start(options, clearQueue = false)
            next.identify(jwt("user-a"))
            next.flush()
            waitUntil(message = "the event, sent by the next client") { "Before destroy" in fake.sentEventNames }
        }
    }

    @Test
    fun theVisitAnEarlyEventBeginsKeepsItsSessionStartedWhenTheClientIsDestroyedFirst() {
        val options = KiroraOptions(clientKey = "kirora_pk_destroyed_visit", trackSessions = true)
        heldClient(options) { kirora, release ->
            kirora.track("Before destroy")
            kirora.destroy()
            release()
        }
        runBlocking {
            val next = start(options, clearQueue = false)
            next.identify(jwt("user-a"))
            next.flush()
            waitUntil(message = "the event, sent by the next client") { "Before destroy" in fake.sentEventNames }
        }
        assertEquals(listOf("Session Started", "Before destroy"), fake.sentEventNames.take(2))
    }

    @Test
    fun aLaunchHeldForStartUpIsReportedOnceTheSignInThatFollowedItRegisters() {
        val process = fakeProcess()
        heldClient(KiroraOptions(clientKey = "kirora_pk_launch_open", trackSessions = false, push = KiroraPushOptions())) { kirora, release ->
            shadowOf(Looper.getMainLooper()).idle() // the observer is added
            process.handleLifecycleEvent(Lifecycle.Event.ON_START) // the launch, nobody signed in
            runBlocking {
                val signIn = launch { kirora.identify(jwt("user-a")) }
                waitUntil(message = "signed in") { kirora.isIdentified }
                release()
                signIn.join()
                waitUntil(message = "the launch reported") { fake.appOpens.size == 1 }
            }
        }
    }

    @Test
    fun aSignOutsVisitResetIsntUndoneByAnEarlierEventCapturedAfterIt() {
        val sessions = KiroraOptions(clientKey = "kirora_pk_test", trackSessions = true)
        runBlocking {
            val first = start(sessions)
            first.identify(jwt("user-a"))
        }
        // The replacement keeps A signed in.
        heldClient(sessions) { kirora, release ->
            kirora.track("Early, as A")
            runBlocking {
                val signOut = launch { kirora.logout() }
                delay(200)
                kirora.track("After sign-out")
                release()
                signOut.join()
                kirora.identify(jwt("user-b"))
                kirora.flush()
                waitUntil(message = "sent") { "After sign-out" in fake.sentEventNames }
            }
            val context = fake.sentEvent("After sign-out").context.asJsonObject
            assertTrue("a visit begun by nobody after the sign-out is B's to keep: $context", context.has("sessionId"))
        }
    }

    @Test
    fun aRetryAfterOutlivesARestart() = runBlocking {
        var now = System.currentTimeMillis()
        val first = start(clock = { now })
        first.identify(jwt("user-a"))
        fake.batchResponder = { _, _ -> FakeKiroraApiService.error(429, "RATE_LIMITED", retryAfterSeconds = 3_600) }
        first.track("Held")
        first.waitQueued(1)
        first.flush()
        assertEquals(1, fake.batchRequests.size)
        first.destroy()

        fake.batchResponder = null
        val second = start(clearQueue = false, clock = { now })
        second.identify(jwt("user-a"))
        second.flush()
        delay(100)
        assertEquals("nothing sent within the server's hour", 1, fake.batchRequests.size)

        now += 2 * 3_600_000L // past the hour and any jitter added to it
        second.flush()
        second.waitQueued(0)
        assertEquals(2, fake.batchRequests.size)
    }

    @Test
    fun aDeviceIdForgottenAsConsentIsWithdrawnIsNotMadeAgain() {
        val preferences = KiroraPreferences(context, "risks")
        preferences.getDeviceId()
        preferences.forgetDeviceId()
        assertNull(preferences.getDeviceId(mayCreate = { false }))
        assertNull(preferences.peekDeviceId())
    }

    @Test
    fun startUpsCleanUpStandsDownOnceConsentIsGranted() {
        val preferences = KiroraPreferences(context, "risks-grant")
        val id = preferences.getDeviceId()
        preferences.forgetDeviceId(onlyIf = { false })
        assertEquals(id, preferences.peekDeviceId())
    }

    @Test
    fun aTapStillReachesTheAppWhenItsProcessDiedMidWait() {
        val taps = mutableListOf<Map<String, String>>()
        val kirora = start(KiroraOptions(clientKey = "kirora_pk_test", trackSessions = false, push = KiroraPushOptions()))
        kirora.addNotificationClickListener { taps += it }
        // What Android gives the tap screen it recreates in a new process: the dead one's saved state.
        val saved = Bundle().apply { putString("kirora_tap_process", "from-a-process-that-died") }
        Robolectric.buildActivity(KiroraPushTrampolineActivity::class.java, context.tapIntent("title" to "Sale")).create(saved)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, taps.size)
        assertEquals("Sale", taps.single()["title"])
    }

    @Test
    fun aTapRecoveredAfterAProcessDeathGoesThroughTheWrappersLinkHandler() {
        var asked: String? = null
        start(KiroraOptions(clientKey = "kirora_pk_test", trackSessions = false, push = KiroraPushOptions()))
        Kirora.setWrapperCallbacks(handleDeepLink = { url -> asked = url; true }, onForegroundNotification = null)
        val saved = Bundle().apply { putString("kirora_tap_process", "another-process-that-died") }
        Robolectric.buildActivity(KiroraPushTrampolineActivity::class.java, context.tapIntent("link" to "https://shop.example/sale")).create(saved)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("https://shop.example/sale", asked)
    }

    @Test
    fun aTapRecoveredAfterAProcessDeathIsReportedAgain() = runBlocking {
        start(KiroraOptions(clientKey = "kirora_pk_test", trackSessions = false, push = KiroraPushOptions()))
        val saved = Bundle().apply { putString("kirora_tap_process", "a-third-process-that-died") }
        Robolectric.buildActivity(
            KiroraPushTrampolineActivity::class.java,
            context.tapIntent("title" to "Sale", "trackingUrl" to "https://api.kirora.io/t/p/tap-token")
        ).create(saved)
        // The first process may never have sent it; Kirora counts only the first report.
        waitUntilOnMain(message = "the report") { fake.trackingReports.isNotEmpty() }
        assertEquals("opened", fake.trackingReports.single().second.event)
    }

    @Test
    fun aTapScreenTheSystemEndsAndRecreatesInTheSameProcessHandsTheTapOverOnce() = runBlocking {
        // As with "Don't keep activities": ended alone, not for a configuration change, then recreated.
        val taps = mutableListOf<Map<String, String>>()
        val kirora = start(KiroraOptions(clientKey = "kirora_pk_test", trackSessions = false, push = KiroraPushOptions()))
        kirora.addNotificationClickListener { taps += it }
        fake.trackingResponder = { kotlinx.coroutines.CompletableDeferred<retrofit2.Response<Unit>>().await() } // never answers
        val first = Robolectric.buildActivity(
            KiroraPushTrampolineActivity::class.java,
            context.tapIntent("title" to "Sale", "trackingUrl" to "https://api.kirora.io/t/p/tap-token")
        ).create().start().resume()
        val saved = Bundle()
        first.pause().stop().saveInstanceState(saved).destroy() // hands the tap over as it goes
        Robolectric.buildActivity(KiroraPushTrampolineActivity::class.java, context.tapIntent("title" to "Sale")).create(saved)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
        assertEquals(1, taps.size)
    }

    @Test
    fun aRotationDuringTheWaitStillHandsTheTapOverOnce() = runBlocking {
        val taps = mutableListOf<Map<String, String>>()
        val kirora = start(KiroraOptions(clientKey = "kirora_pk_test", trackSessions = false, push = KiroraPushOptions()))
        kirora.addNotificationClickListener { taps += it }
        fake.trackingResponder = { kotlinx.coroutines.CompletableDeferred<retrofit2.Response<Unit>>().await() } // never answers
        val screen = Robolectric.buildActivity(
            KiroraPushTrampolineActivity::class.java,
            context.tapIntent("title" to "Sale", "trackingUrl" to "https://api.kirora.io/t/p/tap-token")
        ).create().start().resume()
        screen.recreate()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
        assertEquals(1, taps.size)
    }

    @Test
    fun withoutAndroidXsStartupProviderTheAppsActivitiesAreFollowed() {
        val app = context as Application
        val counter = AppLifecycle.ActivityCounter(app)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Lifecycle.State.CREATED, counter.lifecycle.currentState)

        val screen = Robolectric.buildActivity(Activity::class.java).create().start()
        assertTrue(counter.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))

        screen.stop()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(AppLifecycle.STOP_DELAY_MS / 2))
        assertTrue("a rotation's brief stop isn't a trip to the background", counter.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(AppLifecycle.STOP_DELAY_MS))
        assertEquals(Lifecycle.State.CREATED, counter.lifecycle.currentState)
        app.unregisterActivityLifecycleCallbacks(counter)
    }
}
