package com.kirora.androidsdk

import com.kirora.androidsdk.model.KiroraOptions
import com.kirora.androidsdk.net.FakeKiroraApiService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `logout()` drops events waiting from before sign-in (gap 27, narrowed 22 Nov): with someone
 * signed in, all of them; with nobody, only those that continued a signed-in user's visit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class LogoutDropsUnboundTest : KiroraTestBase() {
    // Lifecycle events off: an app event the process lifecycle adds (Application Opened, say) isn't this test's.
    private val sessions = KiroraOptions(clientKey = "kirora_pk_test", trackSessions = true, trackAppLifecycle = false)

    @Test
    fun aRestoredSessionSignedOutBeforeIdentifyDropsWhatItsUserTracked() = runBlocking {
        // The app restored its own session after a relaunch, without identify(), then signs out.
        val kirora = relaunchedAfterAVisit(sessions)
        kirora.track("A's, before identify")
        kirora.waitQueued(1)
        kirora.logout()
        assertEquals(emptyList<String>(), kirora.waiting())

        kirora.track("After the sign-out")
        kirora.identify(jwt("user-b"))
        kirora.flush()
        val sent = fake.sentEventNames
        assertFalse(sent.contains("A's, before identify"))
        assertEquals("what's tracked after the sign-out is the next user's", "After the sign-out", sent.last())
    }

    @Test
    fun aVisitorWhoNeverSignedInKeepsTheirEvents() = runBlocking {
        val kirora = start(sessions)
        kirora.track("Viewed pricing")
        kirora.waitWaiting("Viewed pricing")
        kirora.logout() // an app calling it on an anonymous screen
        assertTrue("a sign-up funnel survives", "Viewed pricing" in kirora.waiting())
    }

    @Test
    fun withoutSessionsNothingTellsThemApartSoNothingIsDropped() = runBlocking {
        val kirora = start()
        kirora.track("Before sign-in")
        kirora.waitQueued(1)
        kirora.logout()
        assertEquals(1, kirora.queued())
    }

    @Test
    fun aSignedInUsersSignOutDropsEveryWaitingEvent() = runBlocking {
        val kirora = start()
        kirora.track("Before sign-in")
        kirora.waitQueued(1)
        // Kirora refuses the sign-in's token: what it adopted waits unbound again, though the user is signed in.
        fake.batchResponder = { _, _ -> FakeKiroraApiService.error(401, "UNAUTHORIZED") }
        kirora.identify(jwt("user-a"))
        kirora.flush()
        kirora.waitWaiting("Before sign-in") // given back
        kirora.logout()
        assertEquals(0, kirora.queued())
    }

    @Test
    fun aSignInStillFetchingItsTokenCountsAsSignedIn() = runBlocking {
        withSignInUnderWay { kirora ->
            kirora.track("Before sign-in") // a first-time user: no earlier visit marks it
            kirora.waitWaiting("Before sign-in")
            kirora.logout()
            assertEquals("adopted and signed out with it, not left for the next user", emptyList<String>(), kirora.waiting())
        }
    }
}
