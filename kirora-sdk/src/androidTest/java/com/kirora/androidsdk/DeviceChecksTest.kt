package com.kirora.androidsdk

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kirora.androidsdk.model.KiroraOptions
import com.kirora.androidsdk.model.KiroraPushOptions
import com.kirora.androidsdk.push.KiroraPushDisplay
import com.kirora.androidsdk.push.KiroraPushTrampolineActivity
import com.kirora.androidsdk.push.PushDisplayConfig
import com.kirora.androidsdk.utils.GlobalPreferences
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Collections
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks on a real device or emulator of what Robolectric can't show: notifications as the system
 * draws them, real network redirects, and an activity really destroyed mid-wait.
 */
@RunWith(AndroidJUnit4::class)
class DeviceChecksTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var silent: ServerSocket? = null
    private val held = Collections.synchronizedList(mutableListOf<Socket>())

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        Kirora.resetForTesting()
        manager.cancelAll()
    }

    @After
    fun tearDown() {
        Kirora.resetForTesting()
        manager.cancelAll()
        silent?.close()
        held.forEach { runCatching { it.close() } }
    }

    /** A local https address that accepts connections and never answers, so a report hangs. */
    private fun silentApiUrl(): String {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        silent = server
        Thread {
            while (!server.isClosed) {
                try {
                    held.add(server.accept())
                } catch (_: Exception) {
                    return@Thread
                }
            }
        }.start()
        return "https://127.0.0.1:${server.localPort}"
    }

    /** Checks [value] every 20 ms until it is non-null, failing with [message] after [timeoutMs]. */
    private fun <T : Any> poll(timeoutMs: Long, message: String, value: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            value()?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError(message)
    }

    private fun posted(tag: String): Notification =
        poll(5_000, "notification '$tag' was not posted") {
            manager.activeNotifications.firstOrNull { it.tag == tag }?.notification
        }

    /** [url]'s status (following redirects only if [follow]), or null when it can't be reached. */
    private fun status(url: String, follow: Boolean = false): Int? = try {
        (URL(url).openConnection() as HttpURLConnection).run {
            instanceFollowRedirects = follow
            connectTimeout = 5_000
            readTimeout = 5_000
            responseCode.also { disconnect() }
        }
    } catch (_: Exception) {
        null
    }

    private fun render(vararg pairs: Pair<String, String>) =
        KiroraPushDisplay.render(context, mapOf("kirora" to "true") + pairs, PushDisplayConfig())

    @Test
    fun aLongBodyIsDrawnExpandable() {
        val body = "This push has a long body. ".repeat(15).trim()
        render("title" to "Long", "body" to body, "tag" to "long")
        val n = posted("long")
        assertEquals("android.app.Notification\$BigTextStyle", n.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals(body, n.extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT).toString())
    }

    @Test
    fun anImageBehindAnHttpsRedirectIsShown() {
        val url = "https://picsum.photos/id/237/300/200"
        // The test is only meaningful if the address really redirects (and the device is online).
        val first = status(url)
        assumeTrue("no network to the image host", first != null)
        assertTrue("expected a redirect, got $first", first in 301..308)
        // Android 7.0 lacks the root certificate (ISRG Root X1) the image host uses: the device
        // itself refuses that connection, and the push is rightly shown without the picture.
        assumeTrue("this device doesn't trust the image host's certificate", status(url, follow = true) == 200)

        render("title" to "Picture", "body" to "short", "image" to url, "tag" to "pic")
        val n = posted("pic")
        assertEquals("android.app.Notification\$BigPictureStyle", n.extras.getString(Notification.EXTRA_TEMPLATE))
        val picture = n.extras.get(Notification.EXTRA_PICTURE) ?: n.extras.get(Notification.EXTRA_PICTURE_ICON)
        assertNotNull("the picture is attached", picture)
    }

    @Test
    fun aRedirectToPlainHttpIsNotFollowed() {
        assumeTrue("no network to the redirect host", status("https://httpbin.org/status/204") != null)
        render("title" to "Picture", "body" to "short", "image" to "https://httpbin.org/redirect-to?url=http%3A%2F%2Fhttpbin.org%2Fimage%2Fpng", "tag" to "insecure")
        val n = posted("insecure")
        // Shown, without the picture: the long body style, not the picture one.
        assertTrue(n.extras.getString(Notification.EXTRA_TEMPLATE) != "android.app.Notification\$BigPictureStyle")
    }

    @Test
    fun theAppsOwnSoundIsFoundByName() {
        val uri = KiroraPushDisplay.soundUri(context, "kirora_test_chime.wav")
        assertNotNull(uri)
        assertNull(KiroraPushDisplay.soundUri(context, "default"))
        render("title" to "Chime", "body" to "x", "sound" to "kirora_test_chime", "tag" to "sound")
        val n = posted("sound")
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            assertEquals(uri, n.sound)
        } else {
            // From Android 8 the channel's sound applies; the push's own is not set.
            assertNull(n.sound)
        }
    }

    @Test
    fun theSavedIconIsFoundByNameSoAnotherBuildsNumberingDoesntMatter() {
        val prefs = GlobalPreferences(context)
        val icon = context.resources.getIdentifier("kirora_test_icon", "drawable", context.packageName)
        assertTrue(icon != 0)
        prefs.savePushDisplay(icon, null, "c", "C")
        assertEquals(icon, prefs.getPushDisplay().smallIconResId)
    }

    /** Each link a tap opened, with when (ms since the tap screen was created, [tapAt]). */
    private val routed = Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
    @Volatile private var tapAt = 0L

    /**
     * A client whose `handleDeepLink` records the links taps open, reporting to an address that
     * never answers, so a tap waits its full 1.5 seconds; and the tap screen's intent for [link].
     */
    private fun tapOnSilentApi(link: String): Pair<Kirora, Intent> {
        val api = silentApiUrl()
        val kirora = Kirora(context, KiroraOptions(
            clientKey = "kirora_pk_device_test",
            apiUrl = api,
            trackSessions = false,
            push = KiroraPushOptions(),
            handleDeepLink = { routed.add(it to System.currentTimeMillis() - tapAt); true }
        ))
        val intent = Intent(context, KiroraPushTrampolineActivity::class.java)
            .putExtra("kirora", "true")
            .putExtra("link", link)
            .putExtra("trackingUrl", "$api/t/p/${link.hashCode()}")
            .putExtra(KiroraPushTrampolineActivity.EXTRA_NOTIFICATION_ID, 0)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return kirora to intent
    }

    /** Taps: starts the tap screen as the notification would, and returns it once created. */
    private fun tap(intent: Intent): Activity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = context as Application
        // The wait starts when the screen is created, so the clock does too: a loaded emulator can
        // take seconds to get there, and the monitor below answers some time after.
        val created = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is KiroraPushTrampolineActivity) tapAt = System.currentTimeMillis()
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        app.registerActivityLifecycleCallbacks(created)
        val monitor = instrumentation.addMonitor(KiroraPushTrampolineActivity::class.java.name, null, false)
        return try {
            context.startActivity(intent)
            instrumentation.waitForMonitorWithTimeout(monitor, 20_000)
        } finally {
            instrumentation.removeMonitor(monitor)
            app.unregisterActivityLifecycleCallbacks(created)
        } ?: throw AssertionError("the tap screen did not start")
    }

    private fun awaitRouted(timeoutMs: Long) {
        poll(timeoutMs, "no link was opened") { routed.firstOrNull() }
    }

    @Test
    fun aTapStillOpensItsLinkWhenTheTapScreenClosesDuringTheWait() {
        val taps = Collections.synchronizedList(mutableListOf<Map<String, String>>())
        val (kirora, intent) = tapOnSilentApi("myapp://order/1")
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            kirora.addNotificationClickListener { taps.add(it) }
        }
        val screen = tap(intent)
        Thread.sleep(300)
        assertTrue("still waiting for the report", routed.isEmpty())
        // As Back would: the screen finishes before the wait ends.
        val closedAt = System.currentTimeMillis() - tapAt
        InstrumentationRegistry.getInstrumentation().runOnMainSync { screen.finish() }
        awaitRouted(5_000)
        // Once, whichever path got there first: the screen's destruction (normally) or, on a
        // loaded device where destroying takes longer, the end of the wait. Robolectric's
        // PushConformanceTest pins the destroyed-first path deterministically.
        assertEquals("closed at $closedAt ms", listOf("myapp://order/1"), routed.map { it.first })
        assertEquals(1, taps.size)
        // Nothing is handed over a second time once the wait would have ended.
        Thread.sleep((tapAt + 2_000 - System.currentTimeMillis()).coerceAtLeast(0))
        assertEquals(1, routed.size)
        assertEquals(1, taps.size)
    }

    @Test
    fun aTapLeftAloneOpensItsLinkAfterTheWaitAtMost() {
        val (_, intent) = tapOnSilentApi("myapp://order/2")
        tap(intent)
        awaitRouted(10_000)
        val (link, at) = routed.single()
        assertEquals("myapp://order/2", link)
        // Waits for the report, but not for long: the report itself would take 30 seconds or
        // more (its read timeout). The upper bound leaves room for a debug build on a loaded
        // emulator, where verifying each method the first time it runs can take a second.
        assertTrue("opened after $at ms", at in Kirora.TAP_REPORT_WAIT_MS - 200..10_000)
    }

    @Test
    fun creatingTheClientReadsNothingFromDiskOnTheMainThread() {
        assertNoDiskAccessOnTheMainThread { key ->
            Kirora(context, KiroraOptions(clientKey = key, trackSessions = true, push = KiroraPushOptions()))
        }
    }

    @Test
    fun trackingStraightAfterCreatingTheClientReadsNothingFromDiskOnTheMainThread() {
        // Start-up held back until the events are tracked, so they come before its reads, every time.
        val startUp = java.util.concurrent.Executors.newSingleThreadExecutor()
        val release = java.util.concurrent.CountDownLatch(1)
        startUp.execute { release.await() }
        try {
            assertNoDiskAccessOnTheMainThread { key ->
                Kirora(context, KiroraOptions(clientKey = key, trackSessions = true), KiroraEnvironment(io = startUp.asCoroutineDispatcher())).apply {
                    track("Launched", mapOf("cold" to true))
                    trackScreenView("Home")
                }
            }
        } finally {
            release.countDown()
            startUp.shutdown()
        }
    }

    /** Runs [onMain] with a fresh workspace's key, failing on any SDK disk access on the main thread. */
    private fun assertNoDiskAccessOnTheMainThread(onMain: (key: String) -> Unit) {
        assumeTrue("StrictMode's listener needs Android 9", Build.VERSION.SDK_INT >= 28)
        // A client of a fresh workspace each run, so nothing is already loaded in memory.
        val key = "kirora_pk_strict_${System.nanoTime()}"
        val violations = Collections.synchronizedList(mutableListOf<String>())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var before: android.os.StrictMode.ThreadPolicy? = null
        instrumentation.runOnMainSync {
            before = android.os.StrictMode.getThreadPolicy()
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .penaltyListener({ it.run() }) { v ->
                        val trace = v.stackTrace.joinToString("\n") { it.toString() }
                        if ("com.kirora.androidsdk" in trace) violations.add(trace)
                    }
                    .build()
            )
            onMain(key)
        }
        try {
            // StrictMode reports a main-thread violation once the looper is idle, through the policy
            // then in place: kept until it has.
            instrumentation.waitForIdleSync()
            Thread.sleep(1_000)
            instrumentation.waitForIdleSync()
        } finally {
            instrumentation.runOnMainSync { android.os.StrictMode.setThreadPolicy(before) }
        }
        assertEquals("disk access on the main thread:\n" + violations.joinToString("\n----\n"), 0, violations.size)
    }
}
