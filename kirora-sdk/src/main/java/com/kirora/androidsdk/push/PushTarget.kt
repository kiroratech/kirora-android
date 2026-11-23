package com.kirora.androidsdk.push

/** How Firebase identifies this device for push, as Kirora's device registration names it (`targetKind`). */
internal object PushTarget {
    const val FCM_TOKEN = "fcm_token"
    const val FIREBASE_INSTALLATION_ID = "firebase_installation_id"
}
