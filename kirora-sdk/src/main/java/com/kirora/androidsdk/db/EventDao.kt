package com.kirora.androidsdk.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
internal interface EventDao : PurgeTarget {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(event: EventEntity): Long

    /** The oldest events [owner] owns; never anyone else's, nor unadopted ones. */
    @Query("SELECT * FROM events WHERE owner = :owner ORDER BY seq ASC LIMIT :limit")
    suspend fun oldestFor(owner: String, limit: Int): List<EventEntity>

    @Query("SELECT * FROM events WHERE owner IS NULL")
    suspend fun unowned(): List<EventEntity>

    @Query("SELECT idempotencyKey FROM events WHERE owner IS NULL")
    suspend fun unownedKeys(): List<String>

    /** Those waiting from before sign-in that continued a visit other than [owner]'s. */
    @Query("SELECT * FROM events WHERE owner IS NULL AND visitOwner IS NOT NULL AND visitOwner != :owner")
    suspend fun unownedOnOtherVisits(owner: String): List<EventEntity>

    @Query("UPDATE events SET contextJson = :contextJson WHERE seq = :seq")
    suspend fun setContext(seq: Long, contextJson: String)

    @Query("UPDATE events SET owner = :owner WHERE owner IS NULL")
    suspend fun adoptUnowned(owner: String)

    /**
     * Binds every unowned event to [owner] in one transaction, as [adoptedBy] does: only those from
     * another user's visit are rewritten (without their `sessionId`). Returns their keys.
     */
    @Transaction
    suspend fun adopt(owner: String): List<String> {
        val keys = unownedKeys()
        if (keys.isEmpty()) return keys
        unownedOnOtherVisits(owner).forEach { event ->
            val adopted = event.adoptedBy(owner)
            if (adopted.contextJson != event.contextJson) setContext(event.seq, adopted.contextJson)
        }
        adoptUnowned(owner)
        return keys
    }

    @Query("UPDATE events SET owner = NULL WHERE owner = :owner AND idempotencyKey IN (:keys)")
    suspend fun release(owner: String, keys: List<String>)

    @Query("DELETE FROM events WHERE idempotencyKey IN (:keys)")
    suspend fun deleteByKeys(keys: List<String>)

    @Query("DELETE FROM events WHERE owner = :owner")
    suspend fun deleteOwnedBy(owner: String): Int

    @Query("DELETE FROM events WHERE owner IS NULL")
    suspend fun deleteUnowned(): Int

    @Query("DELETE FROM events WHERE owner IS NULL AND visitOwner IS NOT NULL")
    suspend fun deleteUnownedOnSignedInVisits(): Int

    /** Events of earlier sign-ins whose tokens named nobody: nobody can sign in as them again. */
    @Query("DELETE FROM events WHERE owner LIKE 'kirora:unnamed:%' AND owner != :current")
    suspend fun deleteUnnamedExcept(current: String): Int

    @Query("DELETE FROM events")
    suspend fun clearAll()

    override suspend fun purgeAll() = clearAll()

    override suspend fun purgeOwner(owner: String) = when (owner) {
        PurgeTarget.UNOWNED -> deleteUnowned()
        PurgeTarget.UNOWNED_ON_SIGNED_IN_VISITS -> deleteUnownedOnSignedInVisits()
        else -> deleteOwnedBy(owner)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<EventEntity>)

    @Query("SELECT COUNT(*) FROM events")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM events WHERE owner = :owner")
    suspend fun countFor(owner: String): Int

    @Query("SELECT COUNT(*) FROM events")
    fun getCountFlow(): Flow<Int>

    /** The queue's size in one scan. */
    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(sizeBytes), 0) AS bytes FROM events")
    suspend fun totals(): QueueTotals

    @Query("SELECT seq, sizeBytes FROM events ORDER BY seq ASC LIMIT :limit")
    suspend fun oldestSizes(limit: Int): List<SeqSize>

    @Query("DELETE FROM events WHERE seq <= :maxSeq")
    suspend fun deleteUpTo(maxSeq: Long)

    /** Adds [event], then drops the oldest until at most [maxCount] events and [maxBytes] fit. Returns how many were dropped. */
    @Transaction
    suspend fun insertAndTrim(event: EventEntity, maxCount: Int, maxBytes: Long): Int {
        insertEvent(event)
        return trimTo(maxCount, maxBytes)
    }

    /** Drops the oldest until at most [maxCount] events and [maxBytes] remain. Returns how many were dropped. */
    @Transaction
    suspend fun trimTo(maxCount: Int, maxBytes: Long): Int {
        val (count, bytes) = totals()
        if (count <= maxCount && bytes <= maxBytes) return 0
        var dropCount = 0
        var freed = 0L
        var lastSeq = -1L
        for (row in oldestSizes(count)) {
            if (count - dropCount <= maxCount && bytes - freed <= maxBytes) break
            dropCount++
            freed += row.sizeBytes
            lastSeq = row.seq
        }
        if (lastSeq >= 0) deleteUpTo(lastSeq)
        return dropCount
    }
}

internal data class SeqSize(val seq: Long, val sizeBytes: Int)

internal data class QueueTotals(val count: Int, val bytes: Long)
