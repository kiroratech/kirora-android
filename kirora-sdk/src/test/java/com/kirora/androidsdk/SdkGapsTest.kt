package com.kirora.androidsdk

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kirora.androidsdk.net.FakeKiroraApiService
import com.kirora.androidsdk.net.KiroraBatchManager
import com.kirora.androidsdk.utils.KiroraContextProvider
import com.kirora.androidsdk.utils.SdkInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import retrofit2.Response

/** The Android gaps in the backend's `sdk-gaps.md`, by number. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class SdkGapsTest : KiroraTestBase() {

    private suspend fun assertKeptAfter(answer: () -> Response<JsonElement>) {
        val kirora = signedIn()
        fake.batchResponder = { _, _ -> answer() }
        listOf("One", "Two", "Three").forEach { kirora.track(it) }
        kirora.waitQueued(3)
        kirora.flush()
        delay(100)
        // One request, not split, nothing dropped, and backing off rather than sending again.
        assertEquals(1, fake.batchRequests.size)
        assertEquals(3, kirora.queued())
        assertEquals(0, kirora.droppedEventCount.toInt())
        kirora.batchManager!!.flush()
        assertEquals(1, fake.batchRequests.size)
    }

    // #2

    @Test
    fun aWhole404KeepsTheEvents() = runBlocking { assertKeptAfter { FakeKiroraApiService.error(404, "NOT_FOUND") } }

    @Test
    fun aWhole403KeepsTheEvents() = runBlocking { assertKeptAfter { FakeKiroraApiService.error(403, "FORBIDDEN") } }

    @Test
    fun aWhole409KeepsTheEvents() = runBlocking { assertKeptAfter { FakeKiroraApiService.error(409, "CONFLICT") } }

    @Test
    fun aRedirectKeepsTheEvents() = runBlocking { assertKeptAfter { FakeKiroraApiService.errorWithBody(302, "<html></html>") } }

    @Test
    fun theRetryAfterAWholeRefusalSendsEverything() = runBlocking {
        val kirora = signedIn()
        fake.batchResponder = { _, _ -> FakeKiroraApiService.error(404, "NOT_FOUND") }
        listOf("One", "Two").forEach { kirora.track(it) }
        kirora.waitQueued(2)
        kirora.flush()
        fake.batchResponder = null
        kirora.flush() // waives the SDK's own backoff
        kirora.waitQueued(0)
        assertEquals(0, kirora.droppedEventCount.toInt())
    }

    // #13

    @Test
    fun a400AboutTheHeadersIsNeitherSplitNorDropped() = runBlocking {
        assertKeptAfter {
            FakeKiroraApiService.errorWithBody(400, """{"error":{"code":"VALIDATION_ERROR","message":"Kirora-SDK header required: <sdk>/<version>"}}""")
        }
    }

    @Test
    fun aProxysHtml400IsNeitherSplitNorDropped() = runBlocking {
        assertKeptAfter { FakeKiroraApiService.errorWithBody(400, "<html><body>Bad Request</body></html>") }
    }

    @Test
    fun a400ListingOnlyHeaderProblemsIsNeitherSplitNorDropped() = runBlocking {
        assertKeptAfter {
            FakeKiroraApiService.errorWithBody(400, """{"error":{"code":"VALIDATION_ERROR","message":"x","errors":[{"in":"headers","field":"kirora-sdk","message":"x"}]}}""")
        }
    }

    @Test
    fun a400NamingAnEventIsSplitAndOnlyThatEventDropped() = runBlocking {
        val kirora = signedIn()
        fake.batchResponder = { request, _ ->
            if (request.events.any { it.name == "Bad" }) {
                FakeKiroraApiService.errorWithBody(400, """{"error":{"code":"VALIDATION_ERROR","message":"x","errors":[{"in":"body","field":"events.1.idempotencyKey","message":"x"}]}}""")
            } else {
                FakeKiroraApiService.accepted(request)
            }
        }
        listOf("One", "Bad", "Two", "Three").forEach { kirora.track(it) }
        kirora.waitQueued(4)
        kirora.flush()
        kirora.waitQueued(0)
        assertEquals(1, kirora.droppedEventCount.toInt())
    }

    @Test
    fun whatCountsAsA400AboutTheEvents() {
        fun about(json: String?) = KiroraBatchManager.aboutTheEvents(json?.let { JsonParser.parseString(it) as JsonObject })
        assertFalse("no Kirora body: a proxy's page", about(null))
        assertFalse("no error code", about("""{"error":{"message":"x"}}"""))
        assertFalse("no list: a request problem", about("""{"error":{"code":"VALIDATION_ERROR","message":"Text must not contain the NUL character"}}"""))
        assertTrue(about("""{"error":{"code":"VALIDATION_ERROR","message":"x","errors":[{"in":"body","field":"events.3.properties.note","code":"custom"}]}}"""))
        assertTrue(about("""{"error":{"code":"VALIDATION_ERROR","message":"x","errors":[{"in":"body","field":"events.0.name"}]}}"""))
        assertFalse("where unknown", about("""{"error":{"code":"VALIDATION_ERROR","message":"x","errors":[{"field":"sentAt"}]}}"""))
        assertFalse(about("""{"error":{"code":"VALIDATION_ERROR","message":"x","errors":[{"in":"query","field":"x"}]}}"""))
        assertFalse(about("""{"error":{"code":"VALIDATION_ERROR","message":"Kirora-SDK header required: <sdk>/<version>"}}"""))
        assertFalse(about("""{"error":{"code":"VALIDATION_ERROR","message":"x","errors":[{"in":"headers","field":"kirora-sdk"}]}}"""))
    }

    @Test
    fun theSdksOwnVersionIsOneTheServerTakes() {
        assertTrue(SdkInfo.VERSION_PATTERN.matches(SdkInfo.VERSION))
        assertFalse(SdkInfo.VERSION_PATTERN.matches("1.0.0_rc1"))
    }

    // #4

    @Test
    fun a401WithNoFetchUserTokenBacksOffUntilANewToken() = runBlocking {
        val kirora = signedIn()
        fake.batchResponder = { _, _ -> FakeKiroraApiService.error(401, "UNAUTHORIZED") }
        kirora.track("E1")
        kirora.waitQueued(1)
        kirora.flush()
        assertEquals(1, fake.batchRequests.size)

        // The 30-second timer or 20 queued events: no new request with the refused token.
        kirora.batchManager!!.flush()
        kirora.batchManager!!.onNetworkAvailable()
        delay(100)
        assertEquals(1, fake.batchRequests.size)

        // identify() with a new token sends at once.
        fake.batchResponder = null
        kirora.identify(jwt("user-a", nonce = "fresh"))
        kirora.waitQueued(0)
        assertEquals(2, fake.batchRequests.size)
    }

    @Test
    fun aRefusalOfTheOldTokenDoesNotPauseTheNewOneIdentifyGaveMeanwhile() = runBlocking {
        val oldToken = jwt("user-a", nonce = "old")
        val newToken = jwt("user-a", nonce = "new")
        val kirora = start()
        kirora.identify(oldToken)
        val gate = CompletableDeferred<Unit>()
        fake.batchResponder = { request, authorization ->
            if (authorization == "Bearer $oldToken") {
                gate.await()
                FakeKiroraApiService.error(401, "UNAUTHORIZED")
            } else {
                FakeKiroraApiService.accepted(request)
            }
        }
        kirora.track("E1")
        kirora.waitQueued(1)
        val sending = launch { kirora.flush() }
        waitUntil(message = "the old token's request") { fake.batchRequests.size == 1 }
        // Same user, new token, while the old token's request is still waiting for its answer.
        kirora.identify(newToken)
        gate.complete(Unit)
        sending.join()
        kirora.waitQueued(0)
        assertEquals("Bearer $newToken", fake.batchAuthorizations.last())
    }

    @Test
    fun theNewTokenTriesOnceThenItsOwnFailuresBackOff() = runBlocking {
        val kirora = signedIn()
        fake.batchResponder = { _, _ -> FakeKiroraApiService.error(401, "UNAUTHORIZED") }
        kirora.track("E1")
        kirora.waitQueued(1)
        kirora.flush()
        assertEquals(1, fake.batchRequests.size)

        // The new token's first attempt meets a 503 with no Retry-After.
        fake.batchResponder = { _, _ -> FakeKiroraApiService.error(503, "SERVICE_UNAVAILABLE") }
        kirora.identify(jwt("user-a", nonce = "fresh"))
        waitUntil(message = "the new token's attempt") { fake.batchRequests.size == 2 }
        delay(100)
        // Later sends wait out that failure's backoff instead of going at once.
        kirora.batchManager!!.flush()
        kirora.batchManager!!.flush()
        delay(100)
        assertEquals(2, fake.batchRequests.size)
    }

    // #3

    @Test
    fun theLocaleKeepsLanguageScriptAndRegionOnly() {
        fun tag(text: String) = KiroraContextProvider.localeTag(Locale.forLanguageTag(text))
        assertEquals("en-GB", tag("en-GB-u-fw-mon-mu-celsius-ms-uksystem-nu-latn"))
        assertEquals("zh-Hant-TW", tag("zh-Hant-TW-u-ca-roc"))
        assertEquals("de-DE", tag("de-DE-1996-x-private"))
        assertEquals("fr", tag("fr"))
        assertNull(tag("und"))
    }
}
