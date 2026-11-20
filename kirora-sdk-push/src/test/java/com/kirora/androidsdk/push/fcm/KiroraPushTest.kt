package com.kirora.androidsdk.push.fcm

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The FCM entry points: a Kirora push is drawn, anything else goes to the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class KiroraPushTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun setUp() {
        KiroraPush.setFallbackMessageHandler(null)
        manager.cancelAll()
    }

    @After
    fun tearDown() {
        KiroraPush.setFallbackMessageHandler(null)
        manager.cancelAll()
    }

    private fun message(vararg data: Pair<String, String>): RemoteMessage =
        RemoteMessage.Builder("test@fcm").apply { data.forEach { (k, v) -> addData(k, v) } }.build()

    @Test
    fun aKiroraPushIsDrawnAndReturnsTrue() {
        assertTrue(KiroraPush.handleMessage(context, message("kirora" to "true", "title" to "Hi", "body" to "There")))
        val drawn = shadowOf(manager).allNotifications.single()
        assertEquals("Hi", shadowOf(drawn).contentTitle)
    }

    @Test
    fun aKiroraPushNeverReachesTheFallbackHandler() {
        var fallbackCalls = 0
        KiroraPush.setFallbackMessageHandler { fallbackCalls++ }
        KiroraPush.handleMessage(context, message("kirora" to "true", "title" to "Hi"))
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun anotherPushReturnsFalseAndGoesToTheFallbackHandlerUntouched() {
        var received: RemoteMessage? = null
        KiroraPush.setFallbackMessageHandler { received = it }
        val other = message("title" to "Not ours", "order" to "42")
        assertFalse(KiroraPush.handleMessage(context, other))
        assertEquals("42", received!!.data["order"])
        assertEquals(0, shadowOf(manager).size())
    }

    @Test
    fun withNoFallbackAnotherPushIsDroppedQuietly() {
        assertFalse(KiroraPush.handleMessage(context, message("title" to "Not ours")))
        assertEquals(0, shadowOf(manager).size())
    }

    @Test
    fun theFallbackCanBeRemoved() {
        var calls = 0
        KiroraPush.setFallbackMessageHandler { calls++ }
        KiroraPush.setFallbackMessageHandler(null)
        KiroraPush.handleMessage(context, message("title" to "x"))
        assertEquals(0, calls)
    }

    @Test
    fun aKiroraFlagThatIsNotExactlyTrueIsNotKiroras() {
        var calls = 0
        KiroraPush.setFallbackMessageHandler { calls++ }
        assertFalse(KiroraPush.handleMessage(context, message("kirora" to "false")))
        assertFalse(KiroraPush.handleMessage(context, message("kirora" to "TRUE")))
        assertEquals(2, calls)
    }

    @Test
    fun aNewTokenWithNoClientIsKeptForTheNextSignIn() {
        KiroraPush.onNewToken(context, "fcm-token-for-later")
        val stored = context.getSharedPreferences("kirora_prefs", Context.MODE_PRIVATE).all.values
        assertTrue(stored.contains("fcm-token-for-later"))
    }

    // ---- the default service and the manifest ---------------------------------------------------------

    @Test
    fun theDefaultServiceDrawsAKiroraPush() {
        val service = Robolectric.buildService(KiroraFirebaseService::class.java).create().get()
        service.onMessageReceived(message("kirora" to "true", "title" to "From FCM"))
        assertEquals("From FCM", shadowOf(shadowOf(manager).allNotifications.single()).contentTitle)
    }

    @Test
    fun theDefaultServiceHandsOtherPushesToTheFallback() {
        var received: RemoteMessage? = null
        KiroraPush.setFallbackMessageHandler { received = it }
        val service = Robolectric.buildService(KiroraFirebaseService::class.java).create().get()
        service.onMessageReceived(message("title" to "Other"))
        assertNotNull(received)
        assertEquals(0, shadowOf(manager).size())
    }

    @Test
    fun theDefaultServiceKeepsATokenItIsGiven() {
        val service = Robolectric.buildService(KiroraFirebaseService::class.java).create().get()
        service.onNewToken("fcm-from-service")
        val stored = context.getSharedPreferences("kirora_prefs", Context.MODE_PRIVATE).all.values
        assertTrue(stored.contains("fcm-from-service"))
    }

    @Test
    fun theServiceAnswersFirebaseMessagingEventsAndIsNotExported() {
        val pm = context.packageManager
        val info = pm.getServiceInfo(
            android.content.ComponentName(context, KiroraFirebaseService::class.java),
            PackageManager.GET_META_DATA
        )
        assertFalse(info.exported)
        val resolved = pm.queryIntentServices(Intent("com.google.firebase.MESSAGING_EVENT"), PackageManager.MATCH_ALL)
        assertEquals(1, resolved.count { it.serviceInfo.name == KiroraFirebaseService::class.java.name })
    }

    @Test
    fun thePermissionActivityIsNotExportedAndOutOfRecents() {
        val info = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, KiroraPermissionActivity::class.java), 0
        )
        assertFalse(info.exported)
        assertTrue(info.flags and android.content.pm.ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
    }

    @Test
    fun theManifestAsksForTheNotificationPermission() {
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue(requested.contains("android.permission.POST_NOTIFICATIONS"))
    }
}
