package com.kirora.androidsdk

import com.kirora.androidsdk.model.KiroraOptions
import com.kirora.androidsdk.net.FakeKiroraApiService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Findings from Codex's review of 22 November. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class CodexFindingsTest : KiroraTestBase() {
    private val sessions = KiroraOptions(clientKey = "kirora_pk_test", trackSessions = true)

    @Test
    fun whatIsTrackedRightAfterASignOutBeginsIsNotOnTheOutgoingVisit() = runBlocking {
        val kirora = start(sessions)
        kirora.identify(jwt("user-a"))
        kirora.track("A1")
        val visitOfA = kirora.sessionId
        kirora.logoutAsync()
        kirora.track("After")
        kirora.waitWaiting("After")
        val after = kirora.databaseForTesting!!.eventDao().unowned().single { it.name == "After" }
        assertNotEquals("a new visit", visitOfA, com.google.gson.JsonParser.parseString(after.contextJson).asJsonObject.get("sessionId")?.asString)
    }

    @Test
    fun anEventGivenBackByARefusedSignInStillKeepsTwoUsersVisitsApart() = runBlocking {
        val relaunched = relaunchedAfterAVisit(sessions)
        relaunched.track("Before sign-in") // continues A's stored visit
        relaunched.waitWaiting("Before sign-in")
        // A signs in, but Kirora refuses the token: the adopted event is given back.
        fake.batchResponder = { _, _ -> FakeKiroraApiService.error(401, "UNAUTHORIZED") }
        relaunched.identify(jwt("user-a"))
        relaunched.flush()
        relaunched.waitWaiting("Before sign-in") // given back

        fake.batchResponder = null
        relaunched.identify(jwt("user-b"))
        relaunched.flush()
        val sent = fake.sentEvent("Before sign-in")
        assertFalse("B never joins A's visit: ${sent.context}", sent.context.asJsonObject.has("sessionId"))
    }
}
