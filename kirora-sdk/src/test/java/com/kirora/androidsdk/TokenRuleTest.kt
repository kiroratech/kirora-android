package com.kirora.androidsdk

import com.kirora.androidsdk.identity.Identity
import com.kirora.androidsdk.model.AuthFailureReason
import com.kirora.androidsdk.model.KiroraOptions
import com.kirora.androidsdk.net.FakeKiroraApiService
import com.kirora.androidsdk.utils.JwtClaims
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The owner's token rule of 22 November: refreshed tokens need `sub`; a clock behind counts lifetimes from now. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class TokenRuleTest : KiroraTestBase() {

    private val now = 1_800_000_000_000L
    private val nowS = now / 1000

    // ---- a refreshed token without sub ---------------------------------------------------------

    private fun refusedAfterRefreshTo(signIn: String, fresh: String): List<AuthFailureReason> = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<AuthFailureReason>())
        val kirora = start(KiroraOptions(clientKey = "kirora_pk_test", trackSessions = false, fetchUserToken = { fresh }, onAuthenticationFailed = { seen.add(it) }))
        kirora.identify(signIn)
        fake.batchResponder = { _, _ -> FakeKiroraApiService.error(401, "UNAUTHORIZED") }
        kirora.track("E")
        kirora.waitQueued(1)
        kirora.flush()
        waitUntilOnMain(message = "the report") { seen.isNotEmpty() }
        assertEquals("the refused token is kept, not replaced", signIn, kirora.identity.user?.token)
        seen.toList()
    }

    @Test
    fun aRefreshedTokenWithoutSubIsRefusedLikeAnotherUsersToken() {
        assertEquals(listOf(AuthFailureReason.REFUSED), refusedAfterRefreshTo(jwt("user-a"), jwt(null, nonce = "fresh")))
    }

    @Test
    fun aSignInWithoutSubIsNotRefreshedWithAnotherTokenWithoutSub() {
        // Both name no one: they can't be told to be the same user.
        assertEquals(listOf(AuthFailureReason.REFUSED), refusedAfterRefreshTo(jwt(null), jwt(null, nonce = "fresh")))
    }

    @Test
    fun theIdentityRefreshAnswersNoTokenForOneWithoutSub() = runBlocking {
        var fresh = jwt(null)
        val identity = Identity({ fresh }) { now }
        identity.signIn(jwt("user-a")) { _, _ -> }
        val user = identity.user!!
        assertNull(identity.refresh(user, user.token))
        fresh = jwt("user-a", nonce = "fresh")
        assertEquals(fresh, identity.refresh(user, user.token))
    }

    // ---- a device clock running behind -----------------------------------------------------------

    @Test
    fun aTokenIssuedWellAheadOfTheDeviceClockHasItsLifetimeCountedFromNow() {
        // The device is an hour behind: issued "in an hour", valid for 15 minutes.
        val token = jwt("a", iat = nowS + 3_600, exp = nowS + 3_600 + 900)
        assertEquals(now + 900_000, JwtClaims.expiresAtMillis(token, now))
    }

    @Test
    fun aTokenIssuedWithinTheLeewayKeepsItsExp() {
        val token = jwt("a", iat = nowS + 60, exp = nowS + 60 + 900)
        assertEquals((nowS + 960) * 1000, JwtClaims.expiresAtMillis(token, now))
    }

    @Test
    fun aTokenAlreadyExpiredOnArrivalHasItsLifetimeCountedFromNow() {
        assertEquals(now + 900_000, JwtClaims.expiresAtMillis(jwt("a", iat = nowS - 3_600, exp = nowS - 2_700), now))
        // Without iat its lifetime is unknown: no early refresh, as on web; a 401 replaces it.
        assertNull(JwtClaims.expiresAtMillis(jwt("a", exp = nowS - 2_700), now))
    }

    @Test
    fun theExpiryIsFixedWhenTheTokenArrivesNotEachTimeItIsRead() = runBlocking {
        var clock = now
        val fresh = jwt("user-a", nonce = "fresh", iat = nowS + 7_200, exp = nowS + 7_200 + 600)
        val identity = Identity({ fresh }) { clock }
        identity.signIn(jwt("user-a", iat = nowS + 3_600, exp = nowS + 3_600 + 900)) { _, _ -> }
        val user = identity.user!!
        clock += 120_000
        assertEquals("counted from sign-in, not from now", now + 900_000, user.current.second)
        identity.refresh(user, user.token)
        assertEquals("the refresh counts from its own arrival", clock + 600_000, user.current.second)
    }

    @Test
    fun theEarlyRefreshIsScheduledFromTheLifetimeNotTheFarAwayExp() = runBlocking {
        Kirora.minRefreshDelayMs = 10
        var fetched = 0
        val kirora = start(KiroraOptions(clientKey = "kirora_pk_test", trackSessions = false, fetchUserToken = { fetched++; jwt("user-a", nonce = "$fetched") }))
        val deviceNow = System.currentTimeMillis() / 1000
        // An hour ahead, valid for 50 seconds: within the minute before it runs out, so due now.
        kirora.identify(jwt("user-a", iat = deviceNow + 3_600, exp = deviceNow + 3_650))
        waitUntil(message = "the early refresh") { fetched >= 1 }
    }
}
