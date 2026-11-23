package com.kirora.androidsdk.push.fcm

import android.content.Context
import com.google.firebase.messaging.RemoteMessage
import com.kirora.androidsdk.Kirora

/** The FCM side of Kirora push, for apps that have their own `FirebaseMessagingService`. */
public object KiroraPush {

    @Volatile
    private var fallback: ((RemoteMessage) -> Unit)? = null

    /**
     * Receives every push that is not Kirora's, from the SDK's own service, so an app can keep
     * handling its other pushes. Set it early (e.g. in `Application.onCreate`).
     */
    @JvmStatic
    public fun setFallbackMessageHandler(handler: ((RemoteMessage) -> Unit)?) {
        fallback = handler
    }

    /**
     * Call from your own `onMessageReceived`. Draws the push when it is Kirora's and returns `true`;
     * returns `false` for any other push, which is then yours.
     */
    @JvmStatic
    public fun handleMessage(context: Context, message: RemoteMessage): Boolean {
        if (Kirora.handlePushMessage(context, message.data)) return true
        fallback?.invoke(message)
        return false
    }

    /** Call from your own `onNewToken`. */
    @JvmStatic
    public fun onNewToken(context: Context, token: String) {
        Kirora.handleNewPushToken(context, token)
    }

    /** Call from your own `onRegistered` (Firebase's installation-ID mode). */
    @JvmStatic
    public fun onRegistered(context: Context, installationId: String) {
        Kirora.handlePushRegistered(context, installationId)
    }
}
