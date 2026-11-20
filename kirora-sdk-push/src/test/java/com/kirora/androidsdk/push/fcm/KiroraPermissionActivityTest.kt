package com.kirora.androidsdk.push.fcm

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
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
