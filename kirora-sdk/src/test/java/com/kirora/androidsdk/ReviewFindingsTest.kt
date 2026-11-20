package com.kirora.androidsdk

import androidx.room.Room
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.kirora.androidsdk.db.EventDao
import com.kirora.androidsdk.db.EventEntity
import com.kirora.androidsdk.db.KiroraDatabase
import com.kirora.androidsdk.db.QueueTotals
import com.kirora.androidsdk.model.KiroraOptions
import com.kirora.androidsdk.model.KiroraPushOptions
import com.kirora.androidsdk.net.EventStore
import com.kirora.androidsdk.net.FakeKiroraApiService
import com.kirora.androidsdk.net.PendingPurges
import com.kirora.androidsdk.net.models.DataEnvelope
import com.kirora.androidsdk.net.models.DeviceAnswer
import com.kirora.androidsdk.utils.GlobalPreferences
import com.kirora.androidsdk.utils.KiroraNamespace
import com.kirora.androidsdk.utils.KiroraPreferences
import com.kirora.androidsdk.utils.RetryAfter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Headers.Companion.headersOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch

/** Regression tests for event context, logout, storage failures, retries and client replacement. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class ReviewFindingsTest : KiroraTestBase() {
    private val tokenA = jwt("user-a")
    private val tokenB = jwt("user-b")
    private val withPush = KiroraOptions(trackSessions = false, push = KiroraPushOptions())
    private val defaultLocale: Locale = Locale.getDefault()
    private var db: KiroraDatabase? = null

    @After
    fun restore() {
        Locale.setDefault(defaultLocale)
        db?.close()
    }

    // ---- event context (the server's schema of 17 November, mobile fields included) ------------------------------------------------------------------

    @Test
    fun eventContextHoldsOnlyFieldsTheServerAccepts() = runBlocking {
        val kirora = start(KiroraOptions(trackSessions = true))
        kirora.identify(tokenA)
        kirora.track("Checked")
        waitUntil(message = "sent") {
            kirora.flush()
            "Checked" in fake.sentEventNames
        }
        assertTrue("a session id is part of it", fake.batchRequests.flatMap { it.events }.any { it.context.asJsonObject.has("sessionId") })
        for (event in fake.batchRequests.flatMap { it.events }) {
            val context = event.context.asJsonObject
            // The server's eventContextSchema: strict, at the top level and in `device`.
            val allowed = setOf("sessionId", "locale", "timezone", "device", "app", "os", "screen", "library")
            assertTrue(context.keySet().toString(), allowed.containsAll(context.keySet()))
            assertFalse("no network details", context.has("network"))
            val device = context.getAsJsonObject("device")
            assertTrue(device.keySet().toString(), setOf("id", "manufacturer", "type").containsAll(device.keySet()))
            assertFalse("no device model: it helps fingerprint", device.has("model"))
            assertEquals("android", device.get("type").asString)
            assertEquals("Android", context.getAsJsonObject("os").get("name").asString)
            assertTrue(setOf("name", "version", "build", "namespace").containsAll(context.getAsJsonObject("app").keySet()))
            context.getAsJsonObject("screen")?.let { screen ->
                assertTrue(setOf("width", "height", "density").containsAll(screen.keySet()))
            }
            assertEquals(setOf("name", "version"), context.getAsJsonObject("library").keySet())
        }
    }

    @Test
    fun contextIsCapturedWhenTheEventIsTracked() = runBlocking {
        val gate = CountDownLatch(1)
        val kirora = start(KiroraOptions(trackSessions = false, beforeSend = { event ->
            if (event.name == "First") gate.await()
            event
        }))
        kirora.identify(tokenA)
        Locale.setDefault(Locale.forLanguageTag("en-GB"))
        kirora.track("First") // holds the queue in beforeSend
        kirora.track("Second")
        Locale.setDefault(Locale.forLanguageTag("fr-FR"))
        gate.countDown()
        kirora.waitQueued(2)
        kirora.flush()
        waitUntil(message = "sent") { "Second" in fake.sentEventNames }
        val second = fake.batchRequests.flatMap { it.events }.first { it.name == "Second" }
        assertEquals("en-GB", second.context.asJsonObject.get("locale").asString)
    }

    // ---- logout boundary ----------------------------------------------------------------

    @Test
    fun eventsTrackedWhileLogoutRunsWaitForTheNextUser() = runBlocking {
        GlobalPreferences(context).setPushToken("fcm-1")
        val kirora = start(withPush)
        kirora.identify(tokenA)
        val release = CompletableDeferred<Unit>()
        fake.deleteResponder = {
            release.await()
            Response.success(204, Unit)
        }
        val logout = launch(Dispatchers.Default) { kirora.logout() }
        waitUntil(message = "logout reaches the detach") { fake.deviceDeletions.isNotEmpty() }
        assertFalse("signed out before any network work ends", kirora.isIdentified)
        kirora.track("During logout")
        kirora.waitQueued(1)
        release.complete(Unit)
        logout.join()
        delay(100)
        // Neither sent as A nor dropped with A's events.
        assertFalse("During logout" in fake.sentEventNames)
        kirora.waitQueued(1)

        kirora.identify(tokenB)
        kirora.flush()
        waitUntil(message = "sent as B") { "During logout" in fake.sentEventNames }
        assertEquals("Bearer $tokenB", fake.batchAuthorizations.last())
    }

    // ---- detach after a registration under way --------------------------------------------

    @Test
    fun aDetachWaitsForATokenRefreshRegistrationAndNamesIt() = runBlocking {
        GlobalPreferences(context).setPushToken("fcm-1")
        val kirora = start(withPush)
        kirora.identify(tokenA)
        val release = CompletableDeferred<Unit>()
        fake.deviceResponder = {
            release.await()
            Response.success(DataEnvelope(DeviceAnswer(lastSeenAt = "2026-11-17T09:00:00.000Z")))
        }
        kirora.pushManager!!.onNewToken("fcm-2")
        waitUntil(message = "the refresh's PUT") { fake.deviceRegistrations.size == 2 }

        val logout = launch(Dispatchers.Default) { kirora.logout() }
        delay(100)
        release.complete(Unit)
        logout.join()
        waitUntil(message = "the detach") { fake.deletionLastSeenAt.isNotEmpty() }
        assertEquals("2026-11-17T09:00:00.000Z", fake.deletionLastSeenAt.single())
    }

    @Test
    fun aDetachCoversAFirstRegistrationStillUnderWay() = runBlocking {
        GlobalPreferences(context).setPushToken("fcm-1")
        val kirora = start(withPush)
        val release = CompletableDeferred<Unit>()
        fake.deviceResponder = {
            release.await()
            Response.success(DataEnvelope(DeviceAnswer(lastSeenAt = "2026-11-17T09:00:00.000Z")))
        }
        kirora.identifyAsync(tokenA)
        waitUntil(message = "the first PUT") { fake.deviceRegistrations.size == 1 }

        val logout = launch(Dispatchers.Default) { kirora.logout() }
        delay(100)
        release.complete(Unit)
        logout.join()
        waitUntil(message = "the detach") { fake.deletionLastSeenAt.isNotEmpty() }
        assertEquals("2026-11-17T09:00:00.000Z", fake.deletionLastSeenAt.single())
    }

    // ---- storage that fails part of the time -----------------------------------------

    /** Room's DAO, with writes or reads made to fail on demand. */
    private class FlakyDao(private val real: EventDao) : EventDao by real {
        @Volatile var failWrites = false
        @Volatile var failReads = false

        private fun write() = check(!failWrites) { "read-only" }
        private fun read() = check(!failReads) { "unreadable" }

        override suspend fun insertEvent(event: EventEntity): Long = write().let { real.insertEvent(event) }
        override suspend fun insertAll(events: List<EventEntity>) = write().let { real.insertAll(events) }
        override suspend fun insertAndTrim(event: EventEntity, maxCount: Int, maxBytes: Long): Int = write().let { real.insertAndTrim(event, maxCount, maxBytes) }
        override suspend fun trimTo(maxCount: Int, maxBytes: Long): Int = write().let { real.trimTo(maxCount, maxBytes) }
        override suspend fun clearAll() = write().let { real.clearAll() }
        override suspend fun deleteOwnedBy(owner: String): Int = write().let { real.deleteOwnedBy(owner) }
        override suspend fun purgeAll() = write().let { real.purgeAll() }
        override suspend fun purgeOwner(owner: String) = write().let { real.purgeOwner(owner) }
        override suspend fun getCount(): Int = read().let { real.getCount() }
        override suspend fun totals(): QueueTotals = read().let { real.totals() }
        override suspend fun countFor(owner: String): Int = read().let { real.countFor(owner) }
        override suspend fun oldestFor(owner: String, limit: Int): List<EventEntity> = read().let { real.oldestFor(owner, limit) }
    }

    private fun newDatabase(): KiroraDatabase =
        Room.inMemoryDatabaseBuilder(context, KiroraDatabase::class.java).allowMainThreadQueries().build().also { db = it }

    private fun event(at: Long, owner: String? = "user-a") = EventEntity(
        idempotencyKey = UUID.randomUUID().toString(),
        name = "E",
        propertiesJson = "{}",
        contextJson = "{}",
        occurredAt = at,
        owner = owner,
        sizeBytes = 100
    )

    @Test
    fun aRefusedPurgeKeepsWithdrawnEventsUnavailableEvenAfterARestart() = runBlocking {
        val database = newDatabase()
        val dao = FlakyDao(database.eventDao())
        var saved: String? = null
        val ledger = { PendingPurges({ saved }, { saved = it; true }) }
        val store = EventStore(dao, ledger())
        // Dated a day ahead (the clock was ahead when they were tracked): still withdrawn.
        val ahead = System.currentTimeMillis() + 86_400_000L
        repeat(1000) { store.add(event(ahead + it)) }

        dao.failWrites = true
        store.clearAll()
        assertEquals("withdrawn events are out of reach at once", 0, store.count())
        assertEquals(0, store.oldestFor("user-a", 100).size)

        // A restart with storage still failing, then storage recovering: they never come back.
        val restarted = EventStore(dao, ledger())
        assertEquals(0, restarted.count())
        dao.failWrites = false
        assertEquals(0, restarted.count())
        assertEquals(0, database.eventDao().getCount())
        assertNull("nothing owed any more", saved)

        // Tracked after the withdrawal, even with the clock set back before the withdrawn
        // events: not covered by it.
        restarted.add(event(500L))
        assertEquals(1, restarted.count())
    }

    @Test
    fun aRefusedSignOutPurgeKeepsThatUsersEventsFromComingBack() = runBlocking {
        val dao = FlakyDao(newDatabase().eventDao())
        val store = EventStore(dao)
        repeat(10) { store.add(event(1_000L + it, owner = "user-a")) }
        store.add(event(2_000L, owner = "user-b"))
        dao.failWrites = true
        store.clearOwnedBy("user-a")
        assertEquals("out of reach while owed", 0, store.countFor("user-a"))
        dao.failWrites = false
        assertEquals(0, store.countFor("user-a"))
        assertEquals(1, store.countFor("user-b"))
        // A signs in again: what they track now is theirs to keep, whatever its time.
        store.add(event(1_000L, owner = "user-a"))
        assertEquals(1, store.countFor("user-a"))
    }

    @Test
    fun diskAndMemoryShareOneBudget() = runBlocking {
        val dao = FlakyDao(newDatabase().eventDao())
        val store = EventStore(dao)
        repeat(EventStore.MAX_EVENTS) { store.add(event(1_000L + it)) }
        // Readable, but refusing writes: the memory fallback gets no second budget.
        dao.failWrites = true
        var dropped = 0
        repeat(EventStore.MAX_EVENTS) { dropped += store.add(event(10_000L + it)) }
        assertEquals(EventStore.MAX_EVENTS, store.count())
        assertEquals(EventStore.MAX_EVENTS, dropped)
    }

    @Test
    fun eventsHeldInMemoryJoinTheDatabaseInCaptureOrderOnceItRecovers() = runBlocking {
        val database = newDatabase()
        val dao = FlakyDao(database.eventDao())
        val store = EventStore(dao)
        store.add(event(1_000L))
        dao.failWrites = true
        store.add(event(2_000L))
        assertEquals(listOf(1_000L, 2_000L), store.oldestFor("user-a", 10).map { it.occurredAt })
        dao.failWrites = false
        store.add(event(3_000L))
        assertEquals(3, database.eventDao().getCount())
        assertEquals(listOf(1_000L, 2_000L, 3_000L), store.oldestFor("user-a", 10).map { it.occurredAt })
    }

    // ---- per-event results ----------------------------------------------------------------

    @Test
    fun malformedResultsAcknowledgeNothing() = runBlocking {
        val kirora = start()
        kirora.identify(tokenA)
        fake.batchResponder = { request, _ ->
            val results = JsonArray()
            request.events.forEachIndexed { i, e ->
                results.add(JsonObject().apply {
                    addProperty("idempotencyKey", e.idempotencyKey)
                    when (i % 4) {
                        0 -> addProperty("error", "unreadable")
                        1 -> add("event", JsonNull.INSTANCE)
                        2 -> addProperty("event", "evt")
                        else -> add("error", JsonObject().apply { addProperty("message", "no code") })
                    }
                })
            }
            val body: JsonElement = JsonObject().apply { add("data", JsonObject().apply { add("results", results) }) }
            Response.success(body)
        }
        repeat(4) { kirora.track("E$it") }
        kirora.waitQueued(4)
        kirora.flush()
        waitUntil(message = "the batch") { fake.batchRequests.isNotEmpty() }
        delay(100)
        assertEquals("all kept for a retry with their keys", 4, kirora.queued())
    }

    // ---- Retry-After --------------------------------------------------------------------

    @Test
    fun retryAfterKeepsTheServersWholeWait() {
        assertEquals(7_200_000L, RetryAfter.parse(headersOf("Retry-After", "7200")))
        assertEquals(RetryAfter.MAX_MS, RetryAfter.parse(headersOf("Retry-After", "9223372036854776")))
        assertEquals(RetryAfter.MAX_MS, RetryAfter.parse(headersOf("Retry-After", "99999999999999999999999")))
        assertNull(RetryAfter.parse(headersOf("Retry-After", "-5")))
        assertNull(RetryAfter.parse(headersOf("Retry-After", "0")))
    }

    @Test
    fun aLongRetryAfterHoldsEventsBackPastAnHour() = runBlocking {
        val kirora = start()
        kirora.identify(tokenA)
        fake.batchResponder = { _, _ -> FakeKiroraApiService.error(429, "RATE_LIMITED", retryAfterSeconds = 7200) }
        kirora.track("Held")
        kirora.waitQueued(1)
        kirora.flush()
        waitUntil(message = "the refused batch") { fake.batchRequests.size == 1 }
        fake.batchResponder = null
        kirora.batchManager!!.flush(waiveBackoff = true)
        kirora.batchManager!!.onNetworkAvailable()
        delay(200)
        assertEquals("nothing sent before Retry-After", 1, fake.batchRequests.size)
    }

    // ---- replacement clients ----------------------------------------------------------------

    @Test
    fun aClientForAnotherWorkspaceOrApiStartsSignedOut() = runBlocking {
        start().identify(tokenA)
        val otherKey = Kirora(context, KiroraOptions(clientKey = "kirora_pk_other", trackSessions = false), KiroraEnvironment(pushBridge = bridge))
        assertFalse(otherKey.isIdentified)

        start().identify(tokenA)
        val otherApi = Kirora(
            context,
            KiroraOptions(clientKey = "kirora_pk_test", apiUrl = "https://staging.kirora.io", trackSessions = false),
            KiroraEnvironment(pushBridge = bridge)
        )
        assertFalse(otherApi.isIdentified)

        start().identify(tokenA)
        assertTrue("the same workspace keeps its user", start(clearQueue = false).isIdentified)
    }

    // ---- app-open spacing -----------------------------------------------------------------

    @Test
    fun withSessionsOffTheAppOpenSpacingIsNotStored() = runBlocking {
        GlobalPreferences(context).setPushToken("fcm-1")
        val kirora = start(withPush)
        kirora.identify(tokenA)
        kirora.appOpens!!.onAppOpen()
        waitUntil(message = "the app open") { fake.appOpens.size == 1 }
        assertEquals(0L, storedLastOpen())
        kirora.appOpens!!.onAppOpen()
        delay(100)
        assertEquals("still spaced, in memory", 1, fake.appOpens.size)
    }

    @Test
    fun withdrawingConsentForgetsTheStoredAppOpenTime() = runBlocking {
        GlobalPreferences(context).setPushToken("fcm-1")
        val kirora = start(KiroraOptions(trackSessions = true, push = KiroraPushOptions()))
        kirora.identify(tokenA)
        kirora.appOpens!!.onAppOpen()
        waitUntil(message = "the app open") { fake.appOpens.size == 1 }
        assertTrue(storedLastOpen() > 0)
        kirora.setTrackingConsent(false)
        assertEquals(0L, storedLastOpen())
        // A regrant within 30 minutes starts again.
        kirora.setTrackingConsent(true)
        kirora.appOpens!!.onAppOpen()
        waitUntil(message = "a new app open") { fake.appOpens.size == 2 }
    }

    private fun storedLastOpen(): Long =
        KiroraPreferences(context, KiroraNamespace.of("https://api.kirora.io", "kirora_pk_test")).getLastAppOpen()
}
