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
    fun firebaseTasksAreAwaitedWithoutTheMainThreadAndARegistrationWithNoResultCountsAsDone() = kotlinx.coroutines.runBlocking {
        val registered: com.google.android.gms.tasks.Task<Void> = com.google.android.gms.tasks.Tasks.forResult(null)
        assertEquals(true, registered.succeeded())
        assertEquals(false, com.google.android.gms.tasks.Tasks.forException<Void>(Exception("down")).succeeded())
        assertEquals("fid-1", com.google.android.gms.tasks.Tasks.forResult("fid-1").await())
        assertEquals(null, com.google.android.gms.tasks.Tasks.forException<String>(Exception("down")).await())
    }

    @Test
    fun installationIdModeFollowsTheAppsManifestFlag() {
        assertEquals(false, FcmPushBridge().registersByInstallationId(context))
        shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).applicationInfo!!.metaData =
            android.os.Bundle().apply { putBoolean("firebase_messaging_installation_id_enabled", true) }
        assertEquals(true, FcmPushBridge().registersByInstallationId(context))
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
