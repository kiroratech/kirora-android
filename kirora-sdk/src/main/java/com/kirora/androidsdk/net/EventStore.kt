package com.kirora.androidsdk.net

import com.kirora.androidsdk.db.EventDao
import com.kirora.androidsdk.db.EventEntity
import com.kirora.androidsdk.db.adoptedBy
import com.kirora.androidsdk.db.PurgeTarget
import com.kirora.androidsdk.db.QueueTotals
import com.kirora.androidsdk.identity.SignedInUser
import com.kirora.androidsdk.utils.KiroraLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The event queue: Room, plus an in-memory queue for events Room could not take (no database, or
 * a write that failed), which is logged as not surviving a restart. Room is tried again on every
 * call, and once it takes writes again the events held in memory move into it.
 *
 * One budget covers both: at most [MAX_EVENTS] events and [MAX_BYTES] bytes in all, the oldest
 * dropped first. A deletion Room refuses (a consent withdrawal, a sign-out) is remembered in
 * [purges] and carried out before Room is read again; until then what it covers stays out of reach.
 */
internal class EventStore(
    /** The database's, or null to keep events in memory; asked for on first use, off the main thread. */
    daoProvider: () -> EventDao?,
    private val purges: PendingPurges = PendingPurges()
) {
    private val dao: EventDao? by lazy { daoProvider().also { if (it == null) warnInMemory(null) } }

    private val memoryLock = Mutex()
    private val memory = ArrayDeque<EventEntity>()

    /** What Room held when last counted, for the shared budget while Room can't be read. */
    @Volatile
    private var diskCount = 0

    @Volatile
    private var diskBytes = 0L

    @Volatile
    private var warned = false

    /** Adds [event]; returns how many old events were dropped to stay within the budgets. */
    suspend fun add(event: EventEntity): Int {
        val d = dao
        if (d != null && purges.settle(d)) {
            try {
                moveMemoryToDisk()
                return d.insertAndTrim(event, MAX_EVENTS, MAX_BYTES)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warnInMemory(e)
            }
        }
        refreshDiskTotals()
        return memoryLock.withLock {
            memory.add(event)
            var dropped = 0
            var bytes = memory.sumOf { it.sizeBytes.toLong() }
            if (memory.size + diskCount > MAX_EVENTS || bytes + diskBytes > MAX_BYTES) {
                // The oldest are on disk: drop those first if Room still lets us.
                val trimmed = room(-1) { it.trimTo((MAX_EVENTS - memory.size).coerceAtLeast(0), (MAX_BYTES - bytes).coerceAtLeast(0L)) }
                if (trimmed > 0) {
                    dropped += trimmed
                    refreshDiskTotals()
                }
            }
            while (memory.isNotEmpty() && (memory.size + diskCount > MAX_EVENTS || bytes + diskBytes > MAX_BYTES)) {
                bytes -= memory.removeFirst().sizeBytes
                dropped++
            }
            dropped
        }
    }

    /** [owner]'s oldest events, in the order they were captured, wherever they are held. */
    suspend fun oldestFor(owner: String, limit: Int): List<EventEntity> {
        val stored = room(emptyList()) { it.oldestFor(owner, limit) }
        val held = memoryLock.withLock { memory.filter { it.owner == owner }.take(limit) }
        if (held.isEmpty()) return stored
        return (stored + held).sortedBy { it.occurredAt }.take(limit)
    }

    /** Binds every unowned event to [owner] at once; returns their keys, so a refused sign-in can give them back. */
    suspend fun adopt(owner: String): List<String> {
        val stored = room(emptyList()) { it.adopt(owner) }
        val held = memoryLock.withLock {
            val keys = ArrayList<String>()
            memory.replaceAll { e -> if (e.owner == null) e.adoptedBy(owner).also { keys.add(e.idempotencyKey) } else e }
            keys
        }
        return stored + held
    }

    suspend fun release(owner: String, keys: List<String>) {
        if (keys.isEmpty()) return
        room(Unit) { d -> keys.chunked(CHUNK).forEach { d.release(owner, it) } }
        val set = keys.toSet()
        memoryLock.withLock {
            memory.replaceAll { e -> if (e.owner == owner && e.idempotencyKey in set) e.copy(owner = null) else e }
        }
    }

    /** False when the database still holds them (it failed to delete): reading on would find them again. */
    suspend fun remove(keys: Collection<String>): Boolean {
        if (keys.isEmpty()) return true
        val set = keys.toSet()
        // Held only in memory (the database failing): gone once removed here, whatever Room says.
        if (removeFromMemory { it.idempotencyKey in set } == set.size) return true
        return dao == null || room(false) { d -> keys.chunked(CHUNK).forEach { d.deleteByKeys(it) }; true }
    }

    /** Drops [owner]'s events. One Room refuses to delete is owed, and never read again until deleted. */
    suspend fun clearOwnedBy(owner: String): Int {
        val stored = dao?.let { purges.purgeOwner(it, owner) } ?: 0
        return stored + removeFromMemory { it.owner == owner }
    }

    /**
     * Drops the events waiting from before sign-in, so the next user to sign in never adopts them:
     * all of them, or with [onlySignedInVisits] only those that continued a signed-in user's visit.
     */
    suspend fun clearUnowned(onlySignedInVisits: Boolean): Int {
        // Owed, durably, when the database refuses: nothing is adopted until it's done.
        val which = if (onlySignedInVisits) PurgeTarget.UNOWNED_ON_SIGNED_IN_VISITS else PurgeTarget.UNOWNED
        val stored = dao?.let { purges.purgeOwner(it, which) } ?: 0
        return stored + removeFromMemory { it.owner == null && (!onlySignedInVisits || it.visitOwner != null) }
    }

    suspend fun clearUnnamedExcept(current: String): Int {
        val stored = room(0) { it.deleteUnnamedExcept(current) }
        return stored + removeFromMemory { val o = it.owner; o != null && o != current && o.startsWith(SignedInUser.UNNAMED_SUBJECT) }
    }

    /** Drops every event. One Room refuses to delete is owed, and never read again until deleted. */
    suspend fun clearAll() {
        dao?.let { purges.purgeAll(it) }
        memoryLock.withLock { memory.clear() }
    }

    suspend fun countFor(owner: String): Int =
        room(0) { it.countFor(owner) } + memoryLock.withLock { memory.count { it.owner == owner } }

    suspend fun count(): Int = room(0) { it.getCount() } + memoryLock.withLock { memory.size }

    /** [count] as it changes; follows the database, and counts nothing a deletion is owed for. */
    fun countFlow(): Flow<Int>? = dao?.getCountFlow()?.map { stored -> (if (purges.isEmpty) stored else 0) + memory.size }

    /** Once Room takes writes again, the events held in memory join it, oldest first. */
    private suspend fun moveMemoryToDisk() {
        val d = dao ?: return
        memoryLock.withLock {
            if (memory.isEmpty()) return
            d.insertAll(memory.toList())
            memory.clear()
        }
    }

    /** Rows owed a deletion are out of reach, so they don't count against the budget. */
    private suspend fun refreshDiskTotals() {
        val totals = room(null) { it.totals() } ?: if (purges.isEmpty) return else QueueTotals(0, 0L)
        diskCount = totals.count
        diskBytes = totals.bytes
    }

    private suspend fun removeFromMemory(predicate: (EventEntity) -> Boolean): Int = memoryLock.withLock {
        val before = memory.size
        memory.removeAll(predicate)
        before - memory.size
    }

    /**
     * Runs [block] against Room, answering [fallback] when there is no database, the call fails, or
     * a deletion is still owed (what it covers must not be read).
     */
    private suspend fun <T> room(fallback: T, block: suspend (EventDao) -> T): T {
        val d = dao ?: return fallback
        return try {
            if (!purges.settle(d)) return fallback
            block(d)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KiroraLogger.e("Kirora's event database failed; trying again on the next call.", e)
            fallback
        }
    }

    private fun warnInMemory(error: Exception?) {
        if (warned) return
        warned = true
        KiroraLogger.e("Kirora can't store events in its database; they are kept in memory and will not survive a restart.", error)
    }

    companion object {
        const val MAX_EVENTS = 1000
        const val MAX_BYTES = 1_000_000L
        private const val CHUNK = 500
    }
}
