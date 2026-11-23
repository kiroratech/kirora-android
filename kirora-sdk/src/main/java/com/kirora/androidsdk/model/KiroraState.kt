package com.kirora.androidsdk.model

/**
 * A snapshot of the client for an app's support and debug screens, from
 * [com.kirora.androidsdk.Kirora.getState]. Read-only and never sent anywhere.
 *
 * Not a `data class`, so fields can be added without breaking apps: no `copy()` or
 * destructuring, and only the SDK creates one.
 */
public class KiroraState internal constructor(
    /** The client is set up and not destroyed. */
    public val isInitialized: Boolean,
    /** A user is signed in. */
    public val isIdentified: Boolean,
    /** The id sent in `context.device.id`, or null when there is none (after tracking consent was withdrawn, say). */
    public val deviceId: String?,
    /** The current visit, or null. */
    public val sessionId: String?,
    /**
     * The FCM token (or, in Firebase's installation-ID mode, the installation ID) in this device's
     * current registration for the signed-in user; null while there
     * is none (before sign-in, after `logout()`, while `setPushEnabled(false)` holds, during a detach,
     * once a denied permission has been reported, after a failed registration), even when Firebase
     * holds a token. Notifications turned off in system settings show here at the next `identify()`.
     */
    public val pushToken: String?,
    /** Whether behavioural tracking is allowed now. */
    public val trackingConsent: Boolean,
    /**
     * The signed-in user's marketing push choice as this device last recorded it with Kirora: saved
     * only once Kirora accepts `setPushConsent` (a refused call keeps the previous value). False when
     * there is none; cleared at `logout()` and when another user signs in.
     */
    public val pushConsent: Boolean,
    /** Events waiting on the device, whoever they belong to. */
    public val queuedEventCount: Int,
) {
    private fun fields(): List<Any?> =
        listOf(isInitialized, isIdentified, deviceId, sessionId, pushToken, trackingConsent, pushConsent, queuedEventCount)

    override fun equals(other: Any?): Boolean = other is KiroraState && fields() == other.fields()

    override fun hashCode(): Int = fields().hashCode()

    override fun toString(): String =
        "KiroraState(isInitialized=$isInitialized, isIdentified=$isIdentified, deviceId=$deviceId, " +
            "sessionId=$sessionId, pushToken=$pushToken, trackingConsent=$trackingConsent, " +
            "pushConsent=$pushConsent, queuedEventCount=$queuedEventCount)"
}
