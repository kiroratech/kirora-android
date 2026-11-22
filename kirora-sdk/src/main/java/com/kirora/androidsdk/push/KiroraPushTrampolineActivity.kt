package com.kirora.androidsdk.push

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.app.NotificationManagerCompat
import com.kirora.androidsdk.Kirora
import com.kirora.androidsdk.utils.KiroraLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Transparent Activity that is the destination of a notification and of its buttons (no service
 * or receiver in between), so it works under Android 12+'s trampoline restrictions. It handles taps directly,
 * ensuring compliance with Android 12+ (API 31+) notification trampoline restrictions.
 * It reports click/open tracking, dispatches SDK listeners, launches target deep links or
 * the launcher Activity, and finishes immediately.
 */
internal class KiroraPushTrampolineActivity : Activity() {

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The tap being handled, until it has reached the app; then null. */
    private var pending: Tap? = null

    /** [clicked]: the tap follows a link or a button (`clicked`), rather than a plain `opened`. */
    private class Tap(val data: Map<String, String>, val actionUrl: String?, val clicked: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated (a configuration change): the first instance already handed the tap over.
        if (savedInstanceState != null) {
            finish()
            return
        }
        val tap = try {
            readTap(intent)
        } catch (e: Exception) {
            KiroraLogger.e("Error processing notification trampoline intent", e)
            null
        }
        if (tap == null) {
            finish()
            return
        }
        pending = tap
        // Stays (invisible) while the tap waits for its report and any wrapper's answer.
        mainScope.launch {
            try {
                Kirora.awaitTapReport(Kirora.reportNotificationTap(applicationContext, tap.data["trackingUrl"], clicked = tap.clicked))
                if (handOver()) open(tap)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KiroraLogger.e("Error processing notification trampoline intent", e)
            } finally {
                finish()
            }
        }
    }

    override fun onDestroy() {
        // Gone before the wait ended (Back, or the system ended it): the tap reaches the app
        // now, without the rest of the wait; the report goes on in the background.
        val tap = pending
        try {
            if (tap != null && handOver()) openNow(applicationContext, tap.actionUrl)
        } catch (e: Exception) {
            KiroraLogger.e("Error processing notification trampoline intent", e)
        }
        mainScope.cancel()
        super.onDestroy()
    }

    /** Tells the app's listeners of the pending tap, once: false when it was already handed over. */
    private fun handOver(): Boolean {
        val tap = pending ?: return false
        pending = null
        Kirora.deliverNotificationTap(tap.data)
        return true
    }

    private fun readTap(intent: Intent?): Tap? {
        if (intent == null) return null
        val extras = intent.extras ?: return null

        val data = mutableMapOf<String, String>()
        for (key in extras.keySet()) {
            if (key in KiroraPushDisplay.INTERNAL_EXTRAS) continue
            extras.get(key)?.let { data[key] = it.toString() }
        }
        val buttonIndex = if (extras.containsKey(EXTRA_BUTTON_INDEX)) extras.getInt(EXTRA_BUTTON_INDEX) else null
        val buttonUrl = extras.getString(EXTRA_BUTTON_URL)?.takeIf { it.isNotBlank() }
        // `link` is the push's `actionUrl` in Android's data-only payload.
        val actionUrl = buttonUrl ?: data["link"]?.takeIf { it.isNotBlank() }

        // Buttons do not dismiss the notification by themselves.
        if (buttonIndex != null) {
            NotificationManagerCompat.from(this).cancel(
                extras.getString(EXTRA_NOTIFICATION_TAG),
                extras.getInt(EXTRA_NOTIFICATION_ID, 0)
            )
        }
        return Tap(data, actionUrl, clicked = buttonIndex != null || actionUrl != null)
    }

