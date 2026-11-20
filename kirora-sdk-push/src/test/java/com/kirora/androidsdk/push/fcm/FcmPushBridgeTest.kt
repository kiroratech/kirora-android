package com.kirora.androidsdk.push.fcm

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The core SDK finds this class by name; its permission request goes through the prompt activity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class FcmPushBridgeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theBridgeIsFoundByTheNameTheCoreSdkLooksUp() {
        val bridge = Class.forName("com.kirora.androidsdk.push.fcm.FcmPushBridge")
            .getDeclaredConstructor().newInstance()
        assertEquals("FcmPushBridge", bridge.javaClass.simpleName)
    }

    @Test
    fun theNotificationPromptAnswerComesBackThroughTheBridge() {
        val results = mutableListOf<Boolean?>()
        FcmPushBridge().requestNotificationPermission(context) { results.add(it) }
        val started = shadowOf(context as Application).nextStartedActivity
        Robolectric.buildActivity(KiroraPermissionActivity::class.java, started).create().get()
            .onRequestPermissionsResult(7301, arrayOf(Manifest.permission.POST_NOTIFICATIONS), intArrayOf(PackageManager.PERMISSION_GRANTED))
        assertEquals(listOf<Boolean?>(true), results)
    }
}
