package com.kirora.androidsdk

import android.content.Intent
import android.os.Looper
import com.google.gson.Gson
import com.kirora.androidsdk.model.KiroraOptions
import com.kirora.androidsdk.model.KiroraPushOptions
import com.kirora.androidsdk.model.PushPermission
import com.kirora.androidsdk.model.PushStatus
import com.kirora.androidsdk.net.FakeKiroraApiService
import com.kirora.androidsdk.push.KiroraPushTracker
import com.kirora.androidsdk.push.KiroraPushTrampolineActivity
import com.kirora.androidsdk.utils.GlobalPreferences
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import retrofit2.Response

/** Push registration, the device's own push choice, app opens and `trackingUrl` reports ("Push", "Push reporting"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class PushConformanceTest : KiroraTestBase() {
    private val token = jwt("user-a")
    private val pushOptions = KiroraOptions(clientKey = "kirora_pk_test", trackSessions = false, push = KiroraPushOptions())

    @Test
    fun registrationPutsTheDeviceIdInThePathAndSendsTheDeviceDetails() = runBlocking {
        val kirora = start(pushOptions)
        kirora.identify(token)

        val (path, request) = fake.deviceRegistrations.single()
        assertEquals(kirora.deviceId, path)
        val body = Gson().toJsonTree(request).asJsonObject
        assertFalse(body.has("externalInstallationId"))
        assertEquals("granted", body["permission"].asString)
        assertEquals("fcm_token", body["targetKind"].asString)
        assertEquals("fcm-1", body["target"].asString)
        assertEquals(FakeKiroraApiService().pushApp!!.pushAppId, body["pushAppId"].asString)
        assertTrue(body.has("osVersion") && body.has("locale") && body.has("timezone"))
        assertEquals("Bearer $token", fake.registrationAuthorizations.single())
    }

    @Test
    fun aSignedInStartCostsNoPushCallsUntilTheUserTokenOrDayChanges() = runBlocking {
        start(pushOptions).identify(token)
        // A new process for the same user, token and details: nothing to tell Kirora.
        start(pushOptions, clearQueue = false).identify(token)
        assertEquals(1, fake.deviceRegistrations.size)
        assertEquals(1, fake.pushAppQueries.size)

        val kirora = Kirora.active!!
        bridge.token = "fcm-2" // Firebase's current token, as onNewToken reports it
        kirora.onNewPushToken("fcm-2")
        waitUntil(message = "registered with the new token") { fake.deviceRegistrations.size == 2 }
        assertEquals("fcm-2", fake.deviceRegistrations.last().second.target)

        kirora.identify(jwt("user-b"))
        assertEquals(3, fake.deviceRegistrations.size)
    }

    @Test
    fun anotherPushAppKindIsNotRegistered() = runBlocking {
        fake.pushApp = fake.pushApp!!.copy(targetKind = "apns_token")
        start(pushOptions).identify(token)
        assertTrue(fake.deviceRegistrations.isEmpty())
    }

    @Test
    fun pushStatusAndTheDevicesOwnChoice() = runBlocking {
        val kirora = start(pushOptions)
        assertEquals(PushStatus.DISABLED, kirora.getPushStatus()) // signed out
        kirora.identify(token)
        assertEquals(PushStatus.ENABLED, kirora.getPushStatus())

        kirora.setPushEnabled(false)
        assertEquals(kirora.deviceId to "Bearer $token", fake.deviceDeletions.single())
        assertEquals(PushStatus.DISABLED, kirora.getPushStatus())

        // The choice is the device's: it holds across sign-ins.
        kirora.logout()
        kirora.identify(token)
        assertEquals(1, fake.deviceRegistrations.size)

        kirora.setPushEnabled(true)
        assertEquals(2, fake.deviceRegistrations.size)
        assertEquals(PushStatus.ENABLED, kirora.getPushStatus())
    }

    @Test
    fun aGrantedPromptTurnsTheDevicesPushBackOn() = runBlocking {
        val kirora = start(pushOptions)
        kirora.identify(token)
        kirora.setPushEnabled(false)
        assertEquals(PushPermission.GRANTED, kirora.requestPushPermission(context))
        waitUntil(message = "registered again") { fake.deviceRegistrations.size == 2 }
    }

    @Test
    fun pushIsUnsupportedWhenOff() = runBlocking {
        val kirora = start()
        assertEquals(PushStatus.UNSUPPORTED, kirora.getPushStatus())
        assertEquals(PushPermission.UNSUPPORTED, kirora.requestPushPermission(context))
    }

    @Test
    fun anAppOpenGoesOnlyForARegisteredDeviceWithItsPushApp() = runBlocking {
        val withoutPush = start()
        withoutPush.identify(token)
        withoutPush.appOpens!!.onAppOpen()
        delay(100)
        assertTrue(fake.appOpens.isEmpty())

        val kirora = start(pushOptions)
        kirora.identify(token)
        kirora.appOpens!!.onAppOpen()
        waitUntil(message = "the app open") { fake.appOpens.isNotEmpty() }
        val (path, body) = fake.appOpens.single()
        assertEquals(kirora.deviceId, path)
        assertEquals(fake.pushApp!!.pushAppId, body.pushAppId)

        // At most one in 30 minutes.
        kirora.appOpens!!.onAppOpen()
        delay(100)
        assertEquals(1, fake.appOpens.size)
    }

    @Test
    fun aLaunchBeforeSignInIsReportedOnceTheDeviceIsRegistered() = runBlocking {
        val kirora = start(pushOptions)
        kirora.appOpens!!.onAppOpen()
        delay(50)
        assertTrue(fake.appOpens.isEmpty())
        kirora.identify(token)
        waitUntil(message = "the held app open") { fake.appOpens.size == 1 }
    }

    @Test
    fun anAppOpenRefusedWith404IsDroppedAndOneThatFailsWaits() = runBlocking {
        val kirora = start(pushOptions)
        kirora.identify(token)
        fake.appOpenResponder = { FakeKiroraApiService.error(503) }
        kirora.appOpens!!.onAppOpen()
        waitUntil(message = "the failed attempt") { fake.appOpens.size == 1 }
        delay(50)
        assertEquals(1, kirora.databaseForTesting!!.appOpenDao().count())

        fake.appOpenResponder = { FakeKiroraApiService.error(404, "NOT_FOUND") }
        kirora.appOpens!!.onNetworkAvailable()
        waitUntil(message = "the 404") { fake.appOpens.size == 2 }
        waitUntil(message = "dropped") { kirora.databaseForTesting!!.appOpenDao().count() == 0 }
    }

    @Test
    fun noAppOpenWhileTrackingIsRefused() = runBlocking {
        val kirora = start(pushOptions)
        kirora.identify(token)
        kirora.setTrackingConsent(false)
        kirora.appOpens!!.onAppOpen()
        delay(100)
        assertTrue(fake.appOpens.isEmpty())
    }

    @Test
    fun deliveredIsReportedOnceAndRetriedOnce() = runBlocking {
        val kirora = start(pushOptions)
        var calls = 0
        fake.trackingResponder = {
            calls++
            FakeKiroraApiService.error(503)
        }
        val url = "https://api.kirora.io/t/p/abc"
        KiroraPushTracker.report(context, kirora.options.apiUrl, url, KiroraPushTracker.DELIVERED, fake)
        KiroraPushTracker.report(context, kirora.options.apiUrl, url, KiroraPushTracker.DELIVERED, fake)
        waitUntil(message = "two attempts") { calls == 2 }
        delay(100)
        assertEquals(2, calls)
        assertEquals("delivered", fake.trackingReports.first().second.event)
    }

    @Test
    fun aRefusedReportIsNotRetriedAndAPushWithoutTrackingUrlReportsNothing() = runBlocking {
        val kirora = start(pushOptions)
        fake.trackingResponder = { FakeKiroraApiService.error(400) }
        KiroraPushTracker.report(context, kirora.options.apiUrl, "/t/p/xyz", KiroraPushTracker.OPENED, fake)
        KiroraPushTracker.report(context, kirora.options.apiUrl, null, KiroraPushTracker.OPENED, fake)
        delay(200)
        assertEquals(1, fake.trackingReports.size)
        assertEquals("opened", fake.trackingReports.single().second.event)
    }

    @Test
    fun onlyKirorasOwnTrackingHostIsCalled() {
        val base = "https://api.kirora.io".toHttpUrl()
        assertEquals("https://api.kirora.io/t/p/abc", KiroraPushTracker.trusted(base, "/t/p/abc").toString())
        assertNull(KiroraPushTracker.trusted(base, "https://evil.example.com/t/p/abc"))
        assertNull(KiroraPushTracker.trusted(base, "https://api.kirora.io/other/path"))
        assertNull(KiroraPushTracker.trusted(base, "https://user@api.kirora.io/t/p/abc"))
        assertNull(KiroraPushTracker.trusted(base, "https://api.kirora.io/x/../t/p/abc"))
        assertNull(KiroraPushTracker.trusted(base, "https://api.kirora.io/t/p/%2e%2e/admin"))
        assertNull(KiroraPushTracker.trusted(base, "https://api.kirora.io/t/p/"))
        assertNull(KiroraPushTracker.trusted(base, "http://api.kirora.io/t/p/abc"))
    }

    @Test
    fun aTrackingUrlMayLieUnderTheApiUrlsPath() {
        val base = "https://example.com/kirora/api/".toHttpUrl()
        assertEquals(
            "https://example.com/kirora/api/t/p/abc",
            KiroraPushTracker.trusted(base, "https://example.com/kirora/api/t/p/abc").toString()
        )
        assertNull("outside the API's path", KiroraPushTracker.trusted(base, "https://example.com/t/p/abc"))
        assertNull(KiroraPushTracker.trusted(base, "https://example.com/kirora/apix/t/p/abc"))
        assertNull(KiroraPushTracker.trusted(base, "https://example.com/kirora/api/../other/t/p/abc"))
    }

    @Test
    fun theFcmTokenComesFromTheBridgeAndIsKept() = runBlocking {
        start(pushOptions).identify(token)
        assertEquals("fcm-1", GlobalPreferences(context).getPushToken())
    }

    // ---- Taps reach the app after their report, waiting 1.5 seconds at most ------------------

    private fun tap(link: String) =
        Robolectric.buildActivity(KiroraPushTrampolineActivity::class.java, tapIntent(link)).create()

    private fun tapIntent(link: String): Intent =
        context.tapIntent("link" to link, "trackingUrl" to "https://api.kirora.io/t/p/tap-token")

    @Test
    fun aTapReachesTheAppAfterItsReport() = runBlocking {
        var routed: String? = null
        start(KiroraOptions(clientKey = "kirora_pk_test", trackSessions = false, push = KiroraPushOptions(), handleDeepLink = { routed = it; true }))
        val release = CompletableDeferred<Unit>()
        fake.trackingResponder = {
            release.await()
            Response.success(204, Unit)
        }
        tap("myapp://order/1")
        waitUntil(message = "the report on its way") { fake.trackingReports.isNotEmpty() }
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("waits for the report", routed)

        release.complete(Unit)
        waitUntilOnMain(message = "the link") { routed != null }
        assertEquals("clicked", fake.trackingReports.single().second.event)
    }

    @Test
    fun aTapWaitsAtMostOneAndAHalfSecondsForItsReport() = runBlocking {
        var routed: String? = null
        start(KiroraOptions(clientKey = "kirora_pk_test", trackSessions = false, push = KiroraPushOptions(), handleDeepLink = { routed = it; true }))
        fake.trackingResponder = { CompletableDeferred<Response<Unit>>().await() } // never answers
        tap("myapp://order/1")
        waitUntil(message = "the report on its way") { fake.trackingReports.isNotEmpty() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_400))
        assertNull(routed)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertEquals("myapp://order/1", routed)
    }

    @Test
    fun aTapStillReachesTheAppWhenTheTapScreenClosesDuringTheWait() = runBlocking {
        var routed: String? = null
        val taps = mutableListOf<Map<String, String>>()
        val kirora = start(KiroraOptions(clientKey = "kirora_pk_test", trackSessions = false, push = KiroraPushOptions(), handleDeepLink = { routed = it; true }))
        kirora.addNotificationClickListener { taps += it }
        fake.trackingResponder = { CompletableDeferred<Response<Unit>>().await() } // never answers
        val screen = tap("myapp://order/1")
        waitUntil(message = "the report on its way") { fake.trackingReports.isNotEmpty() }
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(routed)

        // Back, say, before the wait ends.
        screen.pause().stop().destroy()
        assertEquals("myapp://order/1", routed)
        assertEquals(1, taps.size)
        // The coroutine, cancelled, doesn't hand it over a second time.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
        assertEquals(1, taps.size)
    }

    @Test
    fun aTapThatReopenedTheAppStillReachesItWhenTheClientIsDestroyedDuringTheWait() = runBlocking {
        val taps = mutableListOf<Map<String, String>>()
        val kirora = start(pushOptions)
        kirora.addNotificationClickListener { taps += it }
        fake.trackingResponder = { CompletableDeferred<Response<Unit>>().await() } // never answers
        val intent = tapIntent("myapp://order/1")
        assertTrue(kirora.pushManager!!.handleNotificationIntent(intent))
        waitUntil(message = "the report on its way") { fake.trackingReports.isNotEmpty() }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(taps.isEmpty())

        kirora.destroy()
        waitUntilOnMain(message = "the tap") { taps.isNotEmpty() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
        assertEquals(1, taps.size)
    }

    @Test
    fun aTapIsDeliveredAndReportedOnceWhenTheTapScreenResumes() = runBlocking {
        val taps = mutableListOf<Map<String, String>>()
        val kirora = start(pushOptions)
        kirora.addNotificationClickListener { taps += it }
        // Resumed, as on a device: the activity callbacks see the tap screen too.
        Robolectric.buildActivity(KiroraPushTrampolineActivity::class.java, tapIntent("https://shop.example/sale"))
            .create().start().resume()
        waitUntilOnMain(message = "the tap") { taps.isNotEmpty() }
        delay(200)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, taps.size)
        assertEquals(listOf("clicked"), fake.trackingReports.map { it.second.event })
    }
}
