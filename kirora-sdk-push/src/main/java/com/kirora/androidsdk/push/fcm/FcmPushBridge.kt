package com.kirora.androidsdk.push.fcm

import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.tasks.Task
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import com.kirora.androidsdk.push.KiroraPushBridge
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Found by name from the core SDK when `push = true`. */
internal class FcmPushBridge : KiroraPushBridge {

    // Deprecated from Firebase Messaging 25.1 in favour of registering by installation ID, which
    // is used instead whenever the app turns that mode on (registersByInstallationId).
    @Suppress("DEPRECATION")
    override suspend fun fetchToken(): String? = FirebaseMessaging.getInstance().token.await()

    /** The flag Firebase itself reads: set by the app, or merged in from any SDK it includes. */
    override fun registersByInstallationId(context: Context): Boolean {
        val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        return info.metaData?.getBoolean(INSTALLATION_ID_FLAG, false) == true
    }

    override suspend fun fetchInstallationId(): String? {
        // Registers this installation for messages (onRegistered follows), then reads its id.
        if (!FirebaseMessaging.getInstance().register().succeeded()) return null
        return FirebaseInstallations.getInstance().id.await()
    }

    override fun requestNotificationPermission(context: Context, onResult: (granted: Boolean?) -> Unit) {
        KiroraPermissionActivity.request(context, onResult)
    }

    private companion object {
        const val INSTALLATION_ID_FLAG = "firebase_messaging_installation_id_enabled"
    }
}

// Answered on whichever thread completes the task: nothing here needs the main thread.

/** The task's result, or null when it failed. */
internal suspend fun <T> Task<T>.await(): T? = suspendCancellableCoroutine { cont ->
    addOnCompleteListener({ it.run() }) { task ->
        if (cont.isActive) cont.resume(if (task.isSuccessful) task.result else null)
    }
}

/** Whether a task with no result (`Task<Void>`) succeeded. */
internal suspend fun Task<*>.succeeded(): Boolean = suspendCancellableCoroutine { cont ->
    addOnCompleteListener({ it.run() }) { task -> if (cont.isActive) cont.resume(task.isSuccessful) }
}
