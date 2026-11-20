package com.kirora.androidsdk

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.kirora.androidsdk.db.EventEntity
import com.kirora.androidsdk.model.KiroraOptions
import com.kirora.androidsdk.net.EventStore
import com.kirora.androidsdk.net.FakeKiroraApiService
import com.kirora.androidsdk.net.KiroraBatchManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.util.Date
import java.util.UUID

/** "Event capture and delivery", "Redaction before storage" and the wire shape of a batch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class DeliveryConformanceTest : KiroraTestBase() {
    @Test
    fun theBatchBodyKeepsTheWireShape() = runBlocking {
        val kirora = signedIn()
        kirora.track("Order Completed", mapOf("amount" to 12500, "currency" to "NGN"))
        kirora.waitQueued(1)
        kirora.flush()

        val body = Gson().toJsonTree(fake.batchRequests.single()).asJsonObject
        assertEquals(setOf("events", "sentAt"), body.keySet())
        val event = body["events"].asJsonArray.single().asJsonObject
        assertEquals(setOf("idempotencyKey", "name", "properties", "context", "occurredAt"), event.keySet())
        assertEquals("Order Completed", event["name"].asString)
        assertEquals(12500, event["properties"].asJsonObject["amount"].asInt)
        assertTrue(event["context"].asJsonObject["device"].asJsonObject.has("id"))
        assertTrue(event["occurredAt"].asString.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""")))
    }

    @Test
    fun eachEventIsSettledByItsOwnResult() = runBlocking {
        val kirora = signedIn()
        fake.batchResponder = { request, _ ->
            FakeKiroraApiService.accepted(request, refuse = mapOf("Bad" to "VALIDATION_ERROR", "Later" to "RATE_LIMITED"))
        }
        listOf("Good", "Bad", "Later").forEach { kirora.track(it) }
        kirora.waitQueued(3)
        kirora.flush()
        // Accepted and refused-for-good are removed; a retryable refusal waits with its key.
        kirora.waitQueued(1)
        val kept = kirora.databaseForTesting!!.eventDao().oldestFor("user-a", 10).single()
        assertEquals("Later", kept.name)
        assertEquals(fake.batchRequests.single().events.first { it.name == "Later" }.idempotencyKey, kept.idempotencyKey)
    }

    @Test
    fun aSuccessfulAnswerWithoutResultsAcknowledgesNothing() = runBlocking {
        val kirora = signedIn()
        fake.batchResponder = { request, _ ->
            val unknown = JsonObject().apply {
                add("data", JsonObject().apply {
                    add("results", Gson().toJsonTree(listOf(mapOf("idempotencyKey" to UUID.randomUUID().toString(), "replayed" to false, "event" to mapOf("id" to "x")))))
                })
            }
            if (request.events.isEmpty()) FakeKiroraApiService.accepted(request) else Response.success<JsonElement>(unknown)
        }
        kirora.track("Unanswered")
        kirora.waitQueued(1)
        kirora.flush()
        delay(100)
        kirora.waitQueued(1)
    }

    @Test
    fun aWhole413IsSplitUntilTheOversizedEventStandsAlone() = runBlocking {
        val kirora = signedIn()
        fake.batchResponder = { request, _ ->
            if (request.events.any { it.name == "Huge" }) FakeKiroraApiService.error(413, "PAYLOAD_TOO_LARGE")
            else FakeKiroraApiService.accepted(request)
        }
        listOf("One", "Two", "Huge", "Three", "Four").forEach { kirora.track(it) }
        kirora.waitQueued(5)
        kirora.flush()
        waitUntil(message = "the rest delivered") {
            fake.batchRequests.filter { r -> r.events.none { it.name == "Huge" } }
                .flatMap { r -> r.events.map { it.name } }.toSet() == setOf("One", "Two", "Three", "Four")
        }
        kirora.waitQueued(0)
    }

    @Test
    fun retryAfterHoldsEvenAManualFlush() = runBlocking {
        val kirora = signedIn()
        fake.batchResponder = { _, _ -> FakeKiroraApiService.error(429, "RATE_LIMITED", retryAfterSeconds = 120) }
        kirora.track("Throttled")
        kirora.waitQueued(1)
        kirora.flush()
        assertEquals(1, fake.batchRequests.size)
        kirora.flush()
        assertEquals(1, fake.batchRequests.size)
        kirora.waitQueued(1)
    }

    @Test
    fun aPlanLimitRefusalDropsOnlyThoseEvents() = runBlocking {
        val kirora = signedIn()
        fake.batchResponder = { request, _ -> FakeKiroraApiService.accepted(request, refuse = mapOf("Over" to "PLAN_LIMIT_REACHED")) }
        kirora.track("Within")
        kirora.track("Over")
        kirora.waitQueued(2)
        kirora.flush()
        kirora.waitQueued(0)
        assertEquals(1, kirora.droppedEventCount.toInt())
    }

    @Test
    fun propertiesAreCopiedAtCaptureAndMustBeJson() = runBlocking {
        val kirora = signedIn()
        val mutable = mutableMapOf<String, Any?>("step" to 1, "at" to Date(0), "missing" to null)
        kirora.track("Copied", mutable)
        mutable["step"] = 2
        kirora.track("NaN", mapOf("n" to Double.NaN))
        kirora.track("Object", mapOf("o" to Any()))
        val cyclic = mutableMapOf<String, Any?>()
        cyclic["self"] = cyclic
        kirora.track("Cycle", cyclic)
        kirora.track("Valid after")
        kirora.waitQueued(2)
        kirora.flush()

        val sent = fake.batchRequests.flatMap { it.events }
        assertEquals(listOf("Copied", "Valid after"), sent.map { it.name })
        val props = sent.first().properties.asJsonObject
        assertEquals(1, props["step"].asInt)
        assertEquals("1970-01-01T00:00:00.000Z", props["at"].asString)
        assertTrue(props["missing"].isJsonNull)
    }

    @Test
    fun beforeSendRedactsBeforeStorageAndCanDrop() = runBlocking {
        val kirora = signedIn(KiroraOptions(trackSessions = false, beforeSend = { event ->
            when (event.name) {
                "Drop" -> null
                "Throw" -> throw IllegalStateException("boom")
                "Recurse" -> {
                    Kirora.active?.track("From the hook")
                    event
                }
                else -> event.copy(properties = event.properties - "email")
            }
        }))
        kirora.track("Signed Up", mapOf("email" to "a@example.com", "plan" to "pro"))
        kirora.track("Drop")
        kirora.track("Throw")
        kirora.track("Recurse")
        kirora.waitQueued(2)

        val stored = kirora.databaseForTesting!!.eventDao().oldestFor("user-a", 10)
        assertEquals(listOf("Signed Up", "Recurse"), stored.map { it.name })
        assertFalse(stored.first().propertiesJson.contains("a@example.com"))

        kirora.flush()
        assertFalse(fake.sentEventNames.contains("From the hook"))
    }

    @Test
    fun theQueueKeepsItsCountAndByteBudgets() = runBlocking {
        val store = EventStore(null) // the in-memory fallback has the same budgets
        fun event(size: Int) = EventEntity(
            idempotencyKey = UUID.randomUUID().toString(), name = "e", propertiesJson = "{}", contextJson = "{}",
            occurredAt = 0, owner = "u", sizeBytes = size
        )
        repeat(3) { store.add(event(400_000)) }
        assertEquals(2, store.count())
        repeat(EventStore.MAX_EVENTS + 5) { store.add(event(10)) }
        assertEquals(EventStore.MAX_EVENTS, store.count())
    }

    @Test
    fun aBatchFitsTheByteTargetButAlwaysCarriesOneEvent() {
        fun event(size: Int) = EventEntity(
            idempotencyKey = UUID.randomUUID().toString(), name = "e", propertiesJson = "{}", contextJson = "{}",
            occurredAt = 0, owner = "u", sizeBytes = size
        )
        assertEquals(2, KiroraBatchManager.fitBatch(List(5) { event(90_000) }).size)
        assertEquals(1, KiroraBatchManager.fitBatch(listOf(event(500_000), event(10))).size)
    }

    @Test
    fun destroyStopsTrackingAndKeepsWhatWaits() = runBlocking {
        val kirora = signedIn()
        fake.batchResponder = { _, _ -> FakeKiroraApiService.error(503) }
        kirora.track("Waits")
        kirora.waitQueued(1)
        kirora.destroy()
        kirora.track("After destroy")
        kirora.flush()
        delay(100)
        val next = start(clearQueue = false)
        next.waitQueued(1)
        assertNull(Kirora.active?.takeIf { it === kirora })
    }

    @Test
    fun screenViewsCarryOnlyTheirName() = runBlocking {
        val kirora = signedIn()
        kirora.trackScreenView("Checkout")
        kirora.waitQueued(1)
        kirora.flush()
        val event = fake.batchRequests.single().events.single()
        assertEquals("Screen Viewed", event.name)
        assertEquals(setOf("name"), event.properties.asJsonObject.keySet())
    }
}
