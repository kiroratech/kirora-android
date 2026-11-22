package com.kirora.androidsdk

import com.kirora.androidsdk.model.KiroraOptions
import com.kirora.androidsdk.model.KiroraPushOptions
import com.kirora.androidsdk.net.FakeKiroraApiService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

/** "Device cleanup after sign-out" with no user token held: the registration's device secret (22 Nov). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class DeviceSecretTest : KiroraTestBase() {
    private val token = jwt("user-a")
    private val push = KiroraOptions(trackSessions = false, push = KiroraPushOptions())
    /** The secret the installation sent with its first registration. */
    private val secret: String
        get() = fake.deviceRegistrations.first().second.deviceSecret!!

    private val unavailable: suspend () -> Response<Unit> = { FakeKiroraApiService.error(503, "UNAVAILABLE") }

    /** A client with user-a registered, then a relaunch: nobody signed in yet. */
    private fun relaunchedAfterRegistering(clock: () -> Long = System::currentTimeMillis): Kirora = runBlocking {
        val first = start(push)
        first.identify(token)
        assertEquals(1, fake.deviceRegistrations.size)
        first.destroy()
        start(push, clearQueue = false, clock = clock)
    }

    @Test
    fun aSignOutBeforeIdentifyAfterARelaunchStillDetachesByTheSecret() = runBlocking {
        val kirora = relaunchedAfterRegistering()
        kirora.logout()
        assertEquals(kirora.deviceId to "Device $secret", fake.deviceDeletions.single())
        assertEquals("2026-11-16T10:00:00.000Z", fake.deletionLastSeenAt.single())
        // Dropped once answered: a second sign-out has nothing to detach.
        kirora.logout()
        assertEquals(1, fake.deviceDeletions.size)
    }

    @Test
    fun aDetachTheProcessDidntFinishIsRetriedAfterARestartWithTheSecret() = runBlocking {
        val first = start(push)
        first.identify(token)
        fake.deleteResponder = unavailable
        first.logout()
        assertEquals("first with the user's token", "Bearer $token", fake.deviceDeletions.single().second)
        first.destroy()

        fake.deleteResponder = null
        val restarted = start(push, clearQueue = false)
        waitUntil(message = "the retry after the restart") { fake.deviceDeletions.size == 2 }
        assertEquals(restarted.deviceId to "Device $secret", fake.deviceDeletions.last())
        restarted.destroy()

        // Done: a further restart doesn't send it again.
        start(push, clearQueue = false)
        kotlinx.coroutines.delay(300)
        assertEquals(2, fake.deviceDeletions.size)
    }

    @Test
    fun aPendingDetachIsDroppedAfter24HoursFromTheSignOut() = runBlocking {
        val first = start(push)
        first.identify(token)
        fake.deleteResponder = unavailable
        first.logout()
        first.destroy()

        fake.deleteResponder = null
        val dayLater = System.currentTimeMillis() + 24 * 60 * 60 * 1000L + 1
        start(push, clearQueue = false, clock = { dayLater })
        kotlinx.coroutines.delay(300)
        assertEquals("not retried past 24 hours", 1, fake.deviceDeletions.size)
    }

    @Test
    fun a409AlsoEndsThePendingDetach() = runBlocking {
        val first = start(push)
        first.identify(token)
        fake.deleteResponder = unavailable
        first.logout()
        first.destroy()

        fake.deleteResponder = { FakeKiroraApiService.error(409, "CONFLICT") }
        val second = start(push, clearQueue = false)
        waitUntil(message = "the retry") { fake.deviceDeletions.size == 2 }
        second.destroy()
        fake.deleteResponder = null
        start(push, clearQueue = false)
        kotlinx.coroutines.delay(300)
        assertEquals(2, fake.deviceDeletions.size)
    }

    @Test
    fun everyRegistrationSendsTheInstallationsOneSecretTheDeniedFormToo() = runBlocking {
        val first = start(push)
        first.identify(token)
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(false)
        first.identify(jwt("user-b"))
        assertEquals("denied", fake.deviceRegistrations.last().second.permission)
        val secrets = fake.deviceRegistrations.map { it.second.deviceSecret }
        assertEquals(2, secrets.size)
        assertEquals("one per installation, never rotated", 1, secrets.toSet().size)
        assertTrue("32 bytes, base64url: $secret", Regex("^[A-Za-z0-9_-]{43}$").matches(secret))
        // Kept after a detach is answered.
        first.logout()
        first.identify(token)
        assertEquals(secret, fake.deviceRegistrations.last().second.deviceSecret)
    }

    // ---- a 401 to the user's token ----------------------------------------------------------------

    @Test
    fun aRefusedUserTokenGoesOnAtOnceWithTheSecret() = runBlocking {
        val kirora = start(push)
        kirora.identify(token)
        // The token refused (expired, say); the secret accepted.
        fake.deleteResponder = {
            if (fake.deviceDeletions.last().second!!.startsWith("Bearer ")) FakeKiroraApiService.error(401, "UNAUTHORIZED")
            else Response.success(204, Unit)
        }
        kirora.logout()
        assertEquals(listOf("Bearer $token", "Device $secret"), fake.deviceDeletions.map { it.second })
    }

    @Test
    fun aRefusedSecretIsTheEnd() = runBlocking {
        val kirora = start(push)
        kirora.identify(token)
        fake.deleteResponder = { FakeKiroraApiService.error(401, "UNAUTHORIZED") }
        kirora.logout()
        kirora.destroy()
        assertEquals(2, fake.deviceDeletions.size)
        // Not resumed at the next launch either.
        fake.deleteResponder = null
        start(push, clearQueue = false)
        kotlinx.coroutines.delay(300)
        assertEquals(2, fake.deviceDeletions.size)
    }

    @Test
    fun turningPushOffWithNobodySignedInDetachesByTheSecret() = runBlocking {
        val kirora = relaunchedAfterRegistering()
        kirora.setPushEnabled(false)
        assertEquals(kirora.deviceId to "Device $secret", fake.deviceDeletions.single())
    }

    @Test
    fun aSignOutWithNobodySignedInLeavesAloneWhoeverSignsInMeanwhile() = runBlocking {
        val kirora = relaunchedAfterRegistering()
        // identify() during the sign-out's wait: by the time the detach captures the registration, C has it.
        kirora.identify(jwt("user-c"))
        kirora.pushManager!!.detachLastRegistration(deadline = null)
        assertTrue("C's registration is kept", fake.deviceDeletions.isEmpty())
    }

    @Test
    fun aSignOutWithNobodySignedInStillDetachesAnEarlierUsersRegistration() = runBlocking {
        val kirora = relaunchedAfterRegistering()
        // C signs in meanwhile, but their own registration hasn't happened: the record is still A's.
        fake.deviceResponder = { FakeKiroraApiService.error(503, "UNAVAILABLE") }
        kirora.identify(jwt("user-c"))
        kirora.pushManager!!.detachLastRegistration(deadline = null)
        assertEquals("A's registration goes", kirora.deviceId to "Device $secret", fake.deviceDeletions.single())
    }
}
