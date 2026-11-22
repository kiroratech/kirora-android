package com.kirora.androidsdk

import com.google.gson.JsonObject
import com.kirora.androidsdk.model.KiroraOptions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The spec's "Visits" rule: an event tracked before sign-in continues the latest visit; if another
 * user then adopts it, its `sessionId` is removed, so two users' visits are never joined.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class VisitAdoptionTest : KiroraTestBase() {

    private val sessions = KiroraOptions(trackSessions = true)

    /** A's visit, a relaunch, an event before anyone signs in, then [next] signs in: that event's context, and A's visit. */
    private fun preSignInEventAfterRelaunchAdoptedBy(next: String): Pair<JsonObject, String> = runBlocking {
        val relaunched = relaunchedAfterAVisit(sessions)
        val visitOfA = relaunched.sessionId!! // continued from before the relaunch
        relaunched.track("Before sign-in")
        relaunched.waitQueued(1)
        relaunched.identify(jwt(next))
        relaunched.flush()
        val sent = fake.sentEvent("Before sign-in")
        sent.context.asJsonObject to visitOfA
    }

    @Test
    fun anotherUserAdoptingIt_dropsTheSessionId() {
        val (context, _) = preSignInEventAfterRelaunchAdoptedBy("user-b")
        assertFalse("B's events never join A's visit: $context", context.has("sessionId"))
    }

    @Test
    fun theSameUserAdoptingIt_keepsTheirVisit() {
        val (context, visitOfA) = preSignInEventAfterRelaunchAdoptedBy("user-a")
        assertEquals(visitOfA, context.get("sessionId")?.asString)
    }

    @Test
    fun aVisitBegunBeforeSignInIsTheirsWhoeverSignsIn() = runBlocking {
        val kirora = start(sessions)
        kirora.track("Before sign-in")
        kirora.waitWaiting("Before sign-in")
        val visit = kirora.sessionId!!
        kirora.identify(jwt("user-b"))
        kirora.flush()
        val sent = fake.sentEvent("Before sign-in")
        assertEquals("nobody's visit: nothing to keep apart", visit, sent.context.asJsonObject.get("sessionId")?.asString)
    }
}
