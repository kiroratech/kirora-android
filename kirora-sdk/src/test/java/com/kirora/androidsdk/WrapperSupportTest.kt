package com.kirora.androidsdk

import android.app.Application
import android.content.Intent
import android.os.Looper
import com.kirora.androidsdk.model.KiroraOptions
import com.kirora.androidsdk.model.KiroraPushOptions
import com.kirora.androidsdk.push.KiroraPushDisplay
import com.kirora.androidsdk.push.KiroraPushTrampolineActivity
import com.kirora.androidsdk.push.PushDisplayConfig
import com.kirora.androidsdk.push.PushListeners
import com.kirora.androidsdk.utils.SdkInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
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
import java.time.Duration
import kotlin.concurrent.thread

/** What the native SDK gives the React Native and Flutter wrappers ("Wrapper SDKs"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class WrapperSupportTest : KiroraTestBase() {

    @Test
    fun aWrapperNamesItselfInTheSdkHeader() {
        assertEquals("kirora-android/${SdkInfo.VERSION}", SdkInfo.HEADER)
        assertTrue(Kirora.setWrapperSdk("kirora-react-native", "1.2.0"))
        assertEquals("kirora-react-native/1.2.0", SdkInfo.HEADER)
        assertFalse(Kirora.setWrapperSdk("Kirora RN", "1.2.0"))
        assertEquals("kirora-react-native/1.2.0", SdkInfo.HEADER)
    }

    private fun tap(link: String): KiroraPushTrampolineActivity {
        start(KiroraOptions(clientKey = "kirora_pk_test", trackSessions = false, push = KiroraPushOptions()))
        val intent = Intent(context, KiroraPushTrampolineActivity::class.java)
            .putExtra("kirora", "true")
            .putExtra("link", link)
            .putExtra(KiroraPushTrampolineActivity.EXTRA_NOTIFICATION_ID, 0)
        return Robolectric.buildActivity(KiroraPushTrampolineActivity::class.java, intent).create().get()
    }

    @Test
    fun aWrapperThatTakesTheLinkIsAskedAndKiroraOpensNothing() {
        var asked: String? = null
        Kirora.setWrapperCallbacks(handleDeepLink = { url -> asked = url; true }, onForegroundNotification = null)
        val activity = tap("https://shop.example/sale")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("https://shop.example/sale", asked)
        assertNull(shadowOf(context as Application).nextStartedActivity)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun aWrapperThatDoesNotAnswerWithinOneAndAHalfSecondsGetsTheLinkOpened() {
        Kirora.setWrapperCallbacks(handleDeepLink = { awaitCancellation() }, onForegroundNotification = null)
        val activity = tap("https://shop.example/sale")
        assertFalse(activity.isFinishing)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_600))
        assertEquals("https://shop.example/sale", shadowOf(context as Application).nextStartedActivity?.data.toString())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun aWrappersForegroundHookIsAskedOffTheMainThreadAndShowsThePushWithoutAnAnswer() {
        KiroraPushDisplay.foregroundCheck = { true }
        try {
            val data = mapOf("kirora" to "true", "title" to "Hi")
            Kirora.setWrapperCallbacks(handleDeepLink = null, onForegroundNotification = { false })
            var drawn = true
            thread { drawn = KiroraPushDisplay.handle(context, data, PushListeners(), PushDisplayConfig(), null) }.join()
            assertTrue(drawn) // Kirora's push either way; the hook held it back
            val held = CompletableDeferred<Boolean>()
            Kirora.setWrapperCallbacks(handleDeepLink = null, onForegroundNotification = { held.await() })
            val started = System.currentTimeMillis()
            thread { KiroraPushDisplay.handle(context, data, PushListeners(), PushDisplayConfig(), null) }.join()
            val waited = System.currentTimeMillis() - started
            assertTrue("waited $waited ms", waited in 1_400..3_000)
        } finally {
            KiroraPushDisplay.foregroundCheck = { false }
        }
    }

    @Test
    fun callbackFormsServeABridge() = runBlocking {
        val kirora = start()
        val status = CompletableDeferred<com.kirora.androidsdk.model.PushStatus>()
        kirora.getPushStatusAsync { status.complete(it) }
        assertEquals(com.kirora.androidsdk.model.PushStatus.UNSUPPORTED, status.await())
        val flushed = CompletableDeferred<Unit>()
        kirora.flushAsync { flushed.complete(Unit) }
        flushed.await()
    }
}
