package com.kirora.androidsdk.utils

import android.util.Base64
import org.json.JSONObject

/** Reads the (unverified) `sub`, `exp` and `iat` claims: who is signed in, and when the token runs out. */
internal object JwtClaims {
    fun subject(token: String?): String? = payload(token)?.optString("sub")?.ifEmpty { null }

    /**
     * When [token], arriving at [now] (epoch ms), stops working, in epoch ms. At its `exp`, unless
     * it arrives already expired or issued more than [CLOCK_LEEWAY_MS] ahead of [now]: the device's
     * clock is off, so its lifetime (`exp` less `iat`) counts from now. Null when that can't be
     * told (no `exp`, or expired on arrival without `iat`): no early refresh, a 401 replaces it.
     */
    fun expiresAtMillis(token: String?, now: Long): Long? {
        val claims = payload(token) ?: return null
        val exp = claims.optLong("exp", 0L).takeIf { it > 0 } ?: return null
        val iat = claims.optLong("iat", 0L).takeIf { it > 0 && it <= exp }
        val expiresAt = exp * 1000
        val clockOff = expiresAt <= now || (iat != null && iat * 1000 > now + CLOCK_LEEWAY_MS)
        if (!clockOff) return expiresAt
        return if (iat != null) now + (exp - iat) * 1000 else if (expiresAt <= now) null else expiresAt
    }

    /** How far ahead a token's `iat` may be before the device's clock counts as behind: a refresh's lead. */
    const val CLOCK_LEEWAY_MS = 60_000L

    private fun payload(token: String?): JSONObject? {
        if (token == null) return null
        return try {
            val parts = token.split('.')
            if (parts.size < 2) return null
            val payload = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            JSONObject(String(payload, Charsets.UTF_8))
        } catch (_: Throwable) {
            null
        }
    }
}
