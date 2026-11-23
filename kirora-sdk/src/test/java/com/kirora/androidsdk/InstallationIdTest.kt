package com.kirora.androidsdk

import com.kirora.androidsdk.model.KiroraOptions
import com.kirora.androidsdk.model.KiroraPushOptions
import com.kirora.androidsdk.utils.GlobalPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Firebase's installation-ID mode: the device registers its installation ID, not an FCM token. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class InstallationIdTest : KiroraTestBase() {
    private val push = KiroraOptions(clientKey = "kirora_pk_test", trackSessions = false, push = KiroraPushOptions())

    private suspend fun registered(): Pair<String?, String?> {
        waitUntil(message = "a registration") { fake.deviceRegistrations.isNotEmpty() }
        val request = fake.deviceRegistrations.last().second
        return request.targetKind to request.target
    }

    @Test
    fun inTokenModeTheFcmTokenIsRegisteredAsBefore() = runBlocking {
        start(push).identify(jwt("user-a"))
        assertEquals("fcm_token" to "fcm-1", registered())
    }

    @Test
    fun inInstallationIdModeTheInstallationIdIsRegistered() = runBlocking {
        bridge.byInstallationId = true
        start(push).identify(jwt("user-a"))
        assertEquals("firebase_installation_id" to "fid-1", registered())
    }

    @Test
    fun aTokenKeptFromBeforeTheAppSwitchedModesIsNeverSentAsAnInstallationId() = runBlocking {
        // The previous version of the app registered by token.
        GlobalPreferences(context).setPushToken("fcm-old")
        bridge.byInstallationId = true
        bridge.installationId = null // Firebase can't answer yet
        start(push).identify(jwt("user-a"))
        kotlinx.coroutines.delay(200)
        assertEquals("nothing registered under the wrong kind", 0, fake.deviceRegistrations.size)

        // Firebase's onRegistered arrives: that is what registers.
        Kirora.handlePushRegistered(context, "fid-2")
        assertEquals("firebase_installation_id" to "fid-2", registered())
    }

    @Test
    fun aTokenForwardedInInstallationIdModeDoesntReplaceTheInstallationId() = runBlocking {
        bridge.byInstallationId = true
        start(push).identify(jwt("user-a"))
        assertEquals("firebase_installation_id" to "fid-1", registered())
        Kirora.handleNewPushToken(context, "fcm-forwarded")
        assertEquals("fid-1", GlobalPreferences(context).getPushToken(com.kirora.androidsdk.push.PushTarget.FIREBASE_INSTALLATION_ID))
    }

    @Test
    fun aKiroraPushAppAnsweringFcmTokenTakesTheInstallationIdToo() = runBlocking {
        bridge.byInstallationId = true
        fake.pushApp = fake.pushApp!!.copy(targetKind = "fcm_token")
        start(push).identify(jwt("user-a"))
        assertEquals("firebase_installation_id", registered().first)
    }
}
