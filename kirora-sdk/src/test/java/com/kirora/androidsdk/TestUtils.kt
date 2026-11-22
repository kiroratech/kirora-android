package com.kirora.androidsdk

import android.os.Looper
import kotlinx.coroutines.delay
import org.junit.Assert.fail
import org.robolectric.Shadows.shadowOf
import java.util.Base64

internal suspend fun waitUntil(timeoutMs: Long = 5000, message: String = "condition", condition: suspend () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        if (System.currentTimeMillis() > end) fail("Timed out waiting for $message")
        delay(25)
    }
}

/** [waitUntil], running what is posted to the main thread (Robolectric's paused looper) each time. */
internal suspend fun waitUntilOnMain(timeoutMs: Long = 5000, message: String = "condition", condition: suspend () -> Boolean) =
    waitUntil(timeoutMs, message) {
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }

/** An unsigned JWT with [sub] (and [exp], [iat], epoch seconds): the SDK reads claims without verifying them. */
internal fun jwt(sub: String?, exp: Long? = null, nonce: String = "", iat: Long? = null): String {
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val claims = buildList {
        if (sub != null) add("\"sub\":\"$sub\"")
        if (exp != null) add("\"exp\":$exp")
        if (iat != null) add("\"iat\":$iat")
        if (nonce.isNotEmpty()) add("\"n\":\"$nonce\"")
    }.joinToString(",", "{", "}")
    return encoder.encodeToString("""{"alg":"HS256"}""".toByteArray()) + "." +
        encoder.encodeToString(claims.toByteArray()) + ".sig"
}
