package com.kirora.androidsdk

import com.kirora.androidsdk.model.KiroraOptions
import com.kirora.androidsdk.model.KiroraPushOptions
import com.kirora.androidsdk.net.FakeKiroraApiService
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Findings of the second production audit (22 Nov). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class AuditRoundTwoTest : KiroraTestBase() {

    @Test
    fun flushCarriesAnEventStillBeingWritten() = runBlocking {
        val kirora = signedIn()
        val release = CountDownLatch(1)
        Kirora.holdQueueForTesting(release) // the write of the next event hasn't run yet
        kirora.track("Purchase")
        val flushing = async { kirora.flush() }
        delay(100)
        release.countDown()
        flushing.await()
        assertTrue("sent by this flush, not left for the timer", "Purchase" in fake.sentEventNames)
    }

    @Test
    fun aSignInAbandonedByLogoutNoLongerCountsAsUnderWay() = runBlocking {
        withSignInUnderWay(push = KiroraPushOptions()) { kirora ->
            kirora.logout()
            assertFalse(kirora.identity.signInUnderWay)
            // Calls that wait for a sign-in under way don't wait on the abandoned one.
            withTimeout(2_000) { kirora.getPushStatus() }
            // A second logout, after a visitor tracked something, keeps it (sessions off: nothing to tell apart).
            kirora.track("Later")
            kirora.waitWaiting("Later")
            kirora.logout()
            assertEquals(listOf("Later"), kirora.waiting())
        }
    }

    @Test
    fun theDetachIsCapturedWithoutWaitingBehindTheEventQueue() = runBlocking {
        val kirora = start(KiroraOptions(trackSessions = false, push = KiroraPushOptions()))
        kirora.identify(jwt("user-a"))
        val release = CountDownLatch(1)
        Kirora.holdQueueForTesting(release) // a slow queue: the sign-out's drop waits behind it
        val loggingOut = launch { kirora.logout() }
        try {
            waitUntil(message = "the detach, while the queue is still busy") { fake.deviceDeletions.size == 1 }
        } finally {
            release.countDown()
        }
        loggingOut.join()
    }

    @Test
    fun aTokenRefusedForPushHoldsTheManualFlushToo() = runBlocking {
        val kirora = start(KiroraOptions(trackSessions = false, push = KiroraPushOptions()))
        kirora.identify(jwt("user-a"))
        kirora.track("E")
        kirora.waitQueued(1)
        // Kirora refuses the token on a push call (no fetchUserToken): every request as them waits.
        fake.deviceResponder = { FakeKiroraApiService.error(401, "UNAUTHORIZED") }
        bridge.token = "fcm-2" // Firebase's current token, as onNewToken reports it
        kirora.onNewPushToken("fcm-2")
        waitUntil(message = "refused") { kirora.identity.user!!.waitingForToken }
        kirora.flush()
        assertTrue("not sent with the refused token", fake.batchRequests.isEmpty())
    }

    @Test
    fun aRefusalAboutEveryEventIsNotSentOneEventAtATime() = runBlocking {
        val kirora = signedIn()
        repeat(16) { kirora.track("E$it") }
        kirora.waitQueued(16)
        // A field every event carries is refused: splitting can isolate one, not fix the rest.
        fake.batchResponder = { _, _ ->
            FakeKiroraApiService.errorWithBody(422, """{"error":{"code":"VALIDATION_ERROR","message":"x","errors":[{"in":"body","field":"events.0.context.app","message":"x"}]}}""")
        }
        kirora.flush()
        // 16 → 8 → 4 → 2 → 1 (dropped), then the next batch splits again: stop.
        assertEquals(6, fake.batchRequests.size)
        assertEquals("the rest kept for later", 15, kirora.queued())
    }

    @Test
    fun theRequestIsSentAsLargeAsItWasMeasured() {
        val properties = com.google.gson.JsonObject().apply { addProperty("url", "https://shop.example/?a=1&b=<2>") }
        assertEquals(properties.toString(), com.kirora.androidsdk.net.KiroraApiClient.gson.toJson(properties))
    }
}
