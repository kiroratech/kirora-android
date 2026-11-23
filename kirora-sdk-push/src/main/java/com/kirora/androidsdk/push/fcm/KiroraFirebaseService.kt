package com.kirora.androidsdk.push.fcm

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Default FCM entry point. A Kirora push (`kirora` = `"true"`) is drawn by the SDK in every app state;
 * any other push goes to [KiroraPush.setFallbackMessageHandler]. Apps that already have a
 * `FirebaseMessagingService` keep it and call [KiroraPush.handleMessage] and [KiroraPush.onNewToken].
 */
internal class KiroraFirebaseService : FirebaseMessagingService() {

    // Deprecated from Firebase Messaging 25.1, with the token itself: see FcmPushBridge.fetchToken.
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        KiroraPush.onNewToken(applicationContext, token)
    }

    // Firebase's installation-ID mode: the installation ID this device is now registered by.
    override fun onRegistered(installationId: String) {
        super.onRegistered(installationId)
        KiroraPush.onRegistered(applicationContext, installationId)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        KiroraPush.handleMessage(applicationContext, remoteMessage)
    }
}
