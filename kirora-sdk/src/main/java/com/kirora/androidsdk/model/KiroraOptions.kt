package com.kirora.androidsdk.model

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes

/**
 * SDK configuration: [clientKey], then any options by name.
 *
 * Not a `data class`, so options can be added. Once released, the constructor's shape is part of
 * the binary API (checked against `api/`): a new option goes last, with a default, and the
 * previous constructor stays, hidden, so apps built against the earlier version keep working.
 */
public class KiroraOptions @JvmOverloads constructor(
    /** The workspace's public key (`kirora_pk_…`), safe to ship. */
    public val clientKey: String,
    /**
     * Asks the app's server for a signed user token. Called by `identify()` without a token and
     * when Kirora refuses an expired one. One that hasn't answered after 30 seconds counts as failed.
     */
    public val fetchUserToken: (suspend () -> String?)? = null,
    /** Registers this device for push. Off when null. */
    public val push: KiroraPushOptions? = null,
    /** Collect no behavioural events, session state or influenced app opens until `setTrackingConsent(true)`. */
    public val requireTrackingConsent: Boolean = false,
    /** `Session Started` when a visit begins, `context.sessionId` on every event. */
    public val trackSessions: Boolean = true,
    /** `Application Installed`, `Application Opened`, `Application Updated` and `Application Backgrounded`. */
    public val trackAppLifecycle: Boolean = false,
    public val logLevel: LogLevel = LogLevel.ERROR,
    /** Staging or a private deployment. */
    public val apiUrl: String = "https://api.kirora.io",
    /**
     * Redacts or drops a behavioural event before it is stored or sent. Runs once per event,
     * synchronously, on a background thread, after the event's properties and context are
     * assembled. Return the event (changed or not), or null to drop it. A hook that throws, or
     * answers something that is not JSON, drops the event; only the kind of failure is logged.
     * Tracking from inside the hook is refused.
     */
    public val beforeSend: ((BeforeSendEvent) -> BeforeSendEvent?)? = null,
    /**
     * Called with a push's link when it is tapped (the notification's, or the tapped button's).
     * Return `true` when the app opened it itself. Otherwise the SDK opens it: an app link or a
     * custom scheme of this app in the app, anything else with the system. Runs on the main thread.
     * Never called for blocked schemes (`javascript`, `file`, `content`, `intent`, `data`).
     */
    public val handleDeepLink: ((url: String) -> Boolean)? = null,
    /**
     * Called when Kirora can't keep the signed-in user authenticated in the background: a token
     * refresh failed ([AuthFailureReason.FETCH_FAILED]) or Kirora refused the token
     * ([AuthFailureReason.REFUSED]). Never for a failure `identify()` already throws. Events stay
     * queued; sign the user in again with `identify()`. Runs on the main thread, once per failure:
     * again only after a call with the user's token has succeeded or `identify()` was called.
     */
    public val onAuthenticationFailed: ((reason: AuthFailureReason) -> Unit)? = null
) {
    internal fun withClientKey(key: String) = KiroraOptions(
        clientKey = key,
        fetchUserToken = fetchUserToken,
        push = push,
        requireTrackingConsent = requireTrackingConsent,
        trackSessions = trackSessions,
        trackAppLifecycle = trackAppLifecycle,
        logLevel = logLevel,
        apiUrl = apiUrl,
        beforeSend = beforeSend,
        handleDeepLink = handleDeepLink,
        onAuthenticationFailed = onAuthenticationFailed
    )
}

/**
 * Turns push on. Kirora chooses the push app unless the workspace has several and [pushAppId]
 * names one. Everything else about push comes from Kirora; the rest here is how a notification
 * looks on this device.
 */
public class KiroraPushOptions @JvmOverloads constructor(
    public val pushAppId: String? = null,
    @DrawableRes public val smallIconResId: Int? = null,
    @ColorInt public val accentColor: Int? = null,
    public val defaultChannelId: String = "kirora_default",
    public val defaultChannelName: String = "Notifications",
    /**
     * Decides whether a push is drawn while the app is in the foreground. Return `true` to show it
     * (the default), `false` to hold it back.
     */
    public val onForegroundNotification: ((data: Map<String, String>) -> Boolean)? = null,
    /**
     * When set, Kirora only opens links whose scheme is in this set (case-insensitive, e.g.
     * `setOf("https", "myapp")`); anything else opens the app instead. Null (the default) allows
     * every scheme except the blocked ones. Does not limit what `handleDeepLink` is given.
     */
    public val allowedLinkSchemes: Set<String>? = null
)
