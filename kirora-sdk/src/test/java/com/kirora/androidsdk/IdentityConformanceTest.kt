package com.kirora.androidsdk

import com.kirora.androidsdk.model.KiroraApiError
import com.kirora.androidsdk.model.KiroraOptions
import com.kirora.androidsdk.model.KiroraPushOptions
import com.kirora.androidsdk.model.UserTokenError
import com.kirora.androidsdk.net.FakeKiroraApiService
import com.kirora.androidsdk.utils.GlobalPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Sign-in, ownership of queued events, and sign-out ("Identity transitions and queued ownership"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class IdentityConformanceTest : KiroraTestBase() {
    private val tokenA = jwt("user-a")
    private val tokenB = jwt("user-b")

    @Test
    fun eventsTrackedBeforeSignInWaitAndGoAsTheFirstUserToSignIn() = runBlocking {
        val kirora = start()
        kirora.track("Before")
        kirora.waitQueued(1)
        kirora.flush()
        assertTrue("nothing is sent anonymously", fake.batchRequests.isEmpty())

        kirora.identify(tokenA)
        waitUntil(message = "the adopted event is sent") { fake.sentEventNames == listOf("Before") }
        assertEquals("Bearer $tokenA", fake.batchAuthorizations.single())
        kirora.waitQueued(0)
    }

    @Test
    fun aDirectSwitchNeverSendsTheFirstUsersEventsAsTheSecond() = runBlocking {
        val kirora = start()
        kirora.identify(tokenA)
        fake.batchResponder = { _, _ -> FakeKiroraApiService.error(503) }
        kirora.track("A's")
        kirora.waitQueued(1)
        kirora.flush()

        fake.batchResponder = null
        kirora.identify(tokenB)
        kirora.track("B's")
        kirora.waitQueued(2)
        kirora.flush() // past the SDK's own backoff from A's failure
        waitUntil(message = "B's event is sent") { "B's" in fake.sentEventNames }
        fake.batchRequests.zip(fake.batchAuthorizations).forEach { (request, auth) ->
            if (auth == "Bearer $tokenB") assertFalse(request.events.any { it.name == "A's" })
        }
        // A's event waits, still A's, for A to sign in again.
        kirora.waitQueued(1)
        kirora.identify(tokenA)
        kirora.flush()
        kirora.waitQueued(0)
        assertEquals("Bearer $tokenA", fake.batchAuthorizations.last())
    }

    @Test
    fun aRestartRestoresOwnedEventsWithoutSendingThemAsAnotherUser() = runBlocking {
        val first = start()
        first.identify(tokenA)
        fake.batchResponder = { _, _ -> FakeKiroraApiService.error(503) }
        first.track("A's")
        first.waitQueued(1)
        first.destroy()

        fake.batchResponder = null
        val second = start(clearQueue = false)
        second.identify(tokenB)
        second.flush()
        assertFalse("A's" in fake.sentEventNames)
        second.waitQueued(1)
    }

    @Test
    fun aRefusedSignInGivesBackTheEventsItAdopted() = runBlocking {
        val kirora = start()
        kirora.track("Before")
        kirora.waitQueued(1)
        fake.batchResponder = { _, _ -> FakeKiroraApiService.error(401, "UNAUTHORIZED") }
        kirora.identify(tokenA)
        waitUntil(message = "the refused batch") { fake.batchRequests.isNotEmpty() }
        delay(200)

        fake.batchResponder = null
        kirora.identify(tokenB)
        kirora.flush()
        waitUntil(message = "B sends the released event") { fake.batchAuthorizations.lastOrNull() == "Bearer $tokenB" }
        assertTrue("Before" in fake.batchRequests.last().events.map { it.name })
    }

    @Test
    fun logoutSendsAndDetachesWithTheOutgoingTokenThenDropsWhatIsLeft() = runBlocking {
        GlobalPreferences(context).setPushToken("fcm-1")
        val kirora = start(KiroraOptions(trackSessions = false, push = KiroraPushOptions()))
        kirora.identify(tokenA)
        assertEquals(1, fake.deviceRegistrations.size)
        kirora.track("Sent at logout")
        kirora.waitQueued(1)

        kirora.logout()

        assertTrue("Sent at logout" in fake.sentEventNames)
        assertEquals(kirora.deviceId to "Bearer $tokenA", fake.deviceDeletions.single())
        assertFalse(kirora.isIdentified)
        kirora.waitQueued(0)
        kirora.logout() // safe twice
        assertEquals(1, fake.deviceDeletions.size)
    }

    @Test
    fun logoutStaysWithinItsDeadlineWhenTheFlushHangs() = runBlocking {
        val kirora = start()
        kirora.identify(tokenA)
        fake.batchResponder = { request, _ ->
            delay(60_000)
            FakeKiroraApiService.accepted(request)
        }
        kirora.track("Hangs")
        kirora.waitQueued(1)

        val started = System.currentTimeMillis()
        kirora.logout()
        assertTrue(System.currentTimeMillis() - started < 6_000)
        assertFalse(kirora.isIdentified)
        kirora.waitQueued(0)
    }

    @Test
    fun logoutStaysWithinItsDeadlineWhenTheTokenFetchHangs() = runBlocking {
        val kirora = start(KiroraOptions(trackSessions = false, fetchUserToken = { delay(60_000); tokenA }))
        kirora.identifyAsync()
        val started = System.currentTimeMillis()
        kirora.logout()
        assertTrue(System.currentTimeMillis() - started < 6_000)
        assertFalse(kirora.isIdentified)
    }

    @Test
    fun anOfflineDetachKeepsRetryingWhateverRegistersNext() = runBlocking {
        GlobalPreferences(context).setPushToken("fcm-1")
        val kirora = start(KiroraOptions(trackSessions = false, push = KiroraPushOptions()))
        kirora.identify(tokenA)
        fake.deleteResponder = { FakeKiroraApiService.error(503) }
        kirora.logout()
        assertEquals(1, kirora.pushManager!!.pendingCleanups)

        // Another user's registration doesn't end it: the server scopes it to A, and its lastSeenAt
        // means it can't undo a newer registration.
        kirora.identify(tokenB)
        assertEquals(2, fake.deviceRegistrations.size)
        assertEquals(1, kirora.pushManager!!.pendingCleanups)
    }

    @Test
    fun everyDetachNamesTheLastRegistrationAndA409EndsIt() = runBlocking {
        GlobalPreferences(context).setPushToken("fcm-1")
        val kirora = start(KiroraOptions(trackSessions = false, push = KiroraPushOptions()))
        kirora.identify(tokenA)
        // A signs out, but the device was registered again since (another process): Kirora keeps it.
        fake.deleteResponder = { FakeKiroraApiService.error(409, "CONFLICT") }
        kirora.logout()
        assertEquals("2026-11-16T10:00:00.000Z", fake.deletionLastSeenAt.single())
        assertEquals(0, kirora.pushManager!!.pendingCleanups)

        // setPushEnabled(false) sends it too.
        fake.deleteResponder = null
        kirora.identify(tokenA)
        kirora.setPushEnabled(false)
        assertEquals(listOf("2026-11-16T10:00:00.000Z", "2026-11-16T10:00:00.000Z"), fake.deletionLastSeenAt.toList())
    }

    @Test
    fun aRefusedDetachIsNotRetried() = runBlocking {
        GlobalPreferences(context).setPushToken("fcm-1")
        val kirora = start(KiroraOptions(trackSessions = false, push = KiroraPushOptions()))
        kirora.identify(tokenA)
        fake.deleteResponder = { FakeKiroraApiService.error(401, "UNAUTHORIZED") }
        kirora.logout()
        assertEquals(0, kirora.pushManager!!.pendingCleanups)
    }

    @Test
    fun identifyThrowsOnlyForTheToken() = runBlocking {
        val kirora = start(KiroraOptions(trackSessions = false, fetchUserToken = { throw IllegalStateException("down") }))
        try {
            kirora.identify()
            fail("expected UserTokenError")
        } catch (_: UserTokenError) {
        }
        assertFalse(kirora.isIdentified)

        // A push registration that fails for another reason is only logged.
        GlobalPreferences(context).setPushToken("fcm-1")
        val withPush = start(KiroraOptions(trackSessions = false, push = KiroraPushOptions()))
        fake.deviceResponder = { FakeKiroraApiService.error(500) }
        withPush.identify(tokenA)
        assertTrue(withPush.isIdentified)

        fake.deviceResponder = { FakeKiroraApiService.error(401, "UNAUTHORIZED") }
        try {
            withPush.identify(jwt("user-c"))
            fail("expected KiroraApiError")
        } catch (e: KiroraApiError) {
            assertEquals(401, e.status)
        }
    }

    @Test
    fun aTokenFetchedForAnEarlierSignInIsDiscarded() = runBlocking {
        var calls = 0
        val kirora = start(KiroraOptions(trackSessions = false, fetchUserToken = {
            calls++
            if (calls == 1) {
                delay(500)
                tokenA
            } else {
                tokenB
            }
        }))
        kirora.identifyAsync()
        delay(50)
        kirora.identify()
        delay(700)
        kirora.track("After")
        kirora.waitQueued(1)
        kirora.flush()
        waitUntil(message = "sent") { fake.batchAuthorizations.isNotEmpty() }
        assertTrue(fake.batchAuthorizations.all { it == "Bearer $tokenB" })
    }

    @Test
    fun aReplacementInstanceKeepsTheSignedInUser() = runBlocking {
        val first = start()
        first.identify(tokenA)
        val second = start(clearQueue = false)
        assertTrue(second.isIdentified)
        assertFalse(first.isInitialized)
    }

    @Test
    fun identifyingTheSameUserAgainMidSendPostsEachEventOnce() = runBlocking {
        val kirora = start()
        kirora.identify(tokenA)
        val release = CompletableDeferred<Unit>()
        fake.batchResponder = { request, _ ->
            if (fake.batchRequests.size == 1) release.await()
            FakeKiroraApiService.accepted(request)
        }
        kirora.track("First")
        kirora.waitQueued(1)
        val firstFlush = launch(Dispatchers.Default) { kirora.flush() }
        waitUntil(message = "the first batch on its way") { fake.batchRequests.size == 1 }

        // Same user, new token, while that batch waits for its answer.
        kirora.identify(jwt("user-a", nonce = "refreshed"))
        kirora.track("Second")
        kirora.waitQueued(2)
        val secondFlush = launch(Dispatchers.Default) { kirora.flush() }
        delay(200)
        release.complete(Unit)
        firstFlush.join()
        secondFlush.join()
        kirora.waitQueued(0)

        assertEquals(2, fake.batchRequests.size)
        assertEquals(listOf("First", "Second"), fake.sentEventNames)
    }
}
