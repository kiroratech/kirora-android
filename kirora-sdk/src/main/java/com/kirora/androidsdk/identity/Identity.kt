package com.kirora.androidsdk.identity

import com.kirora.androidsdk.model.AuthFailureReason
import com.kirora.androidsdk.model.UserTokenError
import com.kirora.androidsdk.utils.JwtClaims
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * One signed-in user. A new object each time the user changes, so work started for a user
 * captures it, and `identity.user === user` tells whether they are still the one signed in.
 * A refreshed token replaces [token] on the same object. The token is kept in memory only.
 */
internal class SignedInUser(
    token: String,
    /** The token's `sub`, or null. A refreshed token must have the same. */
    val tokenSubject: String?
) {
    @Volatile
    var token: String = token

    /**
     * Owns this user's queued events: the token's `sub`. A token without one can't be told
     * apart from another user's, so it gets an id of its own that nobody can sign in as again.
     */
    val subject: String = tokenSubject ?: "$UNNAMED_SUBJECT${UUID.randomUUID()}"

    /** Set once Kirora has accepted a call with this user's token. */
    @Volatile
    var confirmed: Boolean = false

    /**
     * The token the app was last told failed in the background, or null. Told again only after
     * [accepted] or a new identify(): once per failure, not per retry or per fresh token.
     */
    @Volatile
    var authFailureReportedFor: String? = null

    /** identify() calls still running for this user; a background failure waits for them, as identify() may throw it. */
    var signingIn: Int = 0

    /** A background failure held while [signingIn]. */
    var heldAuthFailure: AuthFailureReason? = null

    /** Kirora accepted a call with this user's token. */
    fun accepted() {
        confirmed = true
        authFailureReportedFor = null
    }

    val authorization: String
        get() = "Bearer $token"

    companion object {
        const val UNNAMED_SUBJECT = "kirora:unnamed:"
    }
}

/**
 * Who is signed in, and every rule for moving from one user to the next:
 * - The latest `signIn()` or `end()` wins, whatever order tokens arrive in.
 * - Work started for one user never carries on as the next: it captures [user].
 * - A refused token is refreshed once, shared by every call that was refused, and only for the
 *   same `sub`.
 */
internal class Identity(private val fetchUserToken: (suspend () -> String?)?) {
    @Volatile
    var user: SignedInUser? = null
        private set

    private val calls = AtomicInteger()
    private val refreshMutex = Mutex()

    @Volatile
    private var signingIn: CompletableDeferred<Unit>? = null

    /** Changes with every [signIn], [end] and [abandonSignIn]. */
    val generation: Int
        get() = calls.get()

    val canRefresh: Boolean
        get() = fetchUserToken != null

    /**
     * Signs in with [token], or one from `fetchUserToken`, then runs [onSignedIn] with the user.
     * Does nothing if a later sign-in or sign-out decided who is signed in meanwhile. The same
     * `sub` signing in again keeps its [SignedInUser], with the new token.
     */
    suspend fun signIn(token: String?, onSignedIn: suspend (previous: SignedInUser?, user: SignedInUser) -> Unit) {
        val call = calls.incrementAndGet()
        val done = CompletableDeferred<Unit>()
        signingIn = done
        try {
            val userToken = token ?: fetchToken()
            val (previous, signedIn) = synchronized(this) {
                if (call != calls.get()) return
                val previous = user
                val subject = JwtClaims.subject(userToken)
                val signedIn = if (previous != null && previous.tokenSubject != null && previous.tokenSubject == subject) {
                    previous.also { it.token = userToken }
                } else {
                    SignedInUser(userToken, subject)
                }
                user = signedIn
                previous to signedIn
            }
            onSignedIn(previous, signedIn)
        } finally {
            done.complete(Unit)
        }
    }

    /**
     * Signs out whoever is signed in, abandoning any sign-in still waiting for its token, and
     * returns them: from here on, new work belongs to no one.
     */
    @Synchronized
    fun end(): SignedInUser? {
        calls.incrementAndGet()
        val outgoing = user
        user = null
        return outgoing
    }

    /** Carries a user over from the client this one replaces, without a new sign-in. */
    @Synchronized
    fun restore(signedIn: SignedInUser) {
        calls.incrementAndGet()
        user = signedIn
    }

    /** Lets a sign-in still waiting for its token set no one, keeping whoever is signed in. */
    fun abandonSignIn() {
        calls.incrementAndGet()
    }

    /** Waits for the latest sign-in, whatever its outcome. */
    suspend fun settled() {
        signingIn?.await()
    }

    /**
     * A replacement for [signedIn]'s refused token, or null if they are no longer signed in, there
     * is no `fetchUserToken`, or it returned a token for someone else. Reuses a token another call
     * already refreshed. Throws [UserTokenError] when the fetch fails.
     */
    suspend fun refresh(signedIn: SignedInUser, refusedToken: String): String? {
        if (user !== signedIn || fetchUserToken == null) return null
        if (signedIn.token != refusedToken) return signedIn.token
        return refreshMutex.withLock {
            if (user !== signedIn) return@withLock null
            if (signedIn.token != refusedToken) return@withLock signedIn.token
            val fresh = fetchToken()
            if (user !== signedIn || JwtClaims.subject(fresh) != signedIn.tokenSubject) return@withLock null
            signedIn.token = fresh
            fresh
        }
    }

    private suspend fun fetchToken(): String {
        val fetch = fetchUserToken ?: throw UserTokenError("pass identify() a user token, or set fetchUserToken")
        val token = try {
            withTimeout(TOKEN_TIMEOUT_MS) { fetch() }
        } catch (e: TimeoutCancellationException) {
            throw UserTokenError("fetchUserToken did not answer within 30 seconds", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw UserTokenError("fetchUserToken failed", e)
        }
        if (token.isNullOrEmpty()) throw UserTokenError("fetchUserToken returned no token")
        return token
    }

    companion object {
        const val TOKEN_TIMEOUT_MS = 30_000L
    }
}
