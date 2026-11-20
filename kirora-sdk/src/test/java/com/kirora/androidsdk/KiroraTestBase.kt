package com.kirora.androidsdk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kirora.androidsdk.model.KiroraOptions
import com.kirora.androidsdk.net.FakeKiroraApiService
import com.kirora.androidsdk.push.KiroraPushBridge
import com.kirora.androidsdk.utils.GlobalPreferences
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
    protected fun start(options: KiroraOptions = KiroraOptions(trackSessions = false), clearQueue: Boolean = true): Kirora {
        // A restart reads what the previous client saved.
        runBlocking { Kirora.awaitConsentSavedForTesting() }
        val kirora = Kirora(context, options.withClientKey("kirora_pk_test"), KiroraEnvironment(pushBridge = bridge))
        kirora.setApiServiceForTesting(fake)
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
}
