package com.kirora.androidsdk

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Looper
import com.kirora.androidsdk.model.KiroraOptions
import com.kirora.androidsdk.model.KiroraPushOptions
import com.kirora.androidsdk.model.LogLevel
import com.kirora.androidsdk.net.KiroraApiClient
import com.kirora.androidsdk.net.models.PushTrackingRequest
import com.kirora.androidsdk.push.KiroraPushDisplay
import com.kirora.androidsdk.push.KiroraPushTrampolineActivity
import com.kirora.androidsdk.push.PushDisplayConfig
import com.kirora.androidsdk.utils.GlobalPreferences
import com.kirora.androidsdk.utils.KiroraLogger
import com.kirora.androidsdk.utils.LogEntry
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
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

/** Regression tests for the push audit of 22 November. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class PushAuditTest : KiroraTestBase() {
    private val pushOptions = KiroraOptions(clientKey = "kirora_pk_test", trackSessions = false, push = KiroraPushOptions())
    @After
    fun cleanUp() {
        context.notifications.cancelAll()
    }

    private fun posted(): Notification = shadowOf(context.notifications).allNotifications.single()


    // ---- 1. The small icon on Android 8.0 -----------------------------------------------------

    @Test
    @Config(sdk = [26])
    fun onAndroid8AnAdaptiveSmallIconIsSkippedForTheNextOne() {
        val adaptive = android.R.drawable.sym_def_app_icon
        assertTrue("an adaptive icon", context.getDrawable(adaptive) is AdaptiveIconDrawable)
        val launcher = context.applicationInfo.icon
        try {
            // The app's own setting and its launcher icon are both adaptive.
            context.applicationInfo.icon = adaptive
            KiroraPushDisplay.render(context, mapOf("kirora" to "true", "title" to "Hi"), PushDisplayConfig(smallIconResId = adaptive))
            assertEquals(android.R.drawable.ic_dialog_info, posted().smallIcon.resId)
        } finally {
            context.applicationInfo.icon = launcher
        }
    }

    @Test
    @Config(sdk = [26])
    fun onAndroid8AnIconThatIsNotAdaptiveIsKept() {
        KiroraPushDisplay.render(
            context,
            mapOf("kirora" to "true", "title" to "Hi"),
            PushDisplayConfig(smallIconResId = android.R.drawable.star_on)
        )
        assertEquals(android.R.drawable.star_on, posted().smallIcon.resId)
    }

    // ---- 2. App screens opened with Kirora's extras ------------------------------------------

    @Test
    fun anAppScreenOpenedWithKirorasExtrasIsNotATap() = runBlocking {
        val taps = mutableListOf<Map<String, String>>()
        val kirora = start(pushOptions)
        kirora.addNotificationClickListener { taps += it }
        // Any app can start the launcher activity with these.
        val forged = Intent(context, Activity::class.java)
            .putExtra("kirora", "true")
            .putExtra("link", "https://evil.example")
            .putExtra("trackingUrl", "https://api.kirora.io/t/p/someone-elses")
        Robolectric.buildActivity(Activity::class.java, forged).create().start().resume()
        delay(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(taps.isEmpty())
        assertTrue(fake.trackingReports.isEmpty())
    }

    @Test
    fun anIntentReopenedFromRecentsIsNotReportedAgain() = runBlocking {
        val kirora = start(pushOptions)
        val reopened = Intent(context, Activity::class.java)
            .putExtra("kirora", "true")
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertFalse(kirora.handleNotificationIntent(reopened))
    }

    // ---- 4. The token Firebase has now ---------------------------------------------------------

    @Test
    fun aTokenFirebaseChangedWithoutTellingTheAppIsRegistered() = runBlocking {
        start(pushOptions).identify(jwt("user-a"))
        assertEquals("fcm-1", fake.deviceRegistrations.single().second.target)

        // Firebase rotated the token; its onNewToken never arrived.
        bridge.token = "fcm-2"
        start(pushOptions, clearQueue = false).identify(jwt("user-a"))
        assertEquals(2, fake.deviceRegistrations.size)
        assertEquals("fcm-2", fake.deviceRegistrations.last().second.target)
        assertEquals("fcm-2", GlobalPreferences(context).getPushToken())
    }

    @Test
    fun theKeptTokenIsUsedWhenFirebaseHasNoneToGive() = runBlocking {
        start(pushOptions).identify(jwt("user-a"))
        bridge.token = null
        start(pushOptions, clearQueue = false).identify(jwt("user-a"))
        assertEquals(1, fake.deviceRegistrations.size)
        assertEquals("fcm-1", GlobalPreferences(context).getPushToken())
    }

    // ---- 5. Links for other apps -----------------------------------------------------------------

    @Test
    fun aLinkForAnotherAppOpensWithTheSystem() = runBlocking {
        start(pushOptions)
        Robolectric.buildActivity(KiroraPushTrampolineActivity::class.java, context.tapIntent("link" to "tel:+15551234")).create()
        var link: String? = null
        waitUntilOnMain(message = "the link opened") { context.startedLink()?.also { link = it } != null }
        assertEquals("tel:+15551234", link)
    }

    @Test
    fun aLinkNothingOpensOpensTheAppInstead() = runBlocking {
        val taps = mutableListOf<Map<String, String>>()
        start(pushOptions).addNotificationClickListener { taps += it }
        shadowOf(context as Application).checkActivities(true)
        Robolectric.buildActivity(KiroraPushTrampolineActivity::class.java, context.tapIntent("link" to "market://details?id=x")).create()
        waitUntilOnMain(message = "the tap") { taps.isNotEmpty() }
        assertNull(context.startedLink())
    }

    @Test
    fun allowedLinkSchemesAndBlockedSchemesStillApplyToOtherApps() = runBlocking {
        start(KiroraOptions(clientKey = "kirora_pk_test", trackSessions = false, push = KiroraPushOptions(allowedLinkSchemes = setOf("https"))))
        assertFalse(KiroraPushTrampolineActivity.openAction(context, "tel:+15551234"))
        assertFalse(KiroraPushTrampolineActivity.openAction(context, "intent://x#Intent;end"))
        assertNull(context.startedLink())
    }

    // ---- 6. Push data named like the SDK's own extras ----------------------------------------

    @Test
    fun pushDataCannotPassForAButtonTap() = runBlocking {
        val taps = mutableListOf<Map<String, String>>()
        start(pushOptions).addNotificationClickListener { taps += it }
        KiroraPushDisplay.render(
            context,
            mapOf(
                "kirora" to "true",
                "title" to "Hi",
                "plan" to "pro",
                "trackingUrl" to "https://api.kirora.io/t/p/tap-token",
                KiroraPushTrampolineActivity.EXTRA_BUTTON_INDEX to "0",
                KiroraPushTrampolineActivity.EXTRA_BUTTON_URL to "http://evil.example"
            ),
            PushDisplayConfig()
        )
        val tap = shadowOf(posted().contentIntent).savedIntent
        assertFalse(tap.hasExtra(KiroraPushTrampolineActivity.EXTRA_BUTTON_INDEX))
        assertFalse(tap.hasExtra(KiroraPushTrampolineActivity.EXTRA_BUTTON_URL))

        Robolectric.buildActivity(KiroraPushTrampolineActivity::class.java, tap).create()
        waitUntilOnMain(message = "the tap") { taps.isNotEmpty() }
        assertEquals("pro", taps.single()["plan"])
        waitUntil(message = "the tap's report") { fake.trackingReports.any { it.second.event == "opened" } }
        assertFalse(fake.trackingReports.any { it.second.event == "clicked" })
        assertNull(context.startedLink())
    }

    // ---- 7. actionUrl for tap listeners --------------------------------------------------------

    @Test
    fun tapListenersHearThePushsLinkAsActionUrl() = runBlocking {
        val taps = mutableListOf<Map<String, String>>()
        start(pushOptions).addNotificationClickListener { taps += it }
        Robolectric.buildActivity(KiroraPushTrampolineActivity::class.java, context.tapIntent("link" to "https://shop.example/sale")).create()
        waitUntilOnMain(message = "the tap") { taps.isNotEmpty() }
        assertEquals("https://shop.example/sale", taps.single()["actionUrl"])
        assertEquals("https://shop.example/sale", taps.single()["link"])
    }

    // ---- 8. The request log ----------------------------------------------------------------------

    @Test
    fun theRequestLogLeavesOutDeviceIdsAndDeliveryTokens() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
            start()
        }
        val lines = CopyOnWriteArrayList<String>()
        val listener: (LogEntry) -> Unit = { lines += it.message }
        val level = KiroraLogger.logLevel
        KiroraLogger.logLevel = LogLevel.DEBUG
        KiroraLogger.addLogListener(listener)
        try {
            val base = "http://127.0.0.1:${server.address.port}/"
            val api = KiroraApiClient.create(base, { "kirora_pk_test" }, { null })
            api.deleteDevice("install-secret-id", null, null)
            api.reportPush("${base}t/p/signed-delivery-token", PushTrackingRequest("opened"))
            val http = lines.filter { it.startsWith("[HTTP]") }
            assertEquals(listOf("[HTTP] DELETE /client/v1/devices/… -> 204", "[HTTP] POST /t/p/… -> 204"), http)
        } finally {
            KiroraLogger.removeLogListener(listener)
            KiroraLogger.logLevel = level
            server.stop(0)
        }
    }

    @Test
    fun redactionKeepsTheRestOfThePath() {
        assertEquals("/api/client/v1/devices/…/app-opens", KiroraApiClient.redactedPath("/api/client/v1/devices/abc-123/app-opens"))
        assertEquals("/client/v1/events/batch", KiroraApiClient.redactedPath("/client/v1/events/batch"))
    }
}
