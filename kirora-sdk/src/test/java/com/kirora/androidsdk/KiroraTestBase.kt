package com.kirora.androidsdk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kirora.androidsdk.model.KiroraOptions
import com.kirora.androidsdk.model.KiroraPushOptions
import com.kirora.androidsdk.net.FakeKiroraApiService
import com.kirora.androidsdk.push.KiroraPushBridge
import com.kirora.androidsdk.utils.GlobalPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before

/** Firebase as the SDK sees it: a token, and a prompt that answers [answer]. */
internal class FakePushBridge(var token: String? = "fcm-1", var answer: Boolean? = true) : KiroraPushBridge {
    override suspend fun fetchToken(): String? = token
    override fun requestNotificationPermission(context: Context, onResult: (granted: Boolean?) -> Unit) = onResult(answer)
}

internal abstract class KiroraTestBase {
    protected lateinit var context: Context
    protected lateinit var fake: FakeKiroraApiService
    protected val bridge = FakePushBridge()

    @Before
    fun baseSetup() {
        context = ApplicationProvider.getApplicationContext()
        Kirora.resetForTesting()
        fake = FakeKiroraApiService()
        GlobalPreferences(context).setPushToken(null)
    }

    @After
    fun baseTearDown() {
        Kirora.resetForTesting()
    }

    /** A client on a fresh queue, talking to [fake]. */
    protected fun start(
        options: KiroraOptions = KiroraOptions(trackSessions = false),
        clearQueue: Boolean = true,
        clock: () -> Long = System::currentTimeMillis
    ): Kirora {
        // A restart reads what the previous client saved.
        runBlocking { Kirora.awaitConsentSavedForTesting() }
        val kirora = Kirora(context, options.withClientKey("kirora_pk_test"), KiroraEnvironment(clock = clock, pushBridge = bridge, apiService = fake))
        runBlocking {
            kirora.awaitReady()
            if (clearQueue) {
                kirora.databaseForTesting?.eventDao()?.clearAll()
                kirora.databaseForTesting?.appOpenDao()?.clearAll()
            }
        }
        return kirora
    }

    /** A client with user-a signed in. */
    protected suspend fun signedIn(options: KiroraOptions = KiroraOptions(trackSessions = false)): Kirora =
        start(options).also { it.identify(jwt("user-a")) }

    protected suspend fun Kirora.queued(): Int = databaseForTesting!!.eventDao().getCount()

    protected suspend fun Kirora.waitQueued(n: Int) = waitUntil(message = "$n queued events") { queued() == n }

    /** Names of the events waiting from before sign-in. */
    protected suspend fun Kirora.waiting(): List<String> = databaseForTesting!!.eventDao().unowned().map { it.name }

    protected suspend fun Kirora.waitWaiting(name: String) = waitUntil(message = "$name waiting") { name in waiting() }

    /**
     * A client whose identify() is still fetching its token (from fetchUserToken) while [during]
     * runs; the token then arrives, and the sign-in ends however [during] left it.
     */
    protected suspend fun withSignInUnderWay(push: KiroraPushOptions? = null, during: suspend (Kirora) -> Unit) = coroutineScope {
        val token = CompletableDeferred<String>()
        val kirora = start(KiroraOptions(trackSessions = false, fetchUserToken = { token.await() }, push = push))
        val signingIn = launch { runCatching { kirora.identify() } }
        waitUntil(message = "sign-in under way") { kirora.identity.signInUnderWay }
        during(kirora)
        token.complete(jwt("user-a"))
        signingIn.join()
    }

    /** user-a's visit (signed in, one event sent), then a relaunch: nobody signed in yet. */
    protected suspend fun relaunchedAfterAVisit(options: KiroraOptions): Kirora {
        val first = start(options)
        first.identify(jwt("user-a"))
        first.track("A1") // the visit is A's from their first event signed in
        first.flush()
        first.destroy()
        return start(options, clearQueue = false)
    }
}
