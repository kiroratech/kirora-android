package com.kirora.androidsdk

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.gson.JsonObject
import com.kirora.androidsdk.db.EventEntity
import com.kirora.androidsdk.db.KiroraDatabase
import com.kirora.androidsdk.identity.Identity
import com.kirora.androidsdk.identity.SignedInUser
import com.kirora.androidsdk.model.AuthFailureReason
import com.kirora.androidsdk.model.BeforeSendEvent
import com.kirora.androidsdk.model.EventDropReason
import com.kirora.androidsdk.model.KiroraApiError
import com.kirora.androidsdk.model.KiroraError
import com.kirora.androidsdk.model.KiroraOptions
import com.kirora.androidsdk.model.KiroraState
import com.kirora.androidsdk.model.PushPermission
import com.kirora.androidsdk.model.PushStatus
import com.kirora.androidsdk.model.UserTokenError
import com.kirora.androidsdk.net.EventStore
import com.kirora.androidsdk.net.KiroraApiClient
import com.kirora.androidsdk.net.KiroraApiService
import com.kirora.androidsdk.net.KiroraAppOpenReporter
import com.kirora.androidsdk.net.KiroraBatchManager
import com.kirora.androidsdk.net.KiroraNetworkMonitor
import com.kirora.androidsdk.net.PendingPurges
import com.kirora.androidsdk.net.models.PushConsentRequest
import com.kirora.androidsdk.push.KiroraPushBridge
import com.kirora.androidsdk.push.KiroraPushDisplay
import com.kirora.androidsdk.push.KiroraPushManager
import com.kirora.androidsdk.push.KiroraPushTracker
import com.kirora.androidsdk.push.KiroraPushTrampolineActivity
import com.kirora.androidsdk.push.PushDisplayConfig
import com.kirora.androidsdk.push.PushListeners
import com.kirora.androidsdk.session.SessionManager
import com.kirora.androidsdk.utils.GlobalPreferences
import com.kirora.androidsdk.utils.JsonSnapshot
import com.kirora.androidsdk.utils.KiroraContextProvider
import com.kirora.androidsdk.utils.KiroraLogger
import com.kirora.androidsdk.utils.KiroraNamespace
import com.kirora.androidsdk.utils.KiroraPreferences
import com.kirora.androidsdk.utils.NotJsonError
import com.kirora.androidsdk.utils.RegistrationDetails
import com.kirora.androidsdk.utils.SdkInfo
import com.kirora.androidsdk.utils.toApiError
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The Kirora Android SDK: reports what each signed-in user does in the app, so segments, journeys
 * and messages can act on it; consent comes with it, and push is one channel that uses it.
 *
 * ```
 * val kirora = Kirora(applicationContext, KiroraOptions(clientKey = "kirora_pk_…", fetchUserToken = { myServer.kiroraToken() }))
 * kirora.identify()              // at every start while signed in, and after sign-in
 * kirora.track("Order Completed", mapOf("order_id" to "ORD-1042"))
 * kirora.logout()                // on sign-out
 * ```
 *
 * Create it once, in `Application.onCreate`. A second instance replaces the first: the previous one
 * is destroyed (its signed-in user carries over). Creating it never blocks on the network and never
 * asks for notification permission; a failing Kirora never breaks the app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class Kirora internal constructor(
    context: Context,
    options: KiroraOptions,
    private val environment: KiroraEnvironment
) {

    public constructor(context: Context, options: KiroraOptions) : this(context, options, KiroraEnvironment())

    @Volatile
    public var isInitialized: Boolean = false
        private set

    /** Why this client is disabled, for [identify] to throw: a setup error, never a runtime one. */
    @Volatile
    private var setupError: String? = null

    @Volatile
    public var options: KiroraOptions = KiroraOptions()
        private set

    @Volatile
    private var destroyed = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var preferences: KiroraPreferences? = null

    @Volatile
    private var global: GlobalPreferences? = null

    @Volatile
    private var contextProvider: KiroraContextProvider? = null

    /** The workspace and API this client stores and sends for. */
    @Volatile
    private var namespace: String? = null

    /** Opened on first use, off the main thread as a rule: finding and opening it touches the disk. */
    @Volatile
    private var databaseOpener: Lazy<KiroraDatabase?>? = null

    private val database: KiroraDatabase?
        get() = databaseOpener?.value

    @Volatile
    private var store: EventStore? = null

    @Volatile
    internal var apiService: KiroraApiService? = null
        private set

    @Volatile
    internal var batchManager: KiroraBatchManager? = null
        private set

    @Volatile
    internal var sessionManager: SessionManager? = null
        private set

    @Volatile
    internal var pushManager: KiroraPushManager? = null
        private set

    @Volatile
    internal var appOpens: KiroraAppOpenReporter? = null
        private set

    @Volatile
    private var networkMonitor: KiroraNetworkMonitor? = null

    internal val identity = Identity(options.fetchUserToken, environment.clock)

    /** Events a sign-in adopted, given back if Kirora refuses that user's token before accepting it once. */
    @Volatile
    private var adoption: Pair<SignedInUser, List<String>>? = null

    // ---- Tracking consent: the answer given to this client, for when storage can't be read ----

    /** The tracking choice in effect: read from storage, or the answer given to this client. */
    @Volatile
    private var consent: StoredConsent? = null

    /** When [consent] was last read, so a degraded read is retried now and then, never per event. */
    @Volatile
    private var consentReadAt = 0L

    /** Guards the first read of the tracking choice. Declared before init, which may need it. */
    private val consentReadLock = Any()

    // ---- Early token refresh ---------------------------------------------------------------

    @Volatile
    private var refreshJob: Job? = null

    @Volatile
    private var foregroundRefresh: Job? = null


    private val scope = CoroutineScope(SupervisorJob() + environment.io)

    /** For the callback forms: still runs after [destroy], so every callback is answered. */
    private val callbackScope = CoroutineScope(SupervisorJob() + environment.io)

    private val readiness = CompletableDeferred<Unit>()

    /** True once startup work that does not affect the API (stored look, channel) has finished. */
    public val isReady: Boolean
        get() = readiness.isCompleted

    /** Suspends until startup has finished. Nothing requires it: earlier calls are queued behind startup. */
    public suspend fun awaitReady() {
        readiness.await()
    }

    public val isIdentified: Boolean
        get() = identity.user != null

    public val sessionId: String?
        get() = sessionManager?.let { it.ensureBegun(); it.sessionId }

    /** The id sent in `context.device.id`, or null when there is none. Reading it never creates one. */
    public val deviceId: String?
        get() = preferences?.peekDeviceId()

    /** The FCM token in this device's current registration; null while there is none, even when Firebase holds a token. */
    public val pushToken: String?
        get() = pushManager?.registeredToken(identity.user)

    /** Whether behavioural tracking is allowed now. */
    public val trackingConsent: Boolean
        get() = trackingAllowed()

    /** The signed-in user's marketing push choice as this device last recorded it with Kirora; false when there is none. */
    public val pushConsent: Boolean
        get() = identity.user?.let { preferences?.recordedPushConsent(it.subject) } ?: false

    /**
     * A snapshot for an app's support and debug screens: read-only, never sent anywhere. Never
     * prompts, makes no network call and never fails, also before startup ends or after [destroy];
     * a field it can't read answers its empty value.
     */
    public suspend fun getState(): KiroraState = KiroraState(
        isInitialized = isInitialized && !destroyed,
        isIdentified = readSafely(false) { !destroyed && isIdentified },
        deviceId = readSafely(null) { deviceId },
        sessionId = readSafely(null) { sessionId },
        pushToken = readSafely(null) { pushToken },
        trackingConsent = readSafely(false) { trackingConsent },
        pushConsent = readSafely(false) { pushConsent },
        queuedEventCount = try {
            store?.count() ?: 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            0
        },
    )

    /** [getState] for callers without coroutines; [callback] runs on the main thread, also after [destroy]. */
    public fun getStateAsync(callback: (KiroraState) -> Unit) {
        // Not this client's scope: a destroyed client still answers.
        CoroutineScope(Dispatchers.IO).launch {
            val state = getState()
            withContext(Dispatchers.Main) {
                appCallback("getState's callback") { callback(state) }
            }
        }
    }

    private inline fun <T> readSafely(empty: T, read: () -> T): T = try {
        read()
    } catch (e: Exception) {
        empty
    }

    private val dropped = AtomicLong()

    /** Events Kirora has given up on since this instance started, whatever the reason. */
    internal val droppedEventCount: Long
        get() = dropped.get()

    private fun reportDropped(count: Int, reason: EventDropReason) {
        if (count <= 0) return
        dropped.addAndGet(count.toLong())
        KiroraLogger.w("Dropped $count event(s): $reason")
    }

    public fun getQueuedEventCountFlow(): Flow<Int>? = store?.countFlow()

    /** Forgets every event waiting on the device. */
    public fun clearQueue() {
        val queue = store ?: return
        scope.launchInQueue {
            try {
                queue.clearAll()
            } catch (e: Exception) {
                KiroraLogger.e("Error clearing event queue", e)
            }
        }
    }

    // Declared after every property above, so it runs with all of them set.
    init {
        try {
            synchronized(Kirora::class.java) { setup(context, options) }
        } catch (e: Throwable) {
            // A failing Kirora never breaks the app: it stays disabled.
            KiroraLogger.e("Kirora failed to start and stays disabled", e)
            setupError = "Kirora failed to start: ${e.javaClass.simpleName}"
            isInitialized = false
        }
        if (!isInitialized) readiness.complete(Unit)
    }

    private fun disable(reason: String) {
        setupError = reason
        KiroraLogger.e("$reason The SDK stays disabled.")
    }

    private fun setup(context: Context, options: KiroraOptions) {
        KiroraLogger.logLevel = options.logLevel
        val key = options.clientKey.trim()
        if (key.isEmpty()) return disable("Kirora needs a client key in its options (kirora_pk_…).")
        if (key.startsWith("kirora_sk_")) {
            return disable("That is a secret key (kirora_sk_…). Never ship one in an app: use the public client key (kirora_pk_…).")
        }
        if (!isApiUrlAllowed(options.apiUrl)) return disable("Kirora's apiUrl must be an https address.")

        val namespace = KiroraNamespace.of(options.apiUrl, key)
        val previous = active
        // Only a replacement for the same workspace and API keeps the signed-in user: another one
        // must never be handed this one's token, nor take its events.
        val carriedUser = previous?.identity?.user?.takeIf { previous.namespace == namespace }
        if (previous?.identity?.user != null && carriedUser == null) {
            KiroraLogger.w("The new Kirora client is for another workspace or API: the signed-in user is not carried over; call identify().")
        }
        previous?.destroy()
        active = this

        val app = context.applicationContext
        appContext = app
        this.options = options.withClientKey(key)
        this.namespace = namespace

        val prefs = KiroraPreferences(app, namespace)
        preferences = prefs
        val globalPrefs = GlobalPreferences(app)
        global = globalPrefs
        val provider = KiroraContextProvider(app, prefs)
        contextProvider = provider
        val db = lazy {
            try {
                KiroraDatabase.getInstance(app, namespace)
            } catch (e: Throwable) {
                KiroraLogger.e("Kirora can't open its event database", e)
                null
            }
        }
        databaseOpener = db
        val queue = EventStore(
            { db.value?.eventDao() },
            PendingPurges({ prefs.pendingPurges("events") }, { prefs.setPendingPurges("events", it) }, PURGE_RETRY_MS)
        )
        store = queue

        val api = environment.apiService ?: KiroraApiClient.create(
            baseUrl = this.options.apiUrl,
            clientKeyProvider = { this.options.clientKey },
            userTokenProvider = { identity.user?.token }
        )
        apiService = api

        val batchMgr = KiroraBatchManager(
            store = queue,
            apiService = api,
            scope = scope,
            identity = identity,
            trackingAllowed = ::trackingAllowed,
            onTokenRefused = ::onTokenRefused,
            onDropped = ::reportDropped,
            onAuthFailure = ::reportAuthFailure,
            clock = environment.clock
        )
        batchManager = batchMgr

        val pushMgr = KiroraPushManager(
            context = app,
            preferences = prefs,
            global = globalPrefs,
            apiService = api,
            scope = scope,
            identity = identity,
            pushOptions = this.options.push,
            trustedBaseUrl = this.options.apiUrl.toHttpUrlOrNull(),
            listeners = pushListeners,
            bridge = if (this.options.push != null) environment.pushBridge ?: loadPushBridge() else null,
            registrationDetails = { contextProvider?.registrationDetails() ?: RegistrationDetails() },
            onAuthFailure = ::reportAuthFailure,
            clock = environment.clock
        )
        pushManager = pushMgr

        val opens = KiroraAppOpenReporter(
            daoProvider = { db.value?.appOpenDao() },
            preferences = prefs,
            apiService = { apiService },
            scope = scope,
            identity = identity,
            registeredPushAppId = pushMgr::registeredPushAppId,
            trackingAllowed = ::trackingAllowed,
            onAuthFailure = ::reportAuthFailure,
            persistSpacing = this.options.trackSessions,
            purges = PendingPurges({ prefs.pendingPurges("app_opens") }, { prefs.setPendingPurges("app_opens", it) }, PURGE_RETRY_MS),
            clock = environment.clock
        )
        appOpens = opens

        // Already read at startup for the lifecycle events, so the queue reuses it.
        val appInfo = provider.app
        sessionManager = SessionManager(
            preferences = prefs,
            trackSessions = this.options.trackSessions,
            trackAppLifecycle = this.options.trackAppLifecycle,
            appVersion = appInfo.version.ifBlank { "unknown" },
            appBuild = appInfo.build.ifBlank { "unknown" },
            trackEvent = { name, properties -> track(name, properties) },
            owner = { identity.user?.subject },
            onBackground = { scope.launch { runFlush() } },
            onForeground = ::checkTokenExpiry,
            onAppOpen = opens::onAppOpen,
            isTrackingAllowed = ::trackingAllowed,
            clock = environment.clock,
            processLifecycle = { processLifecycle() }
        )

        val apiUrl = this.options.apiUrl
        val pushOptions = this.options.push
        val netMon = KiroraNetworkMonitor(app) {
            batchMgr.onNetworkAvailable()
            opens.onNetworkAvailable()
        }
        netMon.start()
        networkMonitor = netMon

        isInitialized = true
        // Off the caller's thread (often the main one, in Application.onCreate): everything read
        // from storage at start, what callers may wait on first. A call that needs one of them
        // sooner reads it then, and waits.
        scope.launch {
            try {
                ensureConsentRead()
                // What's stored from before the user refused, e.g. before consent was required.
                if (consentRefused()) forgetTracking()
                sessionManager?.ensureBegun()
                if (pushOptions != null) {
                    // Kept on the device so a push can be drawn, and a tap reported, before the app sets Kirora up.
                    val display = PushDisplayConfig.of(pushOptions)
                    globalPrefs.savePushDisplay(display.smallIconResId, display.accentColor, display.channelId, display.channelName)
                    KiroraPushDisplay.ensureDefaultChannel(app, display)
                }
                globalPrefs.saveApiUrl(apiUrl)
                opens.forgetUnusedSpacing()
                // Sign-outs a previous process couldn't finish detaching.
                pushMgr.resumePendingDetaches()
            } catch (e: Exception) {
                KiroraLogger.e("Kirora startup work failed", e)
            } finally {
                readiness.complete(Unit)
            }
        }
        // Watches the app's lifecycle, after the reads above are under way: a start already in
        // progress is replayed at once, on this thread.
        sessionManager?.start()
        if (carriedUser != null) {
            identity.restore(carriedUser)
            scope.launch {
                try {
                    afterSignIn(null, carriedUser)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    KiroraLogger.e("Resuming the signed-in user failed", e)
                }
            }
        }
        KiroraLogger.d("Kirora SDK initialized.")
    }

    // ---- Sign-in and sign-out ------------------------------------------------------------

    /**
     * Signs the user in with [token], or with one from `fetchUserToken` when omitted; registers the
     * device for push if allowed and sends waiting events. Call it at every start while the user is
     * signed in. If identify() is called again before a token arrives, the latest call wins.
     * Throws for the token: [UserTokenError], or [KiroraApiError] with status 401 when Kirora
     * refuses a fresh one; and [KiroraError] for a setup error (no token and no `fetchUserToken`, a
     * client key or `apiUrl` this client was disabled for). A push registration that fails otherwise
     * is logged.
     */
    public suspend fun identify(token: String? = null) {
        setupError?.let { throw KiroraError(it) }
        if (token == null && options.fetchUserToken == null) {
            throw KiroraError("identify() needs a user token, or set fetchUserToken in the options")
        }
        if (!isInitialized || destroyed) {
            KiroraLogger.w("Kirora.identify called on a disabled instance.")
            return
        }
        if (token != null && token.isBlank()) throw UserTokenError("identify() takes a user token, or nothing to use fetchUserToken")
        identity.signIn(token) { previous, user -> afterSignIn(previous, user) }
    }

    /**
     * [identify] for callers outside coroutines: returns at once and reports a failure to [onError].
     * With a [token], the user is signed in by the time this returns.
     */
    @JvmOverloads
    public fun identifyAsync(token: String? = null, onError: ((Throwable) -> Unit)? = null) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                identify(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KiroraLogger.e("identify failed", e)
                appCallback("identify's onError") { onError?.invoke(e) }
            }
        }
    }

    /**
     * Once signed in: adopt and send what waits, register the device for push, report a waiting
     * app open. A background authentication failure meanwhile (the first send's 401, say) waits
     * until this ends: identify() throws it instead when the token is the problem.
     */
    private suspend fun afterSignIn(previous: SignedInUser?, user: SignedInUser) {
        user.beginSignIn()
        var tokenProblem = false
        try {
            signInSteps(previous, user)
        } catch (e: Exception) {
            tokenProblem = e is UserTokenError || (e as? KiroraApiError)?.status == 401
            throw e
        } finally {
            val held = user.endSignIn(tokenProblem)
            // This client may have been replaced meanwhile: the failure is the new one's to report.
            held?.let { (if (destroyed) active else this)?.reportAuthFailure(it, user) }
        }
    }

    private suspend fun signInSteps(previous: SignedInUser?, user: SignedInUser) {
        // Superseded already (another identify(), or a sign-out): nothing here is this user's to change.
        if (identity.user !== user) return
        if (previous !== user) {
            // A's unsent events keep A as their owner; only the visit and A's marketing consent end.
            if (previous != null && previous.subject != user.subject) preferences?.clearUserData()
            // Also after a restart: a choice recorded for anyone else is theirs, not this user's.
            preferences?.forgetRecordedPushConsentUnlessOf(user.subject)
            val sessionOwner = sessionManager?.currentOwner
            if (sessionOwner != null && sessionOwner != user.subject) sessionManager?.reset()
        }
        scheduleTokenRefresh(user)
        val queue = store
        if (queue != null) {
            try {
                // On the queue's own dispatcher: everything tracked before this sign-in is stored first.
                inQueue {
                    if (identity.user !== user) return@inQueue
                    val keys = queue.adopt(user.subject)
                    adoption = if (keys.isNotEmpty() && !user.confirmed) user to keys else null
                    queue.clearUnnamedExcept(user.subject)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KiroraLogger.e("Couldn't adopt the events tracked before sign-in", e)
            }
        }
        if (identity.user !== user) return
        scope.launch { runFlush() }
        // Only a token problem reaches the caller, after the rest of the sign-in has run.
        var tokenProblem: Exception? = null
        try {
            pushManager?.register(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UserTokenError) {
            tokenProblem = e
        } catch (e: KiroraApiError) {
            if (e.status == 401) tokenProblem = e else KiroraLogger.e("Push registration failed", e)
        } catch (e: Exception) {
            KiroraLogger.e("Push registration failed", e)
        }
        appOpens?.onSignedIn(user)
        tokenProblem?.let { throw it }
        KiroraLogger.d("User identified.")
    }

    /** A sign-in whose token Kirora never accepted gives back the events it adopted. */
    private fun onTokenRefused(user: SignedInUser) {
        val taken = adoption ?: return
        if (taken.first !== user || user.confirmed) return
        adoption = null
        scope.launchInQueue {
            try {
                store?.release(user.subject, taken.second)
            } catch (e: Exception) {
                KiroraLogger.e("Couldn't release adopted events", e)
            }
        }
    }

    /**
     * Signs the user out at once: whatever is tracked from now on waits for the next sign-in, and
     * a sign-in still waiting for its token is abandoned. Then attempts to send the outgoing user's
     * events and detach this device from them, within one 5-second deadline, and drops their unsent
     * events, and those waiting from before sign-in (with nobody signed in, only those that
     * continued a signed-in user's visit). Call it only from the app's sign-out. Never fails; safe
     * to call twice. With nobody signed in (after a relaunch, before [identify]), it still detaches
     * the device's last registration, by its device secret. A
     * detachment that fails is retried in the background (30 seconds, doubling to 5 minutes, for 24
     * hours from the sign-out) with the outgoing user's token, held in memory only; after a restart,
     * with the device secret, kept on the device with the registration.
     */
    public suspend fun logout() {
        if (!isInitialized || destroyed) return
        val deadline = environment.clock() + LOGOUT_DEADLINE_MS
        // At once, before anything waits: the outgoing user is captured, a sign-in still waiting
        // for its token is abandoned (this logout came after it), and whatever is tracked from now
        // on belongs to no one. The abandoned sign-in would have adopted the waiting events and been
        // signed out with them, so they go as if it had landed.
        val ended = identity.end()
        val user = ended.user
        // Before anything waits: a sign-in made meanwhile must not have its state cleared by this one.
        if (user != null) signOutLocally(user)
        try {
            // Side by side, each started at once ([now]): the drop takes its place in the queue ahead
            // of any later sign-in's adoption, and the detachment is captured (and kept on the device)
            // without waiting behind the queue, so a destroy() or the process ending can't lose it.
            coroutineScope {
                fun now(block: suspend () -> Unit) = launch(start = CoroutineStart.UNDISPATCHED) { block() }
                now { dropWaitingFromBeforeSignIn(signedIn = user != null || ended.abandonedSignIn) }
                if (user == null) {
                    // Nobody signed in (a relaunch, before identify()): the last registration goes by its device secret.
                    now { pushManager?.detachLastRegistration(deadline) }
                } else {
                    // Bounded by the deadline itself, so a cut-off attempt is still retried in the background.
                    now { pushManager?.detach(user, deadline) }
                    // With the outgoing user's token, beside the detachment so it doesn't eat its share of the deadline.
                    launch {
                        val flushed = withTimeoutOrNull((deadline - environment.clock()).coerceAtLeast(1L)) {
                            batchManager?.flushAs(user)
                            true
                        } != null
                        if (!flushed) KiroraLogger.e("Gave up waiting for Kirora; the user's unsent events are dropped.")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KiroraLogger.e("Logout failed to clean up", e)
        }
        if (user == null) return
        dropOwnedBy(user)
        KiroraLogger.d("User logged out.")
    }

    /** [logout] for callers outside coroutines: returns at once, then calls [onComplete]. */
    @JvmOverloads
    public fun logoutAsync(onComplete: (() -> Unit)? = null) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                logout()
            } finally {
                appCallback("logout's onComplete") { onComplete?.invoke() }
            }
        }
    }

    /**
     * At a sign-out, the events waiting from before sign-in, so the next user doesn't adopt them.
     * With someone [signedIn] (or signing in), all of them. With nobody, only those that continued a signed-in
     * user's visit: an app that restored its own session after a relaunch, before identify(), and
     * the user it signs out made them. A visitor who never signed in keeps theirs (a sign-up funnel),
     * and so does every event when sessions are off, as nothing tells them apart. Those tracked
     * from here on wait for the next user.
     */
    private suspend fun dropWaitingFromBeforeSignIn(signedIn: Boolean) {
        val queue = store ?: return
        try {
            // On the queue's own dispatcher: everything tracked before this sign-out is stored first.
            val dropped = inQueue { queue.clearUnowned(onlySignedInVisits = !signedIn) }
            reportDropped(dropped, EventDropReason.UNDELIVERABLE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KiroraLogger.e("Couldn't drop the events waiting from before sign-in", e)
        }
    }

    /** The local part of signing [user] out, done before any network work. */
    private fun signOutLocally(user: SignedInUser) {
        if (adoption?.first === user) adoption = null
        refreshJob?.cancel()
        refreshJob = null
        foregroundRefresh?.cancel()
        foregroundRefresh = null
        sessionManager?.reset()
        preferences?.clearUserData()
    }

    /** After the flush: what is left of [user]'s is dropped, so it never goes as the next user. */
    private fun dropOwnedBy(user: SignedInUser) {
        // Unless they signed in again meanwhile: then it's theirs to send.
        if (identity.user?.subject != user.subject) {
            scope.launchInQueue {
                try {
                    reportDropped(store?.clearOwnedBy(user.subject) ?: 0, EventDropReason.UNDELIVERABLE)
                } catch (e: Exception) {
                    KiroraLogger.e("Couldn't drop the signed-out user's events", e)
                }
            }
            appOpens?.clearOwnedBy(user.subject)
        }
        // Kept past a consent withdrawal only so the device could be detached.
        if (consentRefused()) forgetDeviceIdIfUnused()
    }

    // ---- Background authentication failures --------------------------------------------------

    /**
     * Tells the app, on the main thread, that [user] can't stay authenticated in the background.
     * Once per failure: again only after Kirora accepts their token or identify() is called.
     * Held while identify() runs for them. Quiet once they are no longer the one
     * signed in.
     */
    @Synchronized
    private fun reportAuthFailure(reason: AuthFailureReason, user: SignedInUser) {
        val callback = options.onAuthenticationFailed
        if (destroyed || identity.user !== user) return
        val token = user.claimAuthFailure(reason) ?: return
        KiroraLogger.e("Kirora can't keep the user signed in (${reason.name}); call identify() again.")
        if (callback == null) return
        callbackScope.launch(Dispatchers.Main) {
            // Checked again once on the main thread: a sign-out, another user, or a token Kirora
            // accepted or identify() gave since makes this failure stale.
            val current = synchronized(this@Kirora) {
                !destroyed && identity.user === user && user.authFailureReportedFor == token
            }
            if (!current) return@launch
            appCallback("onAuthenticationFailed") { callback(reason) }
        }
    }

    // ---- Early token refresh ---------------------------------------------------------------

    /** The wait is wall-clock based but `delay` pauses in deep sleep, hence also [checkTokenExpiry] on foreground. */
    @Synchronized
    private fun scheduleTokenRefresh(user: SignedInUser) {
        // Late work for someone no longer signed in never touches the current user's refresh.
        if (identity.user !== user) return
        refreshJob?.cancel()
        refreshJob = null
        if (options.fetchUserToken == null) return
        val (token, expiry) = user.current
        val expiresAt = expiry ?: return
        val due = expiresAt - TOKEN_REFRESH_LEAD_MS - environment.clock()
        // fetchUserToken handed back a token no longer-lived than this user's last one: refreshing again would loop.
        if (due <= 0 && expiresAt <= user.refreshScheduledFor) return
        user.refreshScheduledFor = expiresAt
        val wait = due.coerceAtLeast(minRefreshDelayMs)
        refreshJob = scope.launch {
            delay(wait)
            refreshAhead(user, token, expiresAt)
        }
    }

    /** Called when the app comes to the foreground: the token may have run out while the process slept. */
    private fun checkTokenExpiry() {
        val user = identity.user ?: return
        if (options.fetchUserToken == null) return
        val (token, expiry) = user.current
        val expiresAt = expiry ?: return
        if (expiresAt - TOKEN_REFRESH_LEAD_MS > environment.clock()) return
        if (foregroundRefresh?.isActive == true) return
        foregroundRefresh = scope.launch { refreshAhead(user, token, expiresAt) }
    }

    /**
     * Refreshes [stale] before it expires at [expiresAt]. A failure is retried, not reported, while
     * the token is still valid; once it has expired and a refresh still fails, the app is told (FETCH_FAILED).
     */
    private suspend fun refreshAhead(user: SignedInUser, stale: String, expiresAt: Long) {
        var attempt = 0
        while (identity.user === user) {
            val failure: UserTokenError? = try {
                val fresh = identity.refresh(user, stale)
                if (fresh != null && fresh != stale) {
                    scheduleTokenRefresh(user)
                    return
                }
                // Null while still signed in with the same token: the token was for someone else, or no one.
                if (identity.user === user && user.token == stale) UserTokenError("fetchUserToken returned a token for another user, or without sub") else return
            } catch (e: CancellationException) {
                throw e
            } catch (e: UserTokenError) {
                e
            }
            val now = environment.clock()
            if (now >= expiresAt) {
                KiroraLogger.e("Could not refresh the user token, which has expired.", failure)
                reportAuthFailure(AuthFailureReason.FETCH_FAILED, user)
                return
            }
            KiroraLogger.w("Could not refresh the user token before it expires; trying again.", failure)
            // Doubling, but never past the expiry: the attempt right after it decides.
            val wait = (refreshRetryMs shl minOf(attempt++, 5)).coerceAtMost(maxOf(expiresAt - now, 1L))
            delay(wait)
        }
    }

    // ---- Tracking --------------------------------------------------------------------------

    /** Queues an event and returns at once. Never throws; [properties] are copied now. */
    @JvmOverloads
    public fun track(name: String, properties: Map<String, Any?>? = null) {
        if (inBeforeSend.get() == true) {
            KiroraLogger.e("Not tracked: called from beforeSend")
            return
        }
        try {
            trackInternal(name, properties)
        } catch (e: Throwable) {
            KiroraLogger.e("Failed to track an event", e)
        }
    }

    private fun trackInternal(name: String, properties: Map<String, Any?>?) {
        val queue = store
        val provider = contextProvider
        if (!isInitialized || destroyed || queue == null || provider == null) {
            KiroraLogger.w("Kirora.track called on a disabled instance; not tracked.")
            return
        }
        if (!trackingAllowed()) return
        if (name.isBlank()) {
            KiroraLogger.e("Not tracked: an event needs a name")
            reportDropped(1, EventDropReason.INVALID)
            return
        }
        // Copied now, so changing the map after track() doesn't change the event.
        val snapshot = try {
            JsonSnapshot.of(properties)
        } catch (e: NotJsonError) {
            KiroraLogger.e("$name not tracked: its properties hold ${e.message}")
            reportDropped(1, EventDropReason.INVALID)
            return
        }
        // Activity first: it may begin a visit with `Session Started`, queued ahead of this event.
        sessionManager?.onActivity()
        val occurredAt = environment.clock()
        val sessionId = sessionManager?.sessionId
        // Decided now: whoever signs in or out later, this event stays this user's.
        val owner = identity.user?.subject
        // Before sign-in, whose visit it continues: adoption by anyone else drops its sessionId.
        val visitOwner = if (owner == null && sessionId != null) sessionManager?.currentOwner else null
        // Captured with the event, not when the queue gets to it.
        val capturedContext = provider.getContext(sessionId)

        // Not on this client's scope: destroy() must not cancel an event tracked before it, which
        // waits in the database for the next client. (With no database, only in memory, it is
        // this client's and goes with it, like the rest of its memory queue.)
        // On the queue's thread, in call order; only the store write takes the queue's lock, so the
        // next event's JSON is built while this one's waits on Room, and still lands after it.
        queueWrites.launch(queueWork) {
            try {
                // Consent withdrawn while this was waiting. A destroy() meanwhile doesn't drop it:
                // it waits on the device for the next client. (A withdrawal through that client
                // forgets queued events on this same queue, after this write.)
                if (!consentAllowsTracking()) return@launch
                var context = capturedContext.toJson(provider.app)
                var props = snapshot
                options.beforeSend?.let { hook ->
                    val redacted = redact(hook, name, props, context) ?: return@launch
                    props = redacted.first
                    context = redacted.second
                }
                val propertiesJson = props.toString()
                val contextJson = context.toString()
                val size = JsonSnapshot.utf8Length(name) + JsonSnapshot.utf8Length(propertiesJson) +
                    JsonSnapshot.utf8Length(contextJson) + JsonSnapshot.utf8Length(owner ?: "") + EVENT_OVERHEAD_BYTES
                if (size > EventStore.MAX_BYTES) {
                    KiroraLogger.e("$name not tracked: larger than the event queue")
                    reportDropped(1, EventDropReason.INVALID)
                    return@launch
                }
                val entity = EventEntity(
                    idempotencyKey = UUID.randomUUID().toString(),
                    name = name,
                    propertiesJson = propertiesJson,
                    contextJson = contextJson,
                    occurredAt = occurredAt,
                    owner = owner,
                    visitOwner = visitOwner,
                    sizeBytes = size
                )
                queueOrder.withLock {
                    reportDropped(queue.add(entity), EventDropReason.QUEUE_FULL)
                    batchManager?.onEventQueued()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KiroraLogger.e("$name not tracked: it could not be queued", e)
            }
        }
    }

    /**
     * The event as `beforeSend` leaves it, or null when it drops it. Only the kind of failure is
     * logged, never the event: it may be what the hook was there to hide.
     */
    private fun redact(
        hook: (BeforeSendEvent) -> BeforeSendEvent?,
        name: String,
        properties: JsonObject,
        context: JsonObject
    ): Pair<JsonObject, JsonObject>? {
        val answer = try {
            inBeforeSend.set(true)
            hook(BeforeSendEvent(name, JsonSnapshot.toMap(properties), JsonSnapshot.toMap(context)))
        } catch (e: Throwable) {
            KiroraLogger.e("$name not tracked: beforeSend threw")
            return null
        } finally {
            inBeforeSend.set(false)
        }
        if (answer == null) {
            KiroraLogger.d("$name dropped by beforeSend")
            return null
        }
        return try {
            JsonSnapshot.of(answer.properties) to JsonSnapshot.of(answer.context)
        } catch (e: NotJsonError) {
            KiroraLogger.e("$name not tracked: beforeSend answered ${e.message}")
            null
        }
    }

    /** Records a `Screen Viewed` event. Screens are named by the app. */
    public fun trackScreenView(name: String) {
        track("Screen Viewed", mapOf("name" to name))
    }

    // ---- Consent ---------------------------------------------------------------------------

    /** What the device has on record: each copy read on its own, null where it can't be read. */
    private class StoredConsent(val answer: Boolean?, val answerReadable: Boolean, val refusalCopy: Boolean?) {
        val refused: Boolean get() = answer == false || refusalCopy == true
        val fullyRead: Boolean get() = answerReadable && refusalCopy != null
    }

    private fun readStoredConsent(): StoredConsent {
        val prefs = preferences ?: return StoredConsent(null, false, null)
        var readable = true
        val answer = try {
            prefs.readTrackingConsent()
        } catch (_: Throwable) {
            readable = false
            null
        }
        val copy = try {
            prefs.readRefusalCopy()
        } catch (_: Throwable) {
            null
        }
        return StoredConsent(answer, readable, copy)
    }

    /** The tracking choice as read at start: by the startup work, or by whatever needs it first. */
    private fun ensureConsentRead() {
        if (consent != null) return
        synchronized(consentReadLock) { if (consent == null) readConsentAtStart() }
    }

    /**
     * The choice in effect, from memory. Storage is read at start; a read that couldn't see both
     * copies is tried again at most every [CONSENT_REREAD_MS] (before first unlock, say), so
     * tracking never touches the disk per event.
     */
    private fun currentConsent(): StoredConsent {
        ensureConsentRead()
        val known = consent
        val now = environment.clock()
        if (known != null && (known.fullyRead || now - consentReadAt < CONSENT_REREAD_MS)) return known
        val reread = readStoredConsent()
        return synchronized(consentReadLock) {
            // An answer given while this read was under way is newer than anything it found.
            if (consent !== known) return@synchronized consent ?: reread
            consent = reread
            consentReadAt = now
            reread
        }
    }

    private fun readConsentAtStart() {
        // A client replaced a moment ago may still be writing its choice: that choice is the one.
        unsavedConsent[namespace]?.granted?.let { granted ->
            consent = StoredConsent(answer = granted, answerReadable = true, refusalCopy = !granted)
            consentReadAt = environment.clock()
            return
        }
        val stored = readStoredConsent()
        consent = stored
        consentReadAt = environment.clock()
        // Read only: never repaired here (sdk-interface.md, "Tracking consent"). A grant clears the
        // refusal copy before it records itself, so a "repair" made between the two, or after a
        // grant another process completed since this read, would undo the grant for good. A
        // refusal in either copy already counts; only setTrackingConsent writes.
        if (stored.fullyRead && (stored.answer == false) == (stored.refusalCopy == true)) return
        val effect = when {
            stored.refused ->
                "one copy of the tracking refusal is missing or unreadable: the other still holds it, so nothing is tracked."
            !stored.answerReadable && stored.refusalCopy == null ->
                "neither copy of the tracking choice can be read: nothing is tracked until one can, or setTrackingConsent answers."
            options.requireTrackingConsent && !stored.answerReadable ->
                "the saved tracking choice can't be read: nothing is tracked until setTrackingConsent(true); a grant is never assumed."
            !stored.answerReadable ->
                "the saved tracking choice can't be read and no refusal is on record: tracking as on a fresh install."
            else ->
                "the second copy of a tracking refusal can't be read: following the saved choice."
        }
        KiroraLogger.e("Kirora: $effect")
    }

    /** Saves the choice off the caller's thread, one write after another, surviving [destroy]. */
    private fun persistConsent(granted: Boolean) {
        val prefs = preferences ?: return
        val key = namespace ?: return
        // Each write its own entry: a later one with the same answer (false, true, false) isn't removed by this one.
        val pending = PendingChoice(granted)
        unsavedConsent[key] = pending
        lastConsentWrite = consentWrites.launch {
            val saved = try {
                prefs.writeTrackingConsent(granted)
            } finally {
                // Unless a later choice is waiting behind this one.
                unsavedConsent.remove(key, pending)
            }
            when {
                saved -> KiroraLogger.d("Tracking choice saved: ${if (granted) "granted" else "refused"}.")
                else -> KiroraLogger.e("Can't save the tracking choice on this device: it holds in memory until the app closes, so ask again on the next start.")
            }
        }
    }

    /** A refusal on record in either copy, or given to this client. */
    private fun consentRefused(): Boolean = currentConsent().refused

    /**
     * A refusal in either copy always wins; with `requireTrackingConsent`, only a readable grant
     * allows tracking. Without it, tracking goes on as on a fresh install when the saved choice
     * can't be read but the refusal copy was read and holds none; with neither copy readable,
     * nothing is collected.
     */
    private fun trackingAllowed(): Boolean = !destroyed && consentAllowsTracking()

    /** The tracking choice alone, whether or not this client has been destroyed since. */
    private fun consentAllowsTracking(): Boolean {
        val state = currentConsent()
        if (state.refused) return false
        if (options.requireTrackingConsent) return state.answerReadable && state.answer == true
        return state.answerReadable || state.refusalCopy == false
    }

    /**
     * The user's tracking choice, remembered per workspace on the device. `false` stops behavioural
     * collection even when `requireTrackingConsent` is false, forgets queued events, app opens and
     * the visit, and cancels sends under way. It does not revoke marketing push consent, turn push
     * off, or stop operational push reports.
     */
    public fun setTrackingConsent(granted: Boolean) {
        val prefs = preferences
        if (!isInitialized || destroyed || prefs == null) {
            KiroraLogger.w("Kirora.setTrackingConsent called on a disabled instance; ignored.")
            return
        }
        val before = trackingAllowed()
        // Takes effect at once, whether or not it can be saved; saved off this thread.
        synchronized(consentReadLock) {
            consent = StoredConsent(answer = granted, answerReadable = true, refusalCopy = !granted)
            consentReadAt = environment.clock()
        }
        persistConsent(granted)
        if (!granted) {
            batchManager?.cancel()
            forgetTracking()
        } else if (!before && trackingAllowed()) {
            // Tracking starts from now; nothing skipped before is made up.
            sessionManager?.onConsentGranted()
            scope.launch { batchManager?.flush(waiveBackoff = true) }
        }
        KiroraLogger.d("Tracking consent set to: $granted")
    }

    /** Forgets what behavioural tracking stored, as when the user refuses it. */
    private fun forgetTracking() {
        appOpens?.clearAll()
        sessionManager?.reset()
        val queue = store
        if (queue != null) {
            scope.launchInQueue {
                try {
                    queue.clearAll()
                } catch (e: Exception) {
                    KiroraLogger.e("Couldn't clear events", e)
                }
            }
        }
        forgetDeviceIdIfUnused()
    }

    /** The device id is also the installation id, so a push registration or detachment may still need it. */
    private fun forgetDeviceIdIfUnused() {
        if (pushManager?.needsDeviceId != true) preferences?.forgetDeviceId()
    }

    // ---- Push ------------------------------------------------------------------------------

    /**
     * Records the signed-in user's consent to marketing push with Kirora (`PUT /push-consent`), with
     * proof. Not behavioural tracking consent, nor the OS permission. Returns once Kirora has
     * recorded it; throws when it hasn't, so the app learns the choice was not saved:
     * [KiroraApiError] when Kirora refuses it (a refused token included), [UserTokenError] when a
     * fresh token can't be had, and [KiroraError] with no one signed in, for a setup error or a
     * failed network call. Nothing is retried in the background.
     */
    public suspend fun setPushConsent(granted: Boolean) {
        setupError?.let { throw KiroraError(it) }
        if (destroyed) throw KiroraError("this Kirora client was destroyed: nothing was recorded")
        identity.settled()
        consentCalls.withLock { recordPushConsent(granted) }
    }

    /** One push-consent call at a time, so the saved value is the one Kirora applied last. */
    private val consentCalls = Mutex()

    private suspend fun recordPushConsent(granted: Boolean) {
        val user = identity.user ?: throw KiroraError("setPushConsent needs a signed-in user: call identify() first")
        val api = apiService ?: throw KiroraError("Kirora is not set up")
        val response = try {
            identity.authorized(user) { api.updatePushConsent(PushConsentRequest(granted), it) }
                // A token Kirora refused, with no fetchUserToken to replace it: refused again, without asking.
                ?: throw KiroraApiError(401, "UNAUTHORIZED", "Kirora refused the user token: call identify() with a new one")
        } catch (e: CancellationException) {
            throw e
        } catch (e: KiroraError) {
            throw e
        } catch (e: Exception) {
            throw KiroraError("push consent was not recorded: network error", e)
        }
        if (!response.isSuccessful) throw response.toApiError()
        // The user's choice, for getState(): kept only while they're the one signed in.
        if (identity.user?.subject == user.subject) {
            preferences?.setRecordedPushConsent(user.subject, granted)
            // Signed out in between: the choice was theirs, so it goes with them.
            if (identity.user?.subject != user.subject) preferences?.forgetRecordedPushConsentUnlessOf(identity.user?.subject ?: "")
        }
        KiroraLogger.d("Push consent recorded: $granted")
    }

    /** [setPushConsent] for callers outside coroutines; [onResult] gets null once recorded, or the error. */
    @JvmOverloads
    public fun setPushConsentAsync(granted: Boolean, onResult: ((KiroraError?) -> Unit)? = null) {
        callbackScope.launch {
            val error = try {
                setPushConsent(granted)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: KiroraError) {
                e
            } catch (e: Exception) {
                KiroraError("push consent was not recorded", e)
            }
            if (error != null) KiroraLogger.e("setPushConsent failed", error)
            appCallback("setPushConsent's callback") { onResult?.invoke(error) }
        }
    }

    /**
     * Shows the system notification prompt when needed, then registers the device if granted, and
     * answers `granted`, `denied`, `unanswered` or `unsupported`. `granted` also turns the device's
     * push back on after [setPushEnabled]`(false)`. Does not grant marketing consent.
     */
    public suspend fun requestPushPermission(context: Context): PushPermission =
        suspendCancellableCoroutine { cont ->
            requestPushPermission(context) { result -> if (cont.isActive) cont.resume(result) }
        }

    public fun requestPushPermission(context: Context, callback: (PushPermission) -> Unit) {
        val manager = pushManager
        if (manager == null || destroyed) callback(PushPermission.UNSUPPORTED) else manager.requestPushPermission(context, callback)
    }

    /**
     * This device's push, for an app's "notifications on/off" setting. Never prompts; waits for a
     * sign-in under way. The platform's permission first (`denied`, `unanswered`, `unsupported`);
     * with it granted, `enabled` when this device is registered for the signed-in user, `disabled`
     * otherwise (turned off, signed out, or the registration failed).
     */
    public suspend fun getPushStatus(): PushStatus {
        val manager = pushManager ?: return PushStatus.UNSUPPORTED
        if (destroyed) return PushStatus.UNSUPPORTED
        identity.settled()
        return manager.status(identity.user)
    }

    /**
     * The device's own push on/off, kept in its storage, not the user's: it survives sign-out and
     * applies to whoever signs in next. `false` detaches the device (retried in the background as
     * logout's detachment is); while it holds, `identify()` does not register. `true` forgets the
     * choice and registers if permission is already granted, never prompting. Never fails. Not
     * marketing consent: that is [setPushConsent].
     */
    public suspend fun setPushEnabled(enabled: Boolean) {
        val manager = pushManager ?: return
        if (destroyed) return
        identity.settled()
        try {
            manager.setEnabled(enabled, identity.user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KiroraLogger.e("Couldn't turn push ${if (enabled) "on" else "off"}", e)
        }
    }

    /** [getPushStatus] for callers outside coroutines, such as a React Native or Flutter bridge. */
    public fun getPushStatusAsync(callback: (PushStatus) -> Unit) {
        callbackScope.launch {
            val status = try {
                getPushStatus()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KiroraLogger.e("Couldn't read the push status", e)
                PushStatus.UNSUPPORTED
            }
            appCallback("getPushStatusAsync's callback") { callback(status) }
        }
    }

    /** [setPushEnabled] for callers outside coroutines. Never fails. */
    @JvmOverloads
    public fun setPushEnabledAsync(enabled: Boolean, onComplete: (() -> Unit)? = null) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                setPushEnabled(enabled)
            } finally {
                appCallback("setPushEnabledAsync's onComplete") { onComplete?.invoke() }
            }
        }
    }

    /** [flush] for callers outside coroutines: [onComplete] runs when this attempt stops. */
    @JvmOverloads
    public fun flushAsync(onComplete: (() -> Unit)? = null) {
        callbackScope.launch {
            try {
                flush()
            } finally {
                appCallback("flushAsync's onComplete") { onComplete?.invoke() }
            }
        }
    }

    /**
     * May be called before an instance exists; listeners survive a second instance. Hears taps on
     * Kirora pushes and their buttons, with the push's data, after the SDK has reported the tap. A
     * tap that launched the app before anyone listened is held (the latest ten) and delivered when
     * a listener attaches.
     */
    public fun addNotificationClickListener(listener: (Map<String, String>) -> Unit) {
        pushListeners.click.add(listener)
        // A tap that launched the app arrives before the app has a chance to listen.
        val buffered = synchronized(pushListeners.pendingClicks) {
            pushListeners.pendingClicks.toList().also { pushListeners.pendingClicks.clear() }
        }
        buffered.forEach { data ->
            appCallback("notification click listener") { listener(data) }
        }
    }

    public fun removeNotificationClickListener(listener: (Map<String, String>) -> Unit) {
        pushListeners.click.remove(listener)
    }

    public fun addForegroundNotificationListener(listener: (Map<String, String>) -> Unit) {
        pushListeners.foreground.add(listener)
    }

    public fun removeForegroundNotificationListener(listener: (Map<String, String>) -> Unit) {
        pushListeners.foreground.remove(listener)
    }

    /** For apps with their own `FirebaseMessagingService`: forward `onNewToken` here. */
    public fun onNewPushToken(token: String) {
        val manager = pushManager
        if (manager != null && !destroyed) manager.onNewToken(token) else global?.setPushToken(token)
    }

    /** True when the push data carries `kirora: "true"`: a Kirora push. Any other push is the app's. */
    public fun isKiroraPush(data: Map<String, String>): Boolean = Companion.isKiroraPush(data)

    /**
     * For apps with their own `FirebaseMessagingService`: forward `onMessageReceived` data here.
     * Returns `true` if it was a Kirora push (now drawn), `false` otherwise.
     */
    public fun onPushMessageReceived(data: Map<String, String>): Boolean {
        val manager = pushManager
        if (manager != null) return manager.onMessageReceived(data)
        val context = appContext ?: return Companion.isKiroraPush(data)
        return handlePushMessage(context, data)
    }

    /**
     * Reports a notification tap found in [intent], for apps that show Kirora's notifications some
     * other way. Taps on the notifications the SDK draws are reported by the SDK itself.
     */
    public fun handleNotificationIntent(intent: Intent?): Boolean = pushManager?.handleNotificationIntent(intent) ?: false

    // ---- Delivery and lifecycle -------------------------------------------------------------

    /**
     * Attempts to send the signed-in user's waiting events now, without bypassing consent or
     * `Retry-After`. Returns when this attempt stops, including when events remain for a retry; it
     * is not a receipt that every event was accepted.
     */
    public suspend fun flush() {
        if (!isInitialized || destroyed) return
        afterQueuedWrites()
        batchManager?.flush(waiveBackoff = true)
    }

    /** Waits for every event tracked so far to be stored, so a flush now carries them. */
    private suspend fun afterQueuedWrites() = inQueue { }

    private suspend fun runFlush() {
        try {
            afterQueuedWrites()
            batchManager?.flush()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KiroraLogger.e("Error flushing events", e)
        }
    }

    /**
     * Stops this client's timers and listeners at once, without sending or deleting anything: for
     * hot reload, tests and apps that replace their client. A destroyed client tracks and sends
     * nothing; events still waiting stay on the device for the next client.
     */
    public fun destroy() {
        if (destroyed) return
        destroyed = true
        isInitialized = false
        // Forgetting the user stops work under way for them at its next step.
        identity.end()
        batchManager?.destroy()
        appOpens?.destroy()
        pushManager?.destroy()
        refreshJob?.cancel()
        foregroundRefresh?.cancel()
        networkMonitor?.stop()
        networkMonitor = null
        sessionManager?.stop()
        scope.cancel()
        synchronized(Kirora::class.java) {
            if (active === this) active = null
        }
    }

    private fun loadPushBridge(): KiroraPushBridge? = try {
        Class.forName(PUSH_BRIDGE_CLASS).getDeclaredConstructor().newInstance() as KiroraPushBridge
    } catch (_: Throwable) {
        KiroraLogger.e("push is on, but the android-sdk-push artifact is not on the classpath.")
        null
    }

    private fun isApiUrlAllowed(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (parsed.isHttps) return true
        return parsed.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
    }

    internal val databaseForTesting: KiroraDatabase?
        get() = database

    public companion object {
        private val pushListeners = PushListeners()

        /** True while `beforeSend` runs on this thread, so tracking from inside it is refused. */
        private val inBeforeSend = ThreadLocal<Boolean>()

        /**
         * For the React Native and Flutter wrappers: the `Kirora-SDK` header names the wrapper
         * (`kirora-react-native`, `kirora-flutter`) and its version. Call before the first
         * instance. False, changing nothing, for a name or version Kirora would refuse.
         */
        @KiroraInternalApi
        @JvmStatic
        public fun setWrapperSdk(name: String, version: String): Boolean = SdkInfo.setWrapper(name, version)

        /**
         * For the React Native and Flutter wrappers: callbacks the app answers across an async bridge.
         * Each answer is awaited at most 1.5 seconds; without one in time the SDK acts as if there
         * were no callback (opens the link, shows the push). Used when the matching native option
         * (`handleDeepLink`, `onForegroundNotification`) is not set.
         */
        @KiroraInternalApi
        @JvmStatic
        public fun setWrapperCallbacks(
            handleDeepLink: (suspend (url: String) -> Boolean)?,
            onForegroundNotification: (suspend (data: Map<String, String>) -> Boolean)?
        ) {
            wrapperDeepLinkHandler = handleDeepLink
            wrapperForegroundHandler = onForegroundNotification
            wrapperCallbacksSet.complete(Unit)
        }

        /**
         * For the React Native and Flutter wrappers ("Push at launch"): call from native code at app
         * launch, before the client exists. Android's push handling (the FCM service and the tap
         * activity) is in the manifest, so it already works without a client; this makes the link
         * of a tap that launched the app wait for the wrapper's first [setWrapperCallbacks], with a
         * handler or none, for at most 10 seconds, and then opens it as `handleDeepLink` says.
         *
         * Call it from a `ContentProvider` or `androidx.startup` initializer in the plugin's manifest,
         * so it runs before the tap's activity. A later call still covers the next launches of this
         * install of the app: it is remembered until the app is updated.
         */
        @KiroraInternalApi
        @JvmStatic
        public fun installPushIntegration(context: Context) {
            launchIntegration = true
            appUpdateTime(context.applicationContext)?.let { GlobalPreferences(context.applicationContext).setWrapperPushInstall(it) }
        }

        /**
         * For the React Native and Flutter wrappers: the wrapper's engine is gone (Flutter's
         * `onDetachedFromEngine`, React Native's `invalidate`). Forgets its callbacks, and a later tap's
         * link waits again (at most 10 seconds) for the next engine's [setWrapperCallbacks] instead of
         * opening before the app can route it. Use this, not `setWrapperCallbacks(null, null)`, which
         * means "set up, with no handler".
         *
         * The wrapper's tap and foreground listeners are added on its client like any app's
         * (`addNotificationClickListener`), so the wrapper removes them itself as it detaches; with
         * no tap listener left, taps are held again (the latest ten) for the next engine's listener.
         */
        @KiroraInternalApi
        @JvmStatic
        public fun wrapperDetached() {
            wrapperDeepLinkHandler = null
            wrapperForegroundHandler = null
            if (wrapperCallbacksSet.isCompleted) wrapperCallbacksSet = CompletableDeferred()
        }

        @Volatile
        private var launchIntegration = false

        @Volatile
        private var wrapperCallbacksSet = CompletableDeferred<Unit>()

        /** How long a launch tap's link waits for the wrapper's first [setWrapperCallbacks]. */
        internal const val LAUNCH_LINK_WAIT_MS = 10_000L

        /** The app's process lifecycle: whether any of its screens is showing. Replaced in tests. */
        @Volatile
        internal var processLifecycle: () -> Lifecycle = { ProcessLifecycleOwner.get().lifecycle }

        /** How long a launch tap's link, ready while the app is in the background, waits for it to return. */
        private const val LAUNCH_LINK_HOLD_MS = 5 * 60_000L

        /** True while a wrapper installed push at launch and hasn't set its callbacks yet. */
        internal fun awaitsWrapperCallbacks(context: Context): Boolean {
            if (wrapperCallbacksSet.isCompleted) return false
            if (launchIntegration) return true
            val installed = try {
                GlobalPreferences(context).wrapperPushInstall()
            } catch (_: Exception) {
                0L
            }
            return installed != 0L && installed == appUpdateTime(context)
        }

        /**
         * Opens a launch tap's [url] once the wrapper sets its callbacks, or after 10 seconds: its
         * handler is asked first (at most 1.5 s), then the link goes the usual way.
         */
        internal fun openWhenWrapperReady(context: Context, url: String) {
            val app = context.applicationContext
            CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                withTimeoutOrNull(LAUNCH_LINK_WAIT_MS) { wrapperCallbacksSet.await() }
                val handler = wrapperDeepLinkHandler
                val handled = handler != null && active?.options?.handleDeepLink == null &&
                    (withTimeoutOrNull(WRAPPER_ANSWER_MS) {
                        try {
                            handler(url)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            KiroraLogger.e("Error in the wrapper's deep link handler", e)
                            false
                        }
                    } ?: false)
                if (!handled) whenInForeground { openLaunchLink(app, url) }
            }
        }

        private fun openLaunchLink(app: Context, url: String) {
            try {
                KiroraPushTrampolineActivity.openAction(app, url)
            } catch (e: Exception) {
                KiroraLogger.w("Could not open the notification's link", e)
            }
        }

        /**
         * Runs [block] now if the app is on screen, else when it next is (within 5 minutes): Android
         * 10+ blocks starting an activity from the background, and older versions would pull the user
         * back into the app. Called on the main thread.
         */
        private fun whenInForeground(block: () -> Unit) {
            val lifecycle = try {
                processLifecycle()
            } catch (_: Throwable) {
                return block()
            }
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return block()
            val until = SystemClock.elapsedRealtime() + LAUNCH_LINK_HOLD_MS
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    lifecycle.removeObserver(this)
                    if (SystemClock.elapsedRealtime() <= until) block() else KiroraLogger.d("A launch tap's link expired before the app came back.")
                }
            })
        }

        private fun appUpdateTime(context: Context): Long? = try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (_: Exception) {
            null
        }

        @Volatile
        internal var wrapperDeepLinkHandler: (suspend (String) -> Boolean)? = null

        @Volatile
        internal var wrapperForegroundHandler: (suspend (Map<String, String>) -> Boolean)? = null

        /** How long a wrapper's callback is awaited before the SDK acts as if there were none. */
        internal const val WRAPPER_ANSWER_MS = 1_500L

        /** True when the push data carries `kirora: "true"`: a Kirora push. */
        public fun isKiroraPush(data: Map<String, String>): Boolean = KiroraPushDisplay.isKiroraPush(data)

        /**
         * Decides whether a push is drawn while the app is in the foreground. It is, by default;
         * return `false` to hold one back. Takes precedence over `KiroraPushOptions.onForegroundNotification`
         * and is kept for the life of the process, so it also works for wrappers that set Kirora up late.
         */
        @JvmStatic
        public fun setForegroundNotificationHandler(handler: ((Map<String, String>) -> Boolean)?) {
            foregroundHandler = handler
        }

        @Volatile
        internal var foregroundHandler: ((Map<String, String>) -> Boolean)? = null

        /**
         * Draws a Kirora push, with or without an instance, and returns `true`; returns `false` for any
         * other push, which is then the app's to handle. Used by the FCM service of `android-sdk-push`.
         */
        @KiroraInternalApi
        @JvmStatic
        public fun handlePushMessage(context: Context, data: Map<String, String>): Boolean {
            val app = context.applicationContext
            val current = active
            val config = current?.options?.push?.let { PushDisplayConfig.of(it) }
                ?: GlobalPreferences(app).getPushDisplay()
            return KiroraPushDisplay.handle(
                app,
                data,
                pushListeners,
                config,
                foregroundHandler ?: current?.options?.push?.onForegroundNotification
            )
        }

        /** A new FCM token, with or without an instance (it is registered once the app signs a user in). */
        @KiroraInternalApi
        @JvmStatic
        public fun handleNewPushToken(context: Context, token: String) {
            val current = active
            if (current != null) current.onNewPushToken(token) else GlobalPreferences(context.applicationContext).setPushToken(token)
        }

        /**
         * Reports a tap on a Kirora notification or one of its buttons to the push's `trackingUrl`
         * (`opened`, or `clicked` for a link or a button). An operational report, sent whatever the
         * behavioural tracking consent. Completes when the first attempt has ended.
         */
        internal fun reportNotificationTap(context: Context, trackingUrl: String?, clicked: Boolean): Deferred<Unit> {
            val current = active
            return KiroraPushTracker.report(
                context.applicationContext,
                current?.options?.apiUrl,
                trackingUrl,
                if (clicked) KiroraPushTracker.CLICKED else KiroraPushTracker.OPENED,
                current?.apiService
            )
        }

        /**
         * A tap reaches the app (its link, `handleDeepLink`, a tap listener) after its report, but
         * waits at most [TAP_REPORT_WAIT_MS] for it; the report goes on in the background after that.
         */
        internal suspend fun awaitTapReport(report: Deferred<Unit>) {
            withTimeoutOrNull(TAP_REPORT_WAIT_MS) { report.await() }
        }

        /** How long a tap waits for its report before the app is told. */
        internal const val TAP_REPORT_WAIT_MS = 1_500L

        /** Tells the app's click listeners of a tap, or keeps it (the latest ten) for listeners that attach later. */
        internal fun deliverNotificationTap(data: Map<String, String>) {
            val clickListeners = pushListeners.click
            if (clickListeners.isEmpty()) {
                synchronized(pushListeners.pendingClicks) {
                    pushListeners.pendingClicks.addLast(data)
                    while (pushListeners.pendingClicks.size > 10) pushListeners.pendingClicks.removeFirst()
                }
            } else {
                clickListeners.forEach {
                    appCallback("notification click listener") { it(data) }
                }
            }
        }

        /** Runs the app's [callback], so nothing it throws reaches the SDK's threads (or crashes the app). */
        internal inline fun appCallback(what: String, callback: () -> Unit) {
            try {
                callback()
            } catch (e: Exception) {
                KiroraLogger.e("Error in $what", e)
            }
        }

        /** The most recently created instance, for code that cannot be handed one (e.g. the FCM service). */
        @Volatile
        public var active: Kirora? = null
            private set

        private const val PUSH_BRIDGE_CLASS = "com.kirora.androidsdk.push.fcm.FcmPushBridge"
        /** `logout()`'s whole deadline, including waiting for a sign-in, the flush and the detachment. */
        private const val LOGOUT_DEADLINE_MS = 5_000L
        /** How often a tracking choice that couldn't be fully read is read again. */
        private const val CONSENT_REREAD_MS = 30_000L
        /**
         * The event queue, shared by every client in the process: a sign-in adopts exactly what was
         * tracked before it, by this client or one it replaced. Tasks start one at a time in call
         * order on [queueWork], and [queueOrder] (fair) keeps that order while one waits on Room's own
         * executor, when the thread would otherwise start the next. Enter only through
         * [launchInQueue] and [inQueue] (and the event write, which takes the lock for its store write).
         */
        private val queueWork: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
        private val queueOrder = Mutex()

        /** Event writes; not cancelled by destroy(). */
        private val queueWrites = CoroutineScope(SupervisorJob())

        /** Runs [block] on the event queue after everything put on it before has finished. */
        internal fun CoroutineScope.launchInQueue(block: suspend CoroutineScope.() -> Unit): Job =
            launch(queueWork) { queueOrder.withLock { block() } }

        /** [launchInQueue], waiting for the answer. */
        internal suspend fun <T> inQueue(block: suspend CoroutineScope.() -> T): T =
            withContext(queueWork) { queueOrder.withLock { block() } }

        /** Holds the event queue until [release] completes, for tests. */
        internal fun holdQueueForTesting(release: CountDownLatch) {
            queueWrites.launchInQueue { release.await() }
        }

        /** Tracking-choice writes, in order, for every client in the process; not cancelled by destroy(). */
        private val consentWrites = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

        @Volatile
        private var lastConsentWrite: Job? = null

        /**
         * Per workspace, the tracking choice still being written. A client created in the meantime
         * (one replacing another) takes it rather than the storage it hasn't reached yet.
         */
        private val unsavedConsent = ConcurrentHashMap<String, PendingChoice>()

        /** A tracking choice still being written. Equal only to itself, so each write removes only its own. */
        private class PendingChoice(val granted: Boolean)

        /** Holds tracking-choice writes until [release] completes, for tests. */
        internal fun holdConsentWritesForTesting(release: CountDownLatch) {
            lastConsentWrite = consentWrites.launch { release.await() }
        }

        /** Waits for tracking-choice writes already started, for tests. */
        internal suspend fun awaitConsentSavedForTesting() {
            lastConsentWrite?.join()
        }
        /** A token with an `exp` is refreshed this long before it runs out, and on return to the foreground. */
        private const val TOKEN_REFRESH_LEAD_MS = 60_000L

        /** How long a database that refused an owed deletion is left before it is tried again. */
        private const val PURGE_RETRY_MS = 30_000L
        /** The stored event's own fields beyond its name, properties and context: key, time and JSON punctuation. */
        private const val EVENT_OVERHEAD_BYTES = 160

        /** Keeps a token that expires sooner than its lead from being refreshed in a tight loop. */
        @Volatile
        internal var minRefreshDelayMs = 30_000L

        @Volatile
        internal var refreshRetryMs = 15_000L

        internal fun resetForTesting() {
            active?.destroy()
            active = null
            pushListeners.click.clear()
            pushListeners.foreground.clear()
            pushListeners.pendingClicks.clear()
            foregroundHandler = null
            wrapperDeepLinkHandler = null
            wrapperForegroundHandler = null
            launchIntegration = false
            wrapperCallbacksSet = CompletableDeferred()
            processLifecycle = { ProcessLifecycleOwner.get().lifecycle }
            SdkInfo.resetForTesting()
            KiroraPushTracker.resetForTesting()
            SessionManager.resetForTesting()
            minRefreshDelayMs = 30_000L
            refreshRetryMs = 15_000L
        }
    }
}
