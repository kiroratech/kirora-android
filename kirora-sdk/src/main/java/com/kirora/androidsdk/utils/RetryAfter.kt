package com.kirora.androidsdk.utils

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.kirora.androidsdk.model.KiroraApiError
import okhttp3.Headers
import retrofit2.Response

internal object RetryAfter {
    /**
     * The longest wait kept: far beyond any real `Retry-After`, yet small enough that adding it to
     * the clock can't overflow. A longer one is kept at this, never cut to something sooner.
     */
    const val MAX_MS: Long = Long.MAX_VALUE / 4

    /** `Retry-After` in milliseconds from now, in seconds or as an HTTP date; null when absent, malformed or past. */
    fun parse(headers: Headers?, now: Long = System.currentTimeMillis()): Long? {
        val value = headers?.get("Retry-After")?.trim() ?: return null
        val ms = if (value.isNotEmpty() && value.all { it in '0'..'9' }) {
            // Too many digits for a Long is still a wait, and a long one.
            val seconds = value.toLongOrNull() ?: Long.MAX_VALUE
            if (seconds >= MAX_MS / 1000) MAX_MS else seconds * 1000
        } else {
            headers.getDate("Retry-After")?.let { it.time - now } ?: return null
        }
        return if (ms > 0) ms.coerceAtMost(MAX_MS) else null
    }
}

/** The non-blank string `code` of a `{ error: { code } }` object, or null when there isn't one. */
internal fun JsonObject.errorCode(): String? =
    (get("error") as? JsonObject)?.stringField("code")?.takeIf { it.isNotBlank() }

/** The member [name] when it is a JSON string, else null. */
internal fun JsonObject.stringField(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.asString

/** The answer as a [KiroraApiError]. Reads the error body unless it was read already and passed as [body]. */
internal fun Response<*>.toApiError(body: JsonObject? = errorObject()): KiroraApiError {
    val code = body?.errorCode()
    return KiroraApiError(code(), code, "Kirora answered HTTP ${code()}${code?.let { " $it" } ?: ""}", RetryAfter.parse(headers()))
}

/** The error body as JSON, or null when there is none or it isn't an object. Reads the body, so call once. */
internal fun Response<*>.errorObject(): JsonObject? = try {
    errorBody()?.string()?.let { text -> JsonParser.parseString(text) as? JsonObject }
} catch (_: Exception) {
    null
}

/** `{ data: … }`, Kirora's answer envelope. */
internal fun JsonElement?.data(): JsonElement? = try {
    this?.asJsonObject?.get("data")
} catch (_: Exception) {
    null
}
