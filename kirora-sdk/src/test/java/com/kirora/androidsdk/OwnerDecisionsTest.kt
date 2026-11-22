package com.kirora.androidsdk

import com.kirora.androidsdk.model.KiroraApiError
import com.kirora.androidsdk.model.KiroraOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import com.kirora.androidsdk.model.KiroraPushOptions
import com.kirora.androidsdk.model.PushStatus
import com.kirora.androidsdk.push.PushDisplayConfig
import com.kirora.androidsdk.net.FakeKiroraApiService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The owner's decisions of 22 November on Android's open questions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class OwnerDecisionsTest : KiroraTestBase() {
    private val token = jwt("user-a")
    private val push = KiroraOptions(trackSessions = false, push = KiroraPushOptions())

    // ---- a refused token with no fetchUserToken pauses app opens ------------------------------

    @Test
    fun anAppOpenRefusedWith401WaitsForANewTokenInsteadOfRetrying() = runBlocking {
        val kirora = start(push)
        kirora.identify(token)
        fake.appOpenResponder = { FakeKiroraApiService.error(401, "UNAUTHORIZED") }
        kirora.appOpens!!.onAppOpen()
        waitUntil(message = "the refused attempt") { fake.appOpens.size == 1 }
        // Back online: the same token would only be refused again.
        kirora.appOpens!!.onNetworkAvailable()
        delay(200)
        assertEquals("not sent again with the refused token", 1, fake.appOpens.size)
        assertEquals("kept", 1, kirora.databaseForTesting!!.appOpenDao().count())

        fake.appOpenResponder = null
        kirora.identify(jwt("user-a", nonce = "new"))
        waitUntil(message = "sent with the new token") { fake.appOpens.size == 2 }
        waitUntil(message = "done") { kirora.databaseForTesting!!.appOpenDao().count() == 0 }
    }

    @Test
    fun aTokenRefusedForEventsAlsoHoldsAppOpens() = runBlocking {
        val kirora = start(push)
        kirora.identify(token)
        fake.batchResponder = { _, _ -> FakeKiroraApiService.error(401, "UNAUTHORIZED") }
        kirora.track("E")
        kirora.waitQueued(1)
        kirora.flush()
        kirora.appOpens!!.onAppOpen()
        delay(200)
        assertEquals("the token Kirora refused isn't tried again", 0, fake.appOpens.size)
    }

    // ---- Kirora's channel turned off counts as denied -------------------------------------------

    @Test
    fun aTurnedOffDefaultChannelIsDenied() = runBlocking {
        val kirora = start(push)
        kirora.identify(token)
        assertEquals(PushStatus.ENABLED, kirora.getPushStatus())
        val manager = context.getSystemService(NotificationManager::class.java)
        val config = PushDisplayConfig.of(KiroraPushOptions())
        manager.createNotificationChannel(NotificationChannel(config.channelId, config.channelName, NotificationManager.IMPORTANCE_NONE))
        assertEquals("only the system settings can fix it", PushStatus.DENIED, kirora.getPushStatus())
        // Another channel turned off is the app's own business.
        manager.deleteNotificationChannel(config.channelId)
        manager.createNotificationChannel(NotificationChannel("other", "Other", NotificationManager.IMPORTANCE_NONE))
        assertEquals(PushStatus.ENABLED, kirora.getPushStatus())
    }

    // ---- ...and push calls ------------------------------------------------------------------------

    /** user-a signed in and registered, then Kirora refuses their token (no fetchUserToken). */
    private suspend fun refusedAfterRegistering(): Kirora {
        val kirora = start(push)
        kirora.identify(token)
        assertEquals(1, fake.deviceRegistrations.size)
        fake.batchResponder = { _, _ -> FakeKiroraApiService.error(401, "UNAUTHORIZED") }
        kirora.track("E")
        kirora.waitQueued(1)
        kirora.flush()
        return kirora
    }

    @Test
    fun aRefusedTokenHoldsPushRegistrationUntilANewOne() = runBlocking {
        val kirora = refusedAfterRegistering()
        bridge.token = "fcm-2" // Firebase's current token, as onNewToken reports it
        kirora.onNewPushToken("fcm-2")
        delay(200)
        assertEquals("not registered with the refused token", 1, fake.deviceRegistrations.size)

        kirora.identify(jwt("user-a", nonce = "new"))
        waitUntil(message = "registered with the new token") { fake.deviceRegistrations.size == 2 }
    }

    @Test
    fun setPushConsentWithARefusedTokenFailsWithoutAsking() = runBlocking {
        val kirora = refusedAfterRegistering()
        val error = try {
            kirora.setPushConsent(true)
            null
        } catch (e: KiroraApiError) {
            e
        }
        assertEquals(401, error?.status)
        assertTrue("never sent", fake.pushConsentRequests.isEmpty())
    }

    @Test
    fun aRefusedUsersSignOutDetachesByTheSecretAtOnce() = runBlocking {
        val kirora = refusedAfterRegistering()
        kirora.logout()
        assertEquals("never the refused token", listOf("Device"), fake.deviceDeletions.map { it.second!!.substringBefore(' ') })
    }
}
