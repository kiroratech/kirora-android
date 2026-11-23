package com.kirora.androidsdk

import com.kirora.androidsdk.model.KiroraOptions
import com.kirora.androidsdk.model.KiroraPushOptions
import com.kirora.androidsdk.net.FakeKiroraApiService
import com.kirora.androidsdk.utils.GlobalPreferences
import com.kirora.androidsdk.utils.KiroraNamespace
import com.kirora.androidsdk.model.KiroraApiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `getState()`: a read-only snapshot that never fails, prompts or calls the network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class StateTest : KiroraTestBase() {
    private val pushOptions = KiroraOptions(clientKey = "kirora_pk_test", trackSessions = false, push = KiroraPushOptions())

    @Test
    fun aFreshClientAnswersWithoutCreatingAnything() = runBlocking {
        GlobalPreferences(context).setPushToken("fcm-1")
        val kirora = start(pushOptions)
        val state = kirora.getState()
        assertTrue(state.isInitialized)
        assertFalse(state.isIdentified)
        assertNull("reading the state never creates a device id", state.deviceId)
        assertNull(kirora.deviceId)
        assertNull("Firebase holds a token, but nothing is registered", state.pushToken)
        assertTrue(state.trackingConsent)
        assertFalse(state.pushConsent)
        assertEquals(0, state.queuedEventCount)
        assertTrue(fake.batchRequests.isEmpty() && fake.deviceRegistrations.isEmpty())
    }

    @Test
    fun itReflectsTheSignedInUserAndTheQueue() = runBlocking {
        GlobalPreferences(context).setPushToken("fcm-1")
        val kirora = start(pushOptions)
        kirora.identify(jwt("user-a"))
        fake.batchResponder = { _, _ -> FakeKiroraApiService.error(503) }
        kirora.track("Waiting")
        kirora.waitQueued(1)

        val requestsBefore = fake.batchRequests.size + fake.deviceRegistrations.size
        val state = kirora.getState()
        assertEquals(requestsBefore, fake.batchRequests.size + fake.deviceRegistrations.size)
        assertTrue(state.isIdentified)
        assertNotNull(state.deviceId)
        assertEquals(state.deviceId, fake.deviceRegistrations.single().first)
        assertEquals("fcm-1", state.pushToken)
        assertEquals(1, state.queuedEventCount)

        kirora.setTrackingConsent(false)
        kirora.waitQueued(0) // withdrawal clears the queue in the background
        val refused = kirora.getState()
        assertFalse(refused.trackingConsent)
        assertEquals(0, refused.queuedEventCount)
        assertNull(refused.sessionId)
    }

    @Test
    fun pushTokenIsTheCurrentRegistrationsOnly() = runBlocking {
        GlobalPreferences(context).setPushToken("fcm-1")
        val kirora = start(pushOptions)
        kirora.identify(jwt("user-a"))
        assertEquals("fcm-1", kirora.getState().pushToken)

        kirora.setPushEnabled(false)
        assertNull("turned off", kirora.getState().pushToken)
        kirora.setPushEnabled(true)
        assertEquals("fcm-1", kirora.getState().pushToken)

        kirora.logout()
        assertNull("signed out", kirora.getState().pushToken)

        fake.deviceResponder = { FakeKiroraApiService.error(500) }
        kirora.identify(jwt("user-b"))
        assertNull("the registration failed", kirora.getState().pushToken)
    }

    @Test
    fun pushConsentIsRecordedOnlyOnceKiroraAcceptsItAndIsTheUsers() = runBlocking {
        val kirora = start(pushOptions)
        kirora.identify(jwt("user-a"))
        kirora.setPushConsent(true)
        assertTrue(kirora.pushConsent)

        fake.consentResponder = { FakeKiroraApiService.error(500) }
        try {
            kirora.setPushConsent(false)
            fail("expected KiroraApiError")
        } catch (e: KiroraApiError) {
            assertEquals(500, e.status)
        }
        assertTrue("a refused call leaves the previous value", kirora.getState().pushConsent)

        fake.consentResponder = null
        kirora.identify(jwt("user-b"))
        assertFalse("the consent is A's", kirora.getState().pushConsent)

        kirora.identify(jwt("user-a"))
        kirora.logout()
        assertFalse(kirora.getState().pushConsent)
    }

    @Test
    fun aConsentAcceptedAfterTheUserChangedIsNotRecordedForTheNextOne() = runBlocking {
        val kirora = start(pushOptions)
        kirora.identify(jwt("user-a"))
        fake.consentResponder = {
            Thread.sleep(300)
            retrofit2.Response.success(204, Unit)
        }
        val call = launch(Dispatchers.IO) { kirora.setPushConsent(true) }
        waitUntil(message = "sent") { fake.pushConsentRequests.isNotEmpty() }
        kirora.identify(jwt("user-b"))
        call.join()
        assertFalse(kirora.getState().pushConsent)
    }

    @Test
    fun aUsersConsentDoesNotSurviveTheirLogout() = runBlocking {
        val kirora = start(pushOptions)
        kirora.identify(jwt("user-a"))
        kirora.setPushConsent(true)
        assertTrue(kirora.pushConsent)
        kirora.logout()
        kirora.identify(jwt("user-a"))
        assertFalse(kirora.getState().pushConsent)
        val stored = context.getSharedPreferences("kirora_" + KiroraNamespace.of(kirora.options.apiUrl, kirora.options.clientKey), 0).all.keys
        assertFalse("nothing is stored", stored.any { it.startsWith("push_consent") })
    }

    @Test
    fun anotherUsersRecordedConsentIsForgottenAtSignInAfterARestart() = runBlocking {
        val first = start(pushOptions)
        first.identify(jwt("user-a"))
        first.setPushConsent(true)
        first.destroy()
        val second = start(pushOptions, clearQueue = false)
        second.identify(jwt("user-b"))
        assertFalse(second.getState().pushConsent)
        val stored = context.getSharedPreferences("kirora_" + KiroraNamespace.of(second.options.apiUrl, second.options.clientKey), 0).all.keys
        assertFalse("forgotten, not just hidden", stored.any { it.startsWith("push_consent") })
        second.identify(jwt("user-a"))
        assertFalse(second.getState().pushConsent)
    }

    @Test
    fun aDestroyedClientStillAnswers() = runBlocking {
        val kirora = start()
        kirora.identify(jwt("user-a"))
        kirora.destroy()
        val state = kirora.getState()
        assertFalse(state.isInitialized)
        assertFalse(state.isIdentified)
    }
}
