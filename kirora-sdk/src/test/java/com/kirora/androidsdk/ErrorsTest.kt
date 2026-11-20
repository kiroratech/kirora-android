package com.kirora.androidsdk

import android.os.Looper
import com.kirora.androidsdk.model.AuthFailureReason
import com.kirora.androidsdk.model.KiroraApiError
import com.kirora.androidsdk.model.KiroraError
import com.kirora.androidsdk.model.KiroraOptions
import com.kirora.androidsdk.model.UserTokenError
import com.kirora.androidsdk.net.FakeKiroraApiService
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

/** Errors reach the app, all of base type KiroraError ("Methods", "Errors"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class ErrorsTest : KiroraTestBase() {
    private inline fun <reified T : Throwable> thrown(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.simpleName}, got $e", e)
        }
        throw AssertionError("expected ${T::class.simpleName}")
    }

    @Test
    fun identifyThrowsASetupErrorWithNoTokenAndNoFetchUserToken() = runBlocking {
        val kirora = start()
        val e = thrown<KiroraError> { runBlocking { kirora.identify() } }
        assertFalse("a setup error, not the token's", e is UserTokenError)
    }

    @Test
    fun identifyThrowsASetupErrorForAnApiUrlThatIsNotAnAddress() = runBlocking {
        val kirora = Kirora(context, KiroraOptions(clientKey = "kirora_pk_test", apiUrl = "not a url", trackSessions = false))
        val e = thrown<KiroraError> { runBlocking { kirora.identify(jwt("user-a")) } }
        assertTrue(e !is UserTokenError && e !is KiroraApiError)
    }

    @Test
    fun setPushConsentReturnsOnceRecorded() = runBlocking {
        val kirora = start()
        kirora.identify(jwt("user-a"))
        kirora.setPushConsent(true)
        assertTrue(fake.pushConsentRequests.single().granted)
    }

    @Test
    fun setPushConsentThrowsWhenNotRecorded() = runBlocking {
        val kirora = start()
        // No one signed in.
        val nobody = thrown<KiroraError> { runBlocking { kirora.setPushConsent(true) } }
        assertTrue(nobody !is KiroraApiError)

        kirora.identify(jwt("user-a"))
        fake.consentResponder = { FakeKiroraApiService.error(503, "SERVICE_UNAVAILABLE", retryAfterSeconds = 7) }
        val refused = thrown<KiroraApiError> { runBlocking { kirora.setPushConsent(true) } }
        assertEquals(503, refused.status)
        assertEquals("SERVICE_UNAVAILABLE", refused.code)
        assertEquals(7_000L, refused.retryAfterMs)

        fake.consentResponder = { FakeKiroraApiService.error(401, "UNAUTHORIZED") }
        assertEquals(401, thrown<KiroraApiError> { runBlocking { kirora.setPushConsent(true) } }.status)

        fake.consentResponder = { throw IOException("offline") }
        val offline = thrown<KiroraError> { runBlocking { kirora.setPushConsent(true) } }
        assertTrue(offline !is KiroraApiError && offline.cause is IOException)
        assertFalse(kirora.pushConsent)
    }

    @Test
    fun aRefusedTokenAsksFetchUserTokenOnceThenThrowsTheTokensError() = runBlocking {
        val kirora = start(KiroraOptions(trackSessions = false, fetchUserToken = { throw IllegalStateException("down") }))
        kirora.identify(jwt("user-a"))
        fake.consentResponder = { FakeKiroraApiService.error(401, "UNAUTHORIZED") }
        thrown<UserTokenError> { runBlocking { kirora.setPushConsent(true) } }
        Unit
    }

    @Test
    fun theCallbackFormHandsTheErrorToTheBridge() = runBlocking {
        val kirora = start()
        val answer = CompletableDeferred<KiroraError?>()
        kirora.setPushConsentAsync(true) { answer.complete(it) }
        assertTrue(answer.await() is KiroraError)

        kirora.identify(jwt("user-a"))
        val ok = CompletableDeferred<KiroraError?>()
        kirora.setPushConsentAsync(true) { ok.complete(it) }
        assertNull(ok.await())
    }

    @Test
    fun aDestroyedClientNeverClaimsTheConsentWasRecorded() = runBlocking {
        val kirora = start()
        kirora.identify(jwt("user-a"))
        kirora.destroy()
        thrown<KiroraError> { runBlocking { kirora.setPushConsent(true) } }
        assertTrue(fake.pushConsentRequests.isEmpty())
    }

    @Test
    fun callbackFormsAnswerAfterDestroy() = runBlocking {
        val kirora = start()
        kirora.destroy()
        val consent = CompletableDeferred<KiroraError?>()
        kirora.setPushConsentAsync(true) { consent.complete(it) }
        assertTrue(consent.await() is KiroraError)
        val status = CompletableDeferred<com.kirora.androidsdk.model.PushStatus>()
        kirora.getPushStatusAsync { status.complete(it) }
        status.await()
        val flushed = CompletableDeferred<Unit>()
        kirora.flushAsync { flushed.complete(Unit) }
        flushed.await()
    }

    @Test
    fun overlappingConsentCallsSaveTheValueKiroraAppliedLast() = runBlocking {
        val kirora = start()
        kirora.identify(jwt("user-a"))
        var calls = 0
        fake.consentResponder = {
            // The first answer is the slow one: unserialised, it would land last.
            if (++calls == 1) Thread.sleep(300)
            retrofit2.Response.success(204, Unit)
        }
        val first = launch(kotlinx.coroutines.Dispatchers.IO) { kirora.setPushConsent(true) }
        delay(50)
        kirora.setPushConsent(false)
        first.join()
        assertEquals(listOf(true, false), fake.pushConsentRequests.map { it.granted })
        assertFalse(kirora.pushConsent)
    }

    // ---- onAuthenticationFailed: background failures only ------------------------------------

    private fun authFailures(fetch: suspend () -> String?): Pair<KiroraOptions, MutableList<AuthFailureReason>> {
        val seen = java.util.Collections.synchronizedList(mutableListOf<AuthFailureReason>())
        return KiroraOptions(trackSessions = false, fetchUserToken = fetch, onAuthenticationFailed = { seen.add(it) }) to seen
    }

    @Test
    fun aBackgroundRefreshThatFailsIsReportedOnceOnTheMainThread() = runBlocking {
        var calls = 0
        var thread: Thread? = null
        val (options, seen) = authFailures { if (++calls == 1) jwt("user-a") else throw IOException("down") }
        val kirora = start(KiroraOptions(
            trackSessions = false,
            fetchUserToken = options.fetchUserToken,
            onAuthenticationFailed = { thread = Thread.currentThread(); seen.add(it) }
        ))
        kirora.identify()
        fake.batchResponder = { _, _ -> FakeKiroraApiService.error(401, "UNAUTHORIZED") }
        kirora.track("E")
        kirora.waitQueued(1)
        kirora.flush()
        waitUntilOnMain(message = "the report") { seen.isNotEmpty() }
        assertEquals(listOf(AuthFailureReason.FETCH_FAILED), seen.toList())
        assertEquals(Looper.getMainLooper().thread, thread)

        // The same failing token isn't reported again.
        kirora.batchManager!!.flush(waiveBackoff = true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, seen.size)
    }

    @Test
    fun aFreshTokenKiroraRefusesIsReported() = runBlocking {
        var calls = 0
        val (options, seen) = authFailures { jwt("user-a", nonce = "${++calls}") }
        val kirora = start(options)
        kirora.identify()
        fake.batchResponder = { _, _ -> FakeKiroraApiService.error(401, "UNAUTHORIZED") }
        kirora.track("E")
        kirora.waitQueued(1)
        kirora.flush()
        waitUntilOnMain(message = "the report") { seen.isNotEmpty() }
        assertEquals(listOf(AuthFailureReason.REFUSED), seen.toList())
    }

    @Test
    fun aFailureIdentifyThrowsIsNotReported() = runBlocking {
        val (options, seen) = authFailures { throw IOException("down") }
        val kirora = start(options)
        thrown<UserTokenError> { runBlocking { kirora.identify() } }
        delay(100)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(seen.isEmpty())
    }

    @Test
    fun a401WithNoFreshTokenToTryIsRefusedAndReportedAgainOnlyAfterASuccess() = runBlocking {
        val seen = java.util.Collections.synchronizedList(mutableListOf<AuthFailureReason>())
        val kirora = start(KiroraOptions(trackSessions = false, onAuthenticationFailed = { seen.add(it) }))
        kirora.identify(jwt("user-a"))
        fake.batchResponder = { _, _ -> FakeKiroraApiService.error(401, "UNAUTHORIZED") }
        kirora.track("E1")
        kirora.waitQueued(1)
        kirora.flush()
        waitUntilOnMain(message = "the report") { seen.isNotEmpty() }
        assertEquals(listOf(AuthFailureReason.REFUSED), seen.toList())

        // A retry failing the same way is the same failure.
        kirora.batchManager!!.flush(waiveBackoff = true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, seen.size)

        // Accepted once, then refused again: a new failure.
        fake.batchResponder = null
        kirora.batchManager!!.flush(waiveBackoff = true)
        kirora.waitQueued(0)
        fake.batchResponder = { _, _ -> FakeKiroraApiService.error(401, "UNAUTHORIZED") }
        kirora.track("E2")
        kirora.waitQueued(1)
        kirora.batchManager!!.flush(waiveBackoff = true)
        waitUntilOnMain(message = "the second report") { seen.size == 2 }
    }

    @Test
    fun aUserSigningOutIsNotReported() = runBlocking {
        val seen = java.util.Collections.synchronizedList(mutableListOf<AuthFailureReason>())
        val kirora = start(KiroraOptions(trackSessions = false, onAuthenticationFailed = { seen.add(it) }))
        kirora.identify(jwt("user-a"))
        fake.batchResponder = { _, _ -> FakeKiroraApiService.error(401, "UNAUTHORIZED") }
        kirora.track("E")
        kirora.waitQueued(1)
        kirora.logout() // its flush gets the 401
        delay(100)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(seen.isEmpty())
    }

    @Test
    fun aFailureIdentifyThrowsIsNotAlsoReportedWhenASendDuringItHitItFirst() = runBlocking {
        var calls = 0
        val (base, seen) = authFailures { jwt("user-a", nonce = "${++calls}") }
        val kirora = start(KiroraOptions(
            trackSessions = false,
            push = com.kirora.androidsdk.model.KiroraPushOptions(),
            fetchUserToken = base.fetchUserToken,
            onAuthenticationFailed = base.onAuthenticationFailed
        ))
        kirora.track("Before sign-in")
        kirora.waitQueued(1)
        fake.batchResponder = { _, _ -> FakeKiroraApiService.error(401, "UNAUTHORIZED") }
        // Push registration answers only once the first send has been refused twice (its token
        // and a fresh one), so that background failure comes first.
        fake.deviceResponder = {
            waitUntil(message = "the refused sends") { fake.batchRequests.size >= 2 }
            FakeKiroraApiService.error(401, "UNAUTHORIZED")
        }
        assertEquals(401, thrown<KiroraApiError> { runBlocking { kirora.identify() } }.status)
        delay(100)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("identify() threw it; not reported as well", seen.isEmpty())
    }

    @Test
    fun aFailureIsReportedOnceNotAgainForTheNextFreshTokenRefused() = runBlocking {
        var calls = 0
        val (options, seen) = authFailures {
            when (++calls) {
                2 -> throw IOException("down")
                else -> jwt("user-a", nonce = "$calls")
            }
        }
        val kirora = start(options)
        kirora.identify()
        fake.batchResponder = { _, _ -> FakeKiroraApiService.error(401, "UNAUTHORIZED") }
        kirora.track("E")
        kirora.waitQueued(1)
        kirora.flush()
        waitUntilOnMain(message = "the failed fetch's report") { seen.isNotEmpty() }
        // A fresh token, refused: the same failure, until a success or identify().
        kirora.flush()
        delay(100)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(AuthFailureReason.FETCH_FAILED), seen.toList())
    }

    @Test
    fun aFetchedTokenForAnotherUserIsReportedAsRefused() = runBlocking {
        var calls = 0
        val (options, seen) = authFailures { if (++calls == 1) jwt("user-a") else jwt("user-b") }
        val kirora = start(options)
        kirora.identify()
        fake.batchResponder = { _, _ -> FakeKiroraApiService.error(401, "UNAUTHORIZED") }
        kirora.track("E")
        kirora.waitQueued(1)
        kirora.flush()
        waitUntilOnMain(message = "the report") { seen.isNotEmpty() }
        assertEquals(listOf(AuthFailureReason.REFUSED), seen.toList())
    }

    @Test
    fun anEarlyRefreshThatFailsIsRetriedNotReportedWhileTheTokenIsValid() = runBlocking {
        Kirora.minRefreshDelayMs = 10
        Kirora.refreshRetryMs = 10
        var calls = 0
        val (options, seen) = authFailures { calls++; throw IOException("down") }
        val kirora = start(options)
        // Due for its early refresh at once (within 60 seconds of exp), still valid for 50 seconds.
        kirora.identify(jwt("user-a", exp = System.currentTimeMillis() / 1000 + 50))
        waitUntil(message = "a few attempts") { calls >= 3 }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("retried, not reported", seen.isEmpty())
    }

    @Test
    fun aRefreshThatFailsOnceTheTokenHasExpiredIsReported() = runBlocking {
        Kirora.minRefreshDelayMs = 10
        val (options, seen) = authFailures { throw IOException("down") }
        val kirora = start(options)
        kirora.identify(jwt("user-a", exp = System.currentTimeMillis() / 1000 - 5))
        waitUntilOnMain(message = "the report") { seen.isNotEmpty() }
        assertEquals(listOf(AuthFailureReason.FETCH_FAILED), seen.toList())
    }
}
