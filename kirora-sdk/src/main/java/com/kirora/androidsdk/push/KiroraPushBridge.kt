package com.kirora.androidsdk.push

import android.content.Context

/**
 * The Firebase-specific half of push, implemented by the optional `android-sdk-push` artifact
 * (`com.kirora.androidsdk.push.fcm.FcmPushBridge`) and found by name when `push = true`.
 * Not meant to be implemented by apps.
 */
@com.kirora.androidsdk.KiroraInternalApi
public interface KiroraPushBridge {
    /** The current FCM registration token, or null when Firebase has none. */
    public suspend fun fetchToken(): String?

    /**
     * True when the app has Firebase register devices by installation ID
     * (`firebase_messaging_installation_id_enabled` in its manifest, set by the app or by any SDK
     * it includes): Firebase's token calls then fail, so the device registers its installation ID.
     */
    public fun registersByInstallationId(context: Context): Boolean = false

    /** Registers with Firebase by installation ID and answers it, or null when Firebase can't. */
    public suspend fun fetchInstallationId(): String? = null

    /** Shows the Android 13+ notification prompt; reports null when it was dismissed unanswered. */
    public fun requestNotificationPermission(context: Context, onResult: (granted: Boolean?) -> Unit)
}
