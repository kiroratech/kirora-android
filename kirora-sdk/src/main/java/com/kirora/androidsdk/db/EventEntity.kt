package com.kirora.androidsdk.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.JsonParser

/**
 * A behavioural event waiting to be sent. `seq` gives a strict, tie-free delivery order. The
 * owner is kept on the device only, never sent: Kirora derives the contact from the token.
 */
@Entity(
    tableName = "events",
    indices = [Index(value = ["idempotencyKey"], unique = true), Index(value = ["owner"])]
)
internal data class EventEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val idempotencyKey: String,
    val name: String,
    val propertiesJson: String,
    val contextJson: String,
    val occurredAt: Long,
    /** `sub` of the user selected when the event was tracked; null until a sign-in adopts it. */
    val owner: String? = null,
    /**
     * For an event tracked before sign-in, whose visit it continued (that visit's owner), so that
     * adoption by anyone else drops its `sessionId`: two users' visits are never joined. Device only.
     */
    val visitOwner: String? = null,
    /** The event's serialized size in UTF-8 bytes, ownership included, for the queue's byte budget. */
    val sizeBytes: Int
)

/**
 * This event as [owner] adopts it. One that continued another user's visit loses its
 * `sessionId`, so that visit isn't joined to [owner]'s.
 */
internal fun EventEntity.adoptedBy(owner: String): EventEntity {
    val context = if (visitOwner != null && visitOwner != owner) withoutSessionId(contextJson) else contextJson
    // visitOwner kept: a refused sign-in gives the event back, and the next adopter still needs it.
    return copy(owner = owner, contextJson = context)
}

private fun withoutSessionId(contextJson: String): String = try {
    val context = JsonParser.parseString(contextJson).asJsonObject
    if (context.remove("sessionId") == null) contextJson else context.toString()
} catch (_: Exception) {
    contextJson
}
