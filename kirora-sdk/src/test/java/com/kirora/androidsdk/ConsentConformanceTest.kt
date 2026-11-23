package com.kirora.androidsdk

import com.kirora.androidsdk.model.KiroraOptions
import com.kirora.androidsdk.model.KiroraPushOptions
import com.kirora.androidsdk.net.FakeKiroraApiService
import com.kirora.androidsdk.push.KiroraPushTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Tracking consent and operational push". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class ConsentConformanceTest : KiroraTestBase() {
    private val token = jwt("user-a")

    @Test
    fun aRefusalStopsCollectionEvenWithoutRequiredConsentAndIsRemembered() = runBlocking {
        val kirora = start(KiroraOptions(clientKey = "kirora_pk_test", trackSessions = true))
        kirora.track("Before")
        waitUntil(message = "the event queued") { kirora.queued() >= 1 }
        kirora.setTrackingConsent(false)
        assertFalse(kirora.trackingConsent)
        kirora.waitQueued(0)
        assertEquals(null, kirora.sessionId)

        kirora.track("After refusal")
        delay(100)
        kirora.waitQueued(0)

        // A remembered refusal always wins, in the next client too.
        val next = start(KiroraOptions(clientKey = "kirora_pk_test", trackSessions = true), clearQueue = false)
        next.track("Next start")
        delay(100)
        next.waitQueued(0)
        assertFalse(next.trackingConsent)
    }

    @Test
    fun withRequiredConsentNothingIsStoredUntilGrantedAndThenFromThatPointOnly() = runBlocking {
        val kirora = start(KiroraOptions(clientKey = "kirora_pk_test", trackSessions = true, requireTrackingConsent = true))
        kirora.track("Too early")
        delay(100)
        kirora.waitQueued(0)
        assertEquals(null, kirora.sessionId)

        kirora.setTrackingConsent(true)
        kirora.track("Allowed")
        kirora.waitQueued(2) // the visit begins now, then the event
    }

    @Test
    fun withdrawalCancelsARetryAlreadyScheduled() = runBlocking {
        val kirora = start()
        kirora.identify(token)
        fake.batchResponder = { _, _ -> FakeKiroraApiService.error(503) }
        kirora.track("Retried")
        kirora.waitQueued(1)
        kirora.flush()
        val attempts = fake.batchRequests.size
        kirora.setTrackingConsent(false)
        fake.batchResponder = null
        kirora.flush()
        delay(100)
        assertEquals(attempts, fake.batchRequests.size)
        kirora.waitQueued(0)
    }

    @Test
    fun operationalPushContinuesWhenTrackingIsRefused() = runBlocking {
        val kirora = start(KiroraOptions(clientKey = "kirora_pk_test", trackSessions = false, push = KiroraPushOptions()))
        kirora.setTrackingConsent(false)
        kirora.identify(token)
        // Registration and its device id are operational: still allowed.
        assertEquals(1, fake.deviceRegistrations.size)
        assertNotNull(kirora.deviceId)

        KiroraPushTracker.report(context, kirora.options.apiUrl, "/t/p/tap", KiroraPushTracker.OPENED, fake)
        waitUntil(message = "the tap report") { fake.trackingReports.size == 1 }

        // Cleanup is always allowed.
        kirora.logout()
        assertEquals(1, fake.deviceDeletions.size)
    }

    @Test
    fun withdrawalForgetsTheDeviceIdUnlessPushStillNeedsIt() = runBlocking {
        val kirora = start()
        kirora.track("Gives the device an id")
        kirora.waitQueued(1)
        val first = kirora.deviceId
        assertNotNull(first)
        kirora.setTrackingConsent(false)
        assertNotEquals(first, kirora.deviceId)
    }

    @Test
    fun marketingConsentIsSeparateFromTrackingConsent() = runBlocking {
        val kirora = start(KiroraOptions(clientKey = "kirora_pk_test", trackSessions = false, push = KiroraPushOptions(), requireTrackingConsent = true))
        kirora.identify(token)
        kirora.setPushConsent(true)
        assertFalse(kirora.trackingConsent)
        assertTrue(fake.pushConsentRequests.single().granted)
        assertTrue(kirora.pushConsent)
    }
}