    /** Opens what the tap is for: its link (through the app's or a wrapper's handler), or the app. */
    private suspend fun open(tap: Tap) {
        val actionUrl = tap.actionUrl
        if (actionUrl != null && Kirora.active?.options?.handleDeepLink == null && openable(actionUrl) &&
            Kirora.awaitsWrapperCallbacks(applicationContext)
        ) {
            // A wrapper installed push at launch but hasn't set its deep-link handler yet: open the
            // app so its code runs, and hand the link over once it does (or after 10 seconds).
            bringAppForward(this)
            Kirora.openWhenWrapperReady(applicationContext, actionUrl)
            return
        }

        if (actionUrl != null) {
            val wrapper = Kirora.wrapperDeepLinkHandler
            if (Kirora.active?.options?.handleDeepLink == null && wrapper != null && openable(actionUrl)) {
                // Asked across the wrapper's async bridge, for 1.5 seconds at most.
                val handled = withTimeoutOrNull(Kirora.WRAPPER_ANSWER_MS) {
                    try {
                        wrapper(actionUrl)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        KiroraLogger.e("Error in the wrapper's deep link handler", e)
                        false
                    }
                } ?: false
                when {
                    // The app routed it, perhaps with no screen showing: bring it forward.
                    handled -> bringAppForward(this)
                    !openAction(this, actionUrl) -> bringAppForward(this)
                }
                return
            }
        }
        openNow(this, actionUrl)
    }

    companion object {
        const val EXTRA_BUTTON_INDEX: String = "kirora_button_index"
        const val EXTRA_BUTTON_TITLE: String = "kirora_button_title"
        const val EXTRA_BUTTON_URL: String = "kirora_button_url"
        const val EXTRA_ACTION_TYPE: String = "kirora_action_type"
        const val EXTRA_NOTIFICATION_TAG: String = "kirora_notification_tag"
        const val EXTRA_NOTIFICATION_ID: String = "kirora_notification_id"
        private val BLOCKED_SCHEMES = setOf("javascript", "file", "content", "intent", "data")

        internal fun openable(url: String): Boolean {
            val scheme = Uri.parse(url).scheme?.lowercase() ?: return false
            return scheme !in BLOCKED_SCHEMES
        }

        /** An app link or custom scheme goes to this app first; a web page to whatever opens it. */
        internal fun openAction(context: Context, url: String): Boolean {
            val packageManager = context.packageManager
            val packageName = context.packageName
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme in BLOCKED_SCHEMES) return false
            val options = Kirora.active?.options
            val handler = options?.handleDeepLink
            if (handler != null) {
                try {
                    if (handler(url)) {
                        bringAppForward(context)
                        return true
                    }
                } catch (e: Exception) {
                    KiroraLogger.e("Error in handleDeepLink", e)
                }
            }
            val allowed = options?.push?.allowedLinkSchemes
            if (allowed != null && allowed.none { it.equals(scheme, ignoreCase = true) }) {
                KiroraLogger.w("The notification's link scheme is not in allowedLinkSchemes; opening the app instead.")
                return false
            }
            val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val own = Intent(view).setPackage(packageName)
            try {
                if (own.resolveActivity(packageManager) != null) {
                    context.startActivity(own)
                    return true
                }
                if (scheme == "https" || scheme == "http") {
                    context.startActivity(view)
                    return true
                }
            } catch (e: Exception) {
                KiroraLogger.w("Could not open the notification's link; opening the app instead", e)
            }
            return false
        }

        /**
         * Opens the app, or brings its task to the front as the launcher would, without closing the
         * screens the user left open.
         */
        internal fun bringAppForward(context: Context) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: run {
                KiroraLogger.w("No launch intent found for package: ${context.packageName}")
                return
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            try {
                context.startActivity(launchIntent)
            } catch (e: Exception) {
                KiroraLogger.w("Could not bring the app forward", e)
            }
        }

        /** Opens [actionUrl] (through `handleDeepLink`, never asking a wrapper) or else the app, at once. */
        internal fun openNow(context: Context, actionUrl: String?) {
            if (actionUrl != null && openAction(context, actionUrl)) return
            bringAppForward(context)
        }
    }
}
