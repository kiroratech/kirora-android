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
import retrofit2.Response
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
    val tokenSubject: String?,
    /** When [token] arrived, epoch ms. */
    receivedAt: Long
) {
    /** The token and when it stops working, set together as it arrives (see [JwtClaims.expiresAtMillis]). */
    @Volatile
    var current: Pair<String, Long?> = token to JwtClaims.expiresAtMillis(token, receivedAt)
        private set

    val token: String
        get() = current.first

    /** A new token for this user, arrived at [now]. */
    fun replaceToken(token: String, now: Long) {
        current = token to JwtClaims.expiresAtMillis(token, now)
    }

    /**
     * Owns this user's queued events: the token's `sub`. A token without one can't be told
     * apart from another user's, so it gets an id of its own that nobody can sign in as again.
     */
    val subject: String = tokenSubject ?: "$UNNAMED_SUBJECT${UUID.randomUUID()}"

    /**
     * The token Kirora refused when there is no fetchUserToken to replace it (set by [Identity]
     * only): every request as this user waits until identify() gives another.
     */
    @Volatile
    var refusedToken: String? = null

    /** Paused: Kirora refused the current token and nothing can replace it but identify(). */
    val waitingForToken: Boolean
        get() = refusedToken == token

    /** Set once Kirora has accepted a call with this user's token. */
    @Volatile
    var confirmed: Boolean = false

    /**
     * The token the app was last told failed in the background, or null. Told again only after
     * [accepted] or a new identify(): once per failure, not per retry or per fresh token.
     */
    @Volatile
    var authFailureReportedFor: String? = null
        private set

    // Locked on this user, not a client: a replacement client shares it and may sign it in too.
    /** identify() calls still running for this user; a background failure waits for them, as identify() may throw it. */
    private var signingIn = 0

    /** A background failure held while [signingIn]. */
    private var heldAuthFailure: AuthFailureReason? = null

    /** An identify() for this user begins: a later background failure may be reported again. */
    @Synchronized
    fun beginSignIn() {
        authFailureReportedFor = null
        signingIn++
    }

    /**
     * An identify() ends; [tokenProblem] when it throws the token's failure itself, which is then
     * not reported again. Answers the failure held meanwhile, once no identify() is left running.
     */
    @Synchronized
    fun endSignIn(tokenProblem: Boolean): AuthFailureReason? {
        signingIn--
        if (tokenProblem) {
            authFailureReportedFor = token
            heldAuthFailure = null
        }
        return if (signingIn == 0) heldAuthFailure.also { heldAuthFailure = null } else null
    }

    /**
     * The token to report [reason] for, now marked reported; or null when it was already
     * reported, or is held until the identify() under way ends.
     */
    @Synchronized
    fun claimAuthFailure(reason: AuthFailureReason): String? {
        if (authFailureReportedFor != null) return null
        if (signingIn > 0) {
            if (heldAuthFailure == null) heldAuthFailure = reason
            return null
        }
        return token.also { authFailureReportedFor = it }
    }

    /** Kirora accepted a call with this user's token. */
    @Synchronized
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
internal class Identity(
    private val fetchUserToken: (suspend () -> String?)?,
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Volatile
    var user: SignedInUser? = null
        private set

    private val calls = AtomicInteger()
    private val refreshMutex = Mutex()

    @Volatile
    private var signingIn: CompletableDeferred<Unit>? = null

    /** Changes with every [signIn] and [end]. */
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
                    previous.also { it.replaceToken(userToken, clock()) }
                } else {
                    SignedInUser(userToken, subject, clock())
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


    /** Waits for the latest sign-in, whatever its outcome. */
    suspend fun settled() {
        signingIn?.await()
    }

    /**
     * A replacement for [signedIn]'s refused token, or null if they are no longer signed in, there
     * is no `fetchUserToken`, or it returned a token for someone else or for no one: one without
     * `sub` can't be told apart from another user's. Reuses a token another call
     * already refreshed. Throws [UserTokenError] when the fetch fails.
     */
    suspend fun refresh(signedIn: SignedInUser, refusedToken: String): String? {
        if (fetchUserToken == null) {
            refused(signedIn, refusedToken)
            return null
        }
        if (user !== signedIn) return null
        if (signedIn.token != refusedToken) return signedIn.token
        return refreshMutex.withLock {
            if (user !== signedIn) return@withLock null
            if (signedIn.token != refusedToken) return@withLock signedIn.token
            val fresh = fetchToken()
            val subject = JwtClaims.subject(fresh)
            if (user !== signedIn || subject == null || subject != signedIn.tokenSubject) return@withLock null
            signedIn.replaceToken(fresh, clock())
            fresh
        }
    }

    /**
     * Kirora refused [token]. With no fetchUserToken, nothing but identify() can replace it, so every
     * request as [signedIn] waits ([SignedInUser.waitingForToken]), whether or not they're still signed in.
     */
    fun refused(signedIn: SignedInUser, token: String) {
        if (fetchUserToken == null) signedIn.refusedToken = token
    }

    /**
     * [call] with [signedIn]'s token, asking for a fresh one once on a 401. Null, without calling,
     * while they wait for identify() to replace a refused token. Throws [UserTokenError] when the
     * fetch fails.
     */
    suspend fun <T> authorized(signedIn: SignedInUser, call: suspend (authorization: String) -> Response<T>): Response<T>? {
        if (signedIn.waitingForToken) return null
        val token = signedIn.token
        val response = call("Bearer $token")
        if (response.code() != 401) return response
        val fresh = refresh(signedIn, token) ?: return response
        return if (fresh == token) response else call("Bearer $fresh")
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
