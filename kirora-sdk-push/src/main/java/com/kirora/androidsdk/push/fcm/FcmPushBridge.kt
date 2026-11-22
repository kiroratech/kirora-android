package com.kirora.androidsdk.push.fcm

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import com.kirora.androidsdk.push.KiroraPushBridge
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Found by name from the core SDK when `push = true`. */
internal class FcmPushBridge : KiroraPushBridge {

    // Deprecated from Firebase Messaging 25.1 in favour of registering by Firebase installation ID.
    // Kirora registers devices by FCM token (`targetKind` fcm_token), so this stays until the
    // registration contract moves, for every SDK at once.
    @Suppress("DEPRECATION")
    override suspend fun fetchToken(): String? = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (cont.isActive) cont.resume(if (task.isSuccessful) task.result else null)
        }
    }

    override fun requestNotificationPermission(context: Context, onResult: (granted: Boolean?) -> Unit) {
        KiroraPermissionActivity.request(context, onResult)
    }
}
