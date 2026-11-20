package com.kirora.androidsdk.model

/** Why [KiroraOptions.onAuthenticationFailed] was called. */
public enum class AuthFailureReason {
    /**
     * `fetchUserToken` threw, returned no token, or didn't answer within 30 seconds. A refresh
     * before the token expires that fails is retried, not reported, until the token has expired.
     */
    FETCH_FAILED,

    /**
     * Kirora answered 401 and no fresh token was accepted: a fresh one was refused too, or there
     * was none to try (no `fetchUserToken`, or it returned a token for another user).
     */
    REFUSED
}
