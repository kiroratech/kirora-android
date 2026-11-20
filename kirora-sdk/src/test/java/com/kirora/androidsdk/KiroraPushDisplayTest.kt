package com.kirora.androidsdk

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.kirora.androidsdk.model.KiroraOptions
import com.kirora.androidsdk.model.KiroraPushOptions
import com.kirora.androidsdk.push.KiroraPushDisplay
import com.kirora.androidsdk.push.KiroraPushTracker
import com.kirora.androidsdk.push.KiroraPushTrampolineActivity
import com.kirora.androidsdk.push.PushDisplayConfig
import com.kirora.androidsdk.push.PushListeners
import com.kirora.androidsdk.push.parseButtonsJson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KiroraPushDisplayTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private val config = PushDisplayConfig()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Kirora.resetForTesting()
        shadowOf(manager).allNotifications.forEach { }
        manager.cancelAll()
    }

    @After
    fun tearDown() {
        KiroraPushDisplay.foregroundCheck = { false }
        Kirora.resetForTesting()
        manager.cancelAll()
    }

    private fun kirora(vararg pairs: Pair<String, String>) = mapOf("kirora" to "true") + pairs

    private fun handle(data: Map<String, String>, listeners: PushListeners = PushListeners()) =
        KiroraPushDisplay.handle(context, data, listeners, config, null)

    @Test
    fun aPushThatIsNotKiroraIsLeftToTheApp() {
        assertFalse(handle(mapOf("title" to "Not ours", "body" to "x")))
        assertEquals(0, shadowOf(manager).size())
    }

    @Test
    fun aKiroraPushIsDrawnWithTitleBodyAndTheDefaultChannel() {
        assertTrue(handle(kirora("title" to "Hello", "body" to "World", "custom" to "kept")))
        val n = shadowOf(manager).allNotifications.single()
        assertEquals("Hello", shadowOf(n).contentTitle)
        assertEquals("World", shadowOf(n).contentText)
        assertEquals("kirora_default", n.channelId)
        assertNotNull(manager.getNotificationChannel("kirora_default"))
        assertNotNull(n.contentIntent)
    }

    @Test
    fun anUnknownChannelFallsBackToTheDefaultOneAndAKnownOneIsUsed() {
        handle(kirora("title" to "A", "channelId" to "missing", "tag" to "a"))
        assertEquals("kirora_default", shadowOf(manager).getNotification("a", 0).channelId)

        manager.createNotificationChannel(android.app.NotificationChannel("promos", "Promos", NotificationManager.IMPORTANCE_LOW))
        handle(kirora("title" to "B", "channelId" to "promos", "tag" to "b"))
        assertEquals("promos", shadowOf(manager).getNotification("b", 0).channelId)
    }

    @Test
    fun aNewerPushWithTheSameTagReplacesTheOlderOne() {
        handle(kirora("title" to "First", "tag" to "order-1"))
        handle(kirora("title" to "Second", "tag" to "order-1"))
        handle(kirora("title" to "Other", "tag" to "order-2"))
        val all = shadowOf(manager).allNotifications
        assertEquals(2, all.size)
        assertEquals("Second", shadowOf(shadowOf(manager).getNotification("order-1", 0)).contentTitle)
    }

    @Test
    fun onlyHttpsButtonsAreShownAndNoMoreThanTwo() {
        val json = """[{"title":"A","url":"https://a.example"},{"title":"B","url":"http://b.example"},
            {"title":"C","url":"https://c.example"},{"title":"D","url":"https://d.example"}]"""
        assertEquals(listOf("A", "C", "D"), parseButtonsJson(json).map { it.title })
        handle(kirora("title" to "Buttons", "buttons" to json, "tag" to "t"))
        assertEquals(2, shadowOf(manager).getNotification("t", 0).actions.size)
    }

    @Test
    fun anImageThatIsNotHttpsIsNotFetchedAndThePushStillShows() {
        assertTrue(handle(kirora("title" to "Pic", "image" to "http://insecure.example/x.png")))
        assertEquals(1, shadowOf(manager).size())
    }

    @Test
    fun aForegroundHookCanHoldAPushBackButOnlyWhileTheAppIsOpen() {
        val asked = mutableListOf<Map<String, String>>()
        val hold: (Map<String, String>) -> Boolean = { asked += it; false }
        KiroraPushDisplay.foregroundCheck = { true }
        assertTrue(KiroraPushDisplay.handle(context, kirora("title" to "Held"), PushListeners(), config, hold))
        assertEquals(1, asked.size)
        assertEquals(0, shadowOf(manager).size())

        // Not open: the hook is not asked and the push shows.
        KiroraPushDisplay.foregroundCheck = { false }
        KiroraPushDisplay.handle(context, kirora("title" to "Shown"), PushListeners(), config, hold)
        assertEquals(1, asked.size)
        assertEquals(1, shadowOf(manager).size())
    }

    @Test
    fun aPushIsShownByDefaultInTheForegroundAndWhenTheHookFails() {
        KiroraPushDisplay.foregroundCheck = { true }
        KiroraPushDisplay.handle(context, kirora("title" to "Default"), PushListeners(), config, null)
        KiroraPushDisplay.handle(context, kirora("title" to "Broken", "tag" to "b"), PushListeners(), config) {
            error("hook broke")
        }
        assertEquals(2, shadowOf(manager).size())
    }

    @Test
    fun theStaticForegroundHandlerAppliesWithoutAnInstance() {
        Kirora.setForegroundNotificationHandler { false }
        KiroraPushDisplay.foregroundCheck = { true }
        assertTrue(Kirora.handlePushMessage(context, kirora("title" to "Held")))
        assertEquals(0, shadowOf(manager).size())
    }

    @Test
    fun listenersHearAboutAKiroraPush() {
        val listeners = PushListeners()
        val heard = mutableListOf<Map<String, String>>()
        listeners.foreground.add { heard += it }
        handle(kirora("title" to "Hi", "plan" to "pro"), listeners)
        assertEquals("pro", heard.single()["plan"])
    }

    @Test
    fun theStaticEntryDrawsAKiroraPushWithoutAnInstanceAndUsesTheSavedLook() {
        // No Kirora instance exists: a push that arrives on a cold start.
        assertNull(Kirora.active)
        assertTrue(Kirora.handlePushMessage(context, kirora("title" to "Cold")))
        assertEquals(1, shadowOf(manager).size())
        assertFalse(Kirora.handlePushMessage(context, mapOf("other" to "1")))
    }

    @Test
    fun settingUpPushCreatesTheDefaultChannelAndKeepsTheLookForLater() {
        val k = Kirora(context, KiroraOptions(clientKey = "kirora_pk_x", trackSessions = false, push = KiroraPushOptions(
            defaultChannelId = "mine", defaultChannelName = "Mine")))
        assertTrue(k.isInitialized)
        kotlinx.coroutines.runBlocking { k.awaitReady() }
        assertNotNull(manager.getNotificationChannel("mine"))
        Kirora.resetForTesting()
        Kirora.handlePushMessage(context, kirora("title" to "Cold"))
        assertEquals("mine", shadowOf(manager).allNotifications.single().channelId)
    }

    @Test
    fun theForegroundHandlerIsKeptForTheProcessAndClearedByReset() {
        Kirora.setForegroundNotificationHandler { false }
        assertNotNull(Kirora.foregroundHandler)
        Kirora.resetForTesting()
        assertNull(Kirora.foregroundHandler)
    }

    @Test
    fun theTapActivityCancelsTheNotificationAndHandsTheTapToListenersWithoutInternalExtras() {
        val taps = mutableListOf<Map<String, String>>()
        val k = Kirora(context, KiroraOptions(clientKey = "kirora_pk_x", trackSessions = false, push = KiroraPushOptions()))
        k.addNotificationClickListener { taps += it }
        val intent = Intent(context, KiroraPushTrampolineActivity::class.java)
            .putExtra("kirora", "true")
            .putExtra("plan", "pro")
            .putExtra(KiroraPushTrampolineActivity.EXTRA_BUTTON_INDEX, 0)
            .putExtra(KiroraPushTrampolineActivity.EXTRA_BUTTON_URL, "https://shop.example/sale")
            .putExtra(KiroraPushTrampolineActivity.EXTRA_NOTIFICATION_ID, 0)
            .putExtra(KiroraPushTrampolineActivity.EXTRA_NOTIFICATION_TAG, "t")
        handle(kirora("title" to "x", "tag" to "t"))
        assertEquals(1, shadowOf(manager).size())

        Robolectric.buildActivity(KiroraPushTrampolineActivity::class.java, intent).create()

        assertEquals(0, shadowOf(manager).size())
        assertEquals(mapOf("kirora" to "true", "plan" to "pro"), taps.single())
        val started = shadowOf(context as Application).nextStartedActivity
        assertEquals("https://shop.example/sale", started.data.toString())
    }

    private fun tapWithLink(options: KiroraOptions, link: String): Intent? {
        Kirora(context, options)
        val intent = Intent(context, KiroraPushTrampolineActivity::class.java)
            .putExtra("kirora", "true")
            .putExtra("link", link)
            .putExtra(KiroraPushTrampolineActivity.EXTRA_NOTIFICATION_ID, 0)
        Robolectric.buildActivity(KiroraPushTrampolineActivity::class.java, intent).create()
        return shadowOf(context as Application).nextStartedActivity
    }

    @Test
    fun theAppCanTakeOverALinkWithHandleDeepLinkAndKiroraThenOpensNothing() {
        var routed: String? = null
        val started = tapWithLink(
            KiroraOptions(
                clientKey = "kirora_pk_x",
                trackSessions = false,
                push = KiroraPushOptions(),
                handleDeepLink = { url -> routed = url; true }
            ),
            "https://shop.example/sale"
        )
        assertEquals("https://shop.example/sale", routed)
        assertNull(started)
    }

    @Test
    fun aLinkOutsideTheAllowedSchemesOpensTheAppInstead() {
        val started = tapWithLink(
            KiroraOptions(clientKey = "kirora_pk_x", trackSessions = false, push = KiroraPushOptions(allowedLinkSchemes = setOf("myapp"))),
            "https://shop.example/sale"
        )
        assertNull(started?.data)
    }

    // ---- Push at launch (wrappers) ----------------------------------------------------------

    private fun launchTap(link: String) {
        Kirora.installPushIntegration(context)
        Kirora(context, KiroraOptions(clientKey = "kirora_pk_x", trackSessions = false, push = KiroraPushOptions()))
        val intent = Intent(context, KiroraPushTrampolineActivity::class.java)
            .putExtra("kirora", "true")
            .putExtra("link", link)
            .putExtra(KiroraPushTrampolineActivity.EXTRA_NOTIFICATION_ID, 0)
        Robolectric.buildActivity(KiroraPushTrampolineActivity::class.java, intent).create()
    }

    /** The first started activity with a link, skipping the app's own launch. */
    private fun startedLink(): String? {
        val app = shadowOf(context as Application)
        while (true) {
            val next = app.nextStartedActivity ?: return null
            next.data?.let { return it.toString() }
        }
    }

    @Test
    fun aLaunchTapsLinkWaitsForTheWrappersHandler() {
        launchTap("myapp://order/1")
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("nothing opened before the wrapper is ready", startedLink())

        var routed: String? = null
        Kirora.setWrapperCallbacks(handleDeepLink = { url -> routed = url; true }, onForegroundNotification = null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("myapp://order/1", routed)
        assertNull(startedLink())
    }

    /** The app's screens, as ProcessLifecycleOwner reports them (Robolectric doesn't drive it). */
    private val screens = object : androidx.lifecycle.LifecycleOwner {
        val registry = androidx.lifecycle.LifecycleRegistry.createUnsafe(this)
        override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
    }

    /** The app on screen, as after the launch the tap started. */
    private fun appInForeground() {
        Kirora.processLifecycle = { screens.registry }
        screens.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
    }

    @Test
    fun aLaunchTapsLinkOpensWhenTheWrapperSetsNoHandler() {
        appInForeground()
        launchTap("https://shop.example/sale")
        Kirora.setWrapperCallbacks(handleDeepLink = null, onForegroundNotification = null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("https://shop.example/sale", startedLink())
    }

    @Test
    fun aLaunchTapsLinkOpensAfterTenSecondsWithoutTheWrapper() {
        appInForeground()
        launchTap("https://shop.example/sale")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        assertNull(startedLink())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals("https://shop.example/sale", startedLink())
    }

    @Test
    fun withoutAWrapperALinkOpensAtOnce() {
        val started = tapWithLink(KiroraOptions(clientKey = "kirora_pk_x", trackSessions = false, push = KiroraPushOptions()), "https://shop.example/sale")
        assertEquals("https://shop.example/sale", started?.data.toString())
    }

    @Test
    fun aLinkReadyWhileTheAppIsInTheBackgroundOpensWhenItReturns() {
        appInForeground()
        launchTap("https://shop.example/sale")
        screens.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED
        Kirora.setWrapperCallbacks(handleDeepLink = null, onForegroundNotification = null)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("not started from the background", startedLink())
        screens.registry.currentState = androidx.lifecycle.Lifecycle.State.STARTED
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("https://shop.example/sale", startedLink())
    }

    @Test
    fun afterTheWrappersEngineIsGoneATapWaitsForTheNextOne() {
        appInForeground()
        Kirora.installPushIntegration(context)
        Kirora.setWrapperCallbacks(handleDeepLink = { true }, onForegroundNotification = null)
        Kirora.wrapperDetached()
        launchTap("myapp://order/2")
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("waits for the next engine", startedLink())
        var routed: String? = null
        Kirora.setWrapperCallbacks(handleDeepLink = { url -> routed = url; true }, onForegroundNotification = null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("myapp://order/2", routed)
    }

    @Test
    fun aLongBodyWithoutAnImageExpands() {
        val body = "word ".repeat(80).trim()
        handle(kirora("title" to "T", "body" to body, "tag" to "long"))
        val notification = shadowOf(manager).getNotification("long", 0)
        assertEquals(body, notification.extras.getCharSequence(androidx.core.app.NotificationCompat.EXTRA_BIG_TEXT).toString())
    }

    @Test
    fun aSoundTheAppDoesNotHaveKeepsTheDefault() {
        assertNull(KiroraPushDisplay.soundUri(context, null))
        assertNull(KiroraPushDisplay.soundUri(context, "default"))
        assertNull(KiroraPushDisplay.soundUri(context, "no_such_sound.mp3"))
    }
}
