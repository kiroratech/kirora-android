package com.kirora.androidsdk

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.kirora.androidsdk.net.KiroraNetworkMonitor
import com.kirora.androidsdk.push.KiroraPushDisplay
import com.kirora.androidsdk.push.KiroraPushTrampolineActivity
import com.kirora.androidsdk.push.PushDisplayConfig
import com.kirora.androidsdk.push.PushListeners
import com.kirora.androidsdk.utils.GlobalPreferences
import java.io.File
import java.util.Collections
import kotlinx.coroutines.delay
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
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/** Regression tests for the production-readiness audit of 22 November. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class AuditFixesTest : KiroraTestBase() {

    // ---- coming back online ---------------------------------------------------------------

    private fun monitorReturns(onlineAtStart: Boolean): Int {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (onlineAtStart) {
            val internet = ShadowNetworkCapabilities.newInstance()
            shadowOf(internet).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, internet)
        } else {
            shadowOf(manager).setActiveNetworkInfo(null)
        }
        var returns = 0
        val monitor = KiroraNetworkMonitor(context) { returns++ }
        monitor.start()
        val callback = shadowOf(manager).networkCallbacks.single()
        // Registering reports each network already up: Wi-Fi and mobile data, say.
        val wifi = if (onlineAtStart) manager.activeNetwork!! else ShadowNetwork.newInstance(1)
        val cell = ShadowNetwork.newInstance(2)
        callback.onAvailable(wifi)
        callback.onAvailable(cell)
        // One going down while the other stays is no return either.
        callback.onLost(wifi)
        callback.onAvailable(wifi)
        // Both down, then one back: a return.
        callback.onLost(wifi)
        callback.onLost(cell)
        callback.onAvailable(cell)
        monitor.stop()
        return returns
    }

    @Test
    fun theFirstNetworkAfterAnOfflineStartIsAReturn() {
        assertEquals("offline at start: the first network is a return, then the later one", 2, monitorReturns(onlineAtStart = false))
    }

    @Test
    fun theNetworkUpAtStartIsNotAReturn() {
        assertEquals("only coming back after both were lost", 1, monitorReturns(onlineAtStart = true))
    }

    // ---- restored from a backup ------------------------------------------------------------

    @Test
    fun aRestoredPushTokenIsForgottenAndAFreshOneKept() {
        GlobalPreferences(context).setPushToken("other-phones-token")
        // A restore onto a new device: the file comes back, the no-backup marker doesn't.
        File(context.noBackupFilesDir, "kirora_install_global").delete()
        GlobalPreferences.resetForTesting()
        assertNull(GlobalPreferences(context).getPushToken())

        GlobalPreferences(context).setPushToken("this-phones-token")
        GlobalPreferences.resetForTesting()
        assertEquals("not a restore: kept", "this-phones-token", GlobalPreferences(context).getPushToken())
    }

    @Test
    fun anIconFromAnotherPackageIsFoundAgain() {
        GlobalPreferences(context).savePushDisplay(android.R.drawable.stat_sys_warning, null, "c", "C")
        assertEquals(android.R.drawable.stat_sys_warning, GlobalPreferences(context).getPushDisplay().smallIconResId)
    }

    @Test
    fun anIconThisAppDoesntHaveIsLeftToTheDefault() {
        GlobalPreferences(context).savePushDisplay(0x7f0affff, null, "c", "C")
        assertNull(GlobalPreferences(context).getPushDisplay().smallIconResId)
    }

    // ---- a channel the user turned off -----------------------------------------------------

    @Test
    fun aPushForAChannelTheUserTurnedOffIsNotShown() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("muted", "Muted", NotificationManager.IMPORTANCE_NONE))
        val data = mapOf("kirora" to "true", "title" to "Hi", "body" to "x", "channelId" to "muted")
        assertTrue("still Kirora's", KiroraPushDisplay.handle(context, data, PushListeners(), PushDisplayConfig(), null))
        assertEquals(0, shadowOf(manager).size())

        manager.createNotificationChannel(NotificationChannel("loud", "Loud", NotificationManager.IMPORTANCE_HIGH))
        KiroraPushDisplay.handle(context, data + ("channelId" to "loud"), PushListeners(), PushDisplayConfig(), null)
        assertEquals(1, shadowOf(manager).size())
    }

    // ---- a tap that opens the app -----------------------------------------------------------

    @Test
    fun aTapWithoutALinkBringsTheAppBackWithoutClosingItsScreens() {
        val launcher = ComponentName(context.packageName, "com.example.Main")
        val packages = shadowOf(context.packageManager)
        packages.addActivityIfNotPresent(launcher)
        packages.addIntentFilterForActivity(
            launcher,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        )
        val intent = Intent(context, KiroraPushTrampolineActivity::class.java)
            .putExtra("kirora", "true")
            .putExtra(KiroraPushTrampolineActivity.EXTRA_NOTIFICATION_ID, 0)
        Robolectric.buildActivity(KiroraPushTrampolineActivity::class.java, intent).create()
        val started = shadowOf(context as Application).nextStartedActivity
        assertEquals(launcher, started.component)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED != 0)
        assertEquals("the screens left open stay", 0, started.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    // ---- app callbacks that throw -----------------------------------------------------------

    @Test
    fun aCallbackThatThrowsNeverReachesTheApp() = runBlocking {
        val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.add(e) }
        try {
            val kirora = start()
            var called = 0
            kirora.flushAsync { called++; throw IllegalStateException("app bug") }
            kirora.setPushEnabledAsync(false) { called++; throw IllegalStateException("app bug") }
            kirora.getPushStatusAsync { called++; throw IllegalStateException("app bug") }
            waitUntil(message = "the three callbacks") { called == 3 }
            delay(200)
            assertEquals(emptyList<Throwable>(), uncaught.toList())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
