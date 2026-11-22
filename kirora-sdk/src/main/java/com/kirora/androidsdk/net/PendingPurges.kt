package com.kirora.androidsdk.net

import com.kirora.androidsdk.db.PurgeTarget
import com.kirora.androidsdk.utils.KiroraLogger
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Deletions a table refused, still owed: all its rows, or one owner's. Until they are carried
 * out, the table must not be read, counted or sent from, so a withdrawal or a sign-out can't be
 * undone by storage that recovers later. Kept in preferences as well, so a restart doesn't
 * forget them.
 *
 * Its stores write nothing to the table while a deletion is owed: every write [settle]s first.
 * So what is owed covers exactly the rows stored when it was refused, and rows written after a
 * later grant or sign-in are never caught by it. No time or row number is needed.
 */
internal class PendingPurges(
    private val load: () -> String? = { null },
    private val save: (String?) -> Boolean = { true },
    /** After a failed attempt, how long before the next: a broken database isn't retried on every call. */
    private val retryIntervalMs: Long = 0L,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var all = false
    private val owners = HashSet<String>()

    /** Changes with every deletion owed, so [settle] only clears what it carried out. */
    private var version = 0

    @Volatile
    private var nextAttemptAt = 0L

    /** Read on every database call, so kept apart from the lock that [persist] writes under. */
    @Volatile
    private var empty: Boolean = true

    val isEmpty: Boolean
        get() {
            ensureLoaded()
            return empty
        }

    /** What is owed, read from storage on first use rather than at construction. Published only once read. */
    private val owedRead = lazy(this) { readOwed() }

    private fun ensureLoaded() = owedRead.value

    private fun readOwed() {
        try {
            load()?.let { text ->
                val json = JSONObject(text)
                all = json.optBoolean(ALL)
                json.optJSONArray(OWNERS)?.let { o -> for (i in 0 until o.length()) owners.add(o.getString(i)) }
            }
        } catch (e: Exception) {
            KiroraLogger.e("Kirora couldn't read the deletions it still owes", e)
        }
        updateEmpty()
    }

    /** Every row is owed a deletion. */
    @Synchronized
    private fun oweAll() {
        ensureLoaded()
        all = true
        owners.clear()
        version++
        persist()
    }

    /** [owner]'s rows are owed a deletion. */
    @Synchronized
    private fun oweFor(owner: String) {
        ensureLoaded()
        if (all) return
        owners.add(owner)
        version++
        persist()
    }

    /**
     * Carries out what is owed on [target]. True when nothing is owed any more; false when a
     * deletion failed again, and the rows stay out of reach.
     */
    suspend fun settle(target: PurgeTarget): Boolean {
        if (isEmpty) return true
        if (clock() < nextAttemptAt) return false
        val (purgeAll, purgeOwners, owedAt) = synchronized(this) { Triple(all, owners.toList(), version) }
        try {
            if (purgeAll) target.purgeAll() else purgeOwners.forEach { target.purgeOwner(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KiroraLogger.e("Kirora still can't delete stored data it was asked to forget; it stays unused until it can.", e)
            nextAttemptAt = clock() + retryIntervalMs
            return false
        }
        synchronized(this) {
            // Something owed meanwhile stays owed.
            if (version == owedAt) {
                all = false
                owners.clear()
                persist()
            }
            return empty
        }
    }

    /** Deletes every row of [target], or owes it when the deletion is refused. */
    suspend fun purgeAll(target: PurgeTarget) {
        attempt(target, { target.purgeAll() }) { oweAll() }
    }

    /** Deletes [owner]'s rows of [target], or owes it. How many were deleted, or null when owed. */
    suspend fun purgeOwner(target: PurgeTarget, owner: String): Int? =
        attempt(target, { target.purgeOwner(owner) }) { oweFor(owner) }

    /** Runs [delete] once what is already owed is settled; when either fails, [owe] records it. */
    private suspend fun <T> attempt(target: PurgeTarget, delete: suspend () -> T, owe: () -> Unit): T? {
        if (settle(target)) {
            try {
                return delete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KiroraLogger.e("Kirora's database refused a deletion; those rows stay unused until it can be done.", e)
            }
        }
        owe()
        return null
    }

    /** Called under the lock. */
    private fun updateEmpty() {
        empty = !all && owners.isEmpty()
    }

    private fun persist() {
        updateEmpty()
        val text = if (empty) {
            null
        } else {
            JSONObject().apply {
                if (all) put(ALL, true)
                if (owners.isNotEmpty()) put(OWNERS, JSONArray(owners.toList()))
            }.toString()
        }
        // Still held in memory for this process when it can't be written.
        if (!save(text)) KiroraLogger.e("Kirora couldn't remember a deletion it still owes; it is kept for this run only.")
    }

    private companion object {
        const val ALL = "all"
        const val OWNERS = "owners"
    }
}
