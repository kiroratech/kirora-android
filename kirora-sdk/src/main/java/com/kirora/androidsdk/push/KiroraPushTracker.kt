package com.kirora.androidsdk.push

import android.content.Context
import com.kirora.androidsdk.net.KiroraApiClient
import com.kirora.androidsdk.net.KiroraApiService
import com.kirora.androidsdk.net.models.PushTrackingRequest
import com.kirora.androidsdk.utils.GlobalPreferences
import com.kirora.androidsdk.utils.KiroraLogger
import com.kirora.androidsdk.utils.RetryAfter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Reports a Kirora notification's `delivered`, `opened` or `clicked` to the push's own
 * `trackingUrl`, which needs no credentials and names no device: Kirora signed the delivery into
 * it. Works with or without an instance, so also on a cold start from a tap.
 *
 * These are operational push reports, sent whatever the behavioural tracking consent. Each is
 * sent once per notification; a network error, 429 or 5xx is retried once, never sooner than
 * `Retry-After` (a report asked to wait over a minute is dropped instead), and any other 4xx is
 * never retried. Push payloads are untrusted: only Kirora's own API host is called.
 */
internal object KiroraPushTracker {
    @Volatile
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reported = LinkedHashSet<String>()

    @Volatile
    private var sharedClient: Pair<String, KiroraApiService>? = null

    private const val DEFAULT_API_URL = "https://api.kirora.io"
    private const val REMEMBERED = 100
    private const val RETRY_DELAY_MS = 2_000L
    /** A report is never worth holding longer than this: a longer `Retry-After` drops it instead. */
    private const val MAX_RETRY_DELAY_MS = 60_000L

    const val DELIVERED = "delivered"
    /** Answered at once: nothing to report, so nothing to wait for. */
    private val DONE: Deferred<Unit> = CompletableDeferred(Unit)

    const val OPENED = "opened"
    const val CLICKED = "clicked"

    /**
     * Reports [event] for the push whose `trackingUrl` is [trackingUrl]. A push without one reports
     * nothing. The answer completes when the first attempt has ended (a tap waits for that, at
     * most 1.5 seconds); a retry goes on after it, in the background.
     */
    fun report(context: Context, apiUrl: String?, trackingUrl: String?, event: String, api: KiroraApiService? = null): Deferred<Unit> {
        if (trackingUrl.isNullOrBlank()) return DONE
        val app = context.applicationContext
        val base = baseUrl(app, apiUrl) ?: return DONE
        val url = trusted(base, trackingUrl)
        if (url == null) {
            KiroraLogger.w("Ignoring a push trackingUrl that is not on the Kirora API host.")
            return DONE
        }
        // The first of each counts and a repeat changes nothing, so a repeat isn't sent.
        synchronized(reported) {
            if (!reported.add("$event $url")) return DONE
            while (reported.size > REMEMBERED) reported.remove(reported.first())
        }
        val firstAttempt = CompletableDeferred<Unit>()
        scope.launch {
            var service: KiroraApiService? = null
            val first = try {
                // Inside the try: a client that can't be built must not keep a tap waiting.
                service = api ?: client(base)
                send(service, url.toString(), event)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KiroraLogger.w("Could not report the push $event", e)
                null
            } finally {
                firstAttempt.complete(Unit)
            }
            if (first !is Outcome.Retry || service == null) return@launch
            // Once more, after a pause and never sooner than Retry-After, then dropped. A report
            // that would have to wait longer than a minute is dropped now, not sent early.
            val wait = maxOf(first.retryAfterMs ?: 0L, RETRY_DELAY_MS)
            if (wait > MAX_RETRY_DELAY_MS) {
                KiroraLogger.d("Push $event report dropped: Kirora asked for a longer wait than a report is kept.")
                return@launch
            }
            delay(wait)
            if (send(service, url.toString(), event) is Outcome.Retry) {
                KiroraLogger.d("Push $event report dropped after one retry.")
            }
        }
        return firstAttempt
    }

    private sealed class Outcome {
        object Done : Outcome()
        object Refused : Outcome()
        class Retry(val retryAfterMs: Long?) : Outcome()
    }

    private suspend fun send(service: KiroraApiService, url: String, event: String): Outcome = try {
        val response = service.reportPush(url, PushTrackingRequest(event))
        val code = response.code()
        when {
            response.isSuccessful -> Outcome.Done
            code == 429 || code >= 500 -> Outcome.Retry(RetryAfter.parse(response.headers()))
            else -> {
                KiroraLogger.w("Push $event report refused: HTTP $code")
                Outcome.Refused
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        KiroraLogger.d("Push $event report failed: ${e.javaClass.simpleName}")
        Outcome.Retry(null)
    }

    private fun baseUrl(context: Context, apiUrl: String?): HttpUrl? =
        (apiUrl ?: GlobalPreferences(context).getApiUrl() ?: DEFAULT_API_URL).toHttpUrlOrNull()

    /** One client per API host, instead of a new connection pool for every report. */
    private fun client(base: HttpUrl): KiroraApiService {
        val key = base.toString()
        sharedClient?.takeIf { it.first == key }?.let { return it.second }
        return KiroraApiClient.create(key, { "" }, { null }).also { sharedClient = key to it }
    }

    /**
     * The tracking URL itself, only when it begins with the configured API URL followed by `/t/p/`
     * (same scheme, host and port, under the API URL's own path when it has one), with no user
     * info and no `.` or `..` segments, which could step out of that path.
     */
    fun trusted(base: HttpUrl, trackingUrl: String): HttpUrl? {
        // Checked on the text: parsing quietly resolves dot segments.
        if (hasDotSegment(trackingUrl)) return null
        val resolved = base.resolve(trackingUrl) ?: return null
        if (resolved.scheme != base.scheme || resolved.host != base.host || resolved.port != base.port) return null
        if (resolved.username.isNotEmpty() || resolved.password.isNotEmpty()) return null
        val prefix = base.encodedPath.trimEnd('/') + "/t/p/"
        if (!resolved.encodedPath.startsWith(prefix) || resolved.encodedPath.length == prefix.length) return null
        return resolved
    }

    /** A `.` or `..` segment, written plainly or percent-encoded (`%2e`). A host of `.` fails the host check anyway. */
    private fun hasDotSegment(url: String): Boolean =
        url.substringBefore('?').substringBefore('#').split('/').any { segment ->
            val plain = segment.replace("%2e", ".", ignoreCase = true)
            plain == "." || plain == ".."
        }

    internal fun resetForTesting() {
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        synchronized(reported) { reported.clear() }
        sharedClient = null
    }
}
