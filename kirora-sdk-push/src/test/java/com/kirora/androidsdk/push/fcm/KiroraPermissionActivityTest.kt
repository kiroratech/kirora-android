package com.kirora.androidsdk.push.fcm

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration

/** The notification prompt: one prompt shared by every caller, and an answer for each way it can end. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class KiroraPermissionActivityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val results = mutableListOf<Boolean?>()

    /** Asks, then opens the activity the request started. */
    private fun ask(onResult: (Boolean?) -> Unit = { results.add(it) }): ActivityController<KiroraPermissionActivity> {
        KiroraPermissionActivity.request(context, onResult)
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        return Robolectric.buildActivity(KiroraPermissionActivity::class.java, started).create()
    }

    private fun ActivityController<KiroraPermissionActivity>.answer(result: Int) {
        get().onRequestPermissionsResult(7301, arrayOf(Manifest.permission.POST_NOTIFICATIONS), intArrayOf(result))
    }

    @Test
    fun grantedIsReportedAndTheActivityCloses() {
        val controller = ask()
        assertEquals(listOf<Boolean?>(), results)
        controller.answer(PackageManager.PERMISSION_GRANTED)
        assertEquals(listOf<Boolean?>(true), results)
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun deniedIsReportedAsFalse() {
        val controller = ask()
        controller.answer(PackageManager.PERMISSION_DENIED)
        assertEquals(listOf<Boolean?>(false), results)
    }

    @Test
    fun anAnswerForAnotherRequestCodeIsIgnored() {
        val controller = ask()
        controller.get().onRequestPermissionsResult(1, arrayOf("x"), intArrayOf(PackageManager.PERMISSION_GRANTED))
        assertEquals(listOf<Boolean?>(), results)
        controller.answer(PackageManager.PERMISSION_GRANTED)
        assertEquals(listOf<Boolean?>(true), results)
    }

    @Test
    fun callersWhoAskWhileAPromptIsUpShareItsAnswerAndStartNoSecondActivity() {
        val controller = ask { results.add(it) }
        shadowOf(context as android.app.Application).clearNextStartedActivities() // the system's own prompt
        KiroraPermissionActivity.request(context) { results.add(it) }
        assertNull(shadowOf(context as android.app.Application).nextStartedActivity)
        controller.answer(PackageManager.PERMISSION_GRANTED)
        assertEquals(listOf<Boolean?>(true, true), results)
    }

    @Test
    fun aPromptAndroidNeverOpensIsAnsweredUnansweredAndDoesntBlockTheNext() {
        // From the background, Android 10+ refuses the start without a word: no activity appears.
        KiroraPermissionActivity.request(context) { results.add(it) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertEquals("still waiting for it to open", listOf<Boolean?>(), results)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(listOf<Boolean?>(null), results)

        shadowOf(context as android.app.Application).clearNextStartedActivities()
        ask().answer(PackageManager.PERMISSION_GRANTED)
        assertEquals("a later request shows its prompt", listOf<Boolean?>(null, true), results)
    }

    @Test
    fun aPromptThatOpensIsNotCutShortByTheOpeningTimeout() {
        val controller = ask()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(listOf<Boolean?>(), results)
        controller.answer(PackageManager.PERMISSION_GRANTED)
        assertEquals(listOf<Boolean?>(true), results)
    }

    @Test
    fun anEarlierPromptsTimeoutLeavesALaterPromptAlone() {
        ask().answer(PackageManager.PERMISSION_GRANTED)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        val later = ask()
        // The first prompt's 5 seconds end here, while the second is on screen.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertEquals(listOf<Boolean?>(true), results)
        later.answer(PackageManager.PERMISSION_GRANTED)
        assertEquals(listOf<Boolean?>(true, true), results)
    }

    @Test
    fun aPromptThatOpensTooLateNeitherAnswersNorBlocksTheNextOne() {
        KiroraPermissionActivity.request(context) { results.add(it) }
        val late = shadowOf(context as android.app.Application).nextStartedActivity
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals(listOf<Boolean?>(null), results)

        val next = ask()
        // The first launch finally opens: it bows out without prompting or answering anyone.
        val stale = Robolectric.buildActivity(KiroraPermissionActivity::class.java, late).create()
        assertTrue(stale.get().isFinishing)
        stale.destroy()
        assertEquals(listOf<Boolean?>(null), results)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertEquals("the next prompt isn't timed out either", listOf<Boolean?>(null), results)
        next.answer(PackageManager.PERMISSION_DENIED)
        assertEquals(listOf<Boolean?>(null, false), results)
    }

    @Test
    fun aPromptDismissedWithoutAnAnswerReportsNull() {
        val controller = ask()
        controller.get().finish()
        controller.destroy()
        assertEquals(listOf<Boolean?>(null), results)
    }

    @Test
    fun anActivityDestroyedForARotationDoesNotLoseTheCallbacks() {
        val controller = ask()
        controller.destroy() // not finishing: the system will recreate it
        assertEquals(listOf<Boolean?>(), results)
        val again = Robolectric.buildActivity(KiroraPermissionActivity::class.java).create()
        again.answer(PackageManager.PERMISSION_GRANTED)
        assertEquals(listOf<Boolean?>(true), results)
    }

    @Test
    @Config(sdk = [32])
    fun beforeAndroid13NoPromptExistsSoItIsGrantedAtOnce() {
        val controller = ask()
        assertEquals(listOf<Boolean?>(true), results)
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun aSecondAskAfterAnAnswerStartsANewPrompt() {
        ask().answer(PackageManager.PERMISSION_DENIED)
        ask().answer(PackageManager.PERMISSION_GRANTED)
        assertEquals(listOf<Boolean?>(false, true), results)
    }

    @Test
    fun aStartFromANonActivityContextUsesANewTask() {
        KiroraPermissionActivity.request(context) { results.add(it) }
        val intent = shadowOf(context as android.app.Application).nextStartedActivity
        assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        Robolectric.buildActivity(KiroraPermissionActivity::class.java, intent).create().answer(PackageManager.PERMISSION_GRANTED)
    }

    @Test
    fun fromAnActivityNoNewTaskFlagIsAdded() {
        val host: Activity = Robolectric.buildActivity(Activity::class.java).create().get()
        KiroraPermissionActivity.request(host) { results.add(it) }
        val intent = shadowOf(host).nextStartedActivity
        assertEquals(0, intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        Robolectric.buildActivity(KiroraPermissionActivity::class.java, intent).create().answer(PackageManager.PERMISSION_GRANTED)
    }
}
