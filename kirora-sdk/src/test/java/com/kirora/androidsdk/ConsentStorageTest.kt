package com.kirora.androidsdk

import android.content.SharedPreferences
import com.kirora.androidsdk.model.KiroraOptions
import com.kirora.androidsdk.utils.KiroraNamespace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** "Tracking consent and operational push": a refusal kept twice, and storage that can't be read. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class ConsentStorageTest : KiroraTestBase() {
    private val namespace get() = KiroraNamespace.of("https://api.kirora.io", "kirora_pk_test")
    private val mainStore: SharedPreferences get() = context.getSharedPreferences("kirora_$namespace", 0)
    private val refusalCopy get() = File(context.noBackupFilesDir, "kirora_tracking_refused_$namespace")
    private val optional = KiroraOptions(trackSessions = false)
    private val required = KiroraOptions(trackSessions = false, requireTrackingConsent = true)

    /** A main store that throws when read, as a value of the wrong type makes it. Call after a first start. */
    private fun saved() = runBlocking { Kirora.awaitConsentSavedForTesting() }

    private fun breakMainStore() {
        saved()
        mainStore.edit().putInt("tracking_consent", 1).commit()
    }

    @Test
    fun aRefusalIsKeptInASecondFileOutsideTheMainStore() {
        val kirora = start(optional)
        kirora.setTrackingConsent(false)
        saved()
        assertTrue(refusalCopy.exists())
        // The main store lost (Android serves a corrupt file as empty): the refusal still counts.
        mainStore.edit().clear().commit()
        assertFalse(start(optional, clearQueue = false).trackingConsent)
        saved()
        assertNull("read only: nothing is rebuilt on start", mainStore.getString("tracking_consent", null))
    }

    @Test
    fun aStartNeverRepairsACopyAGrantMayBeClearing() {
        // Mid-grant in another process: the refusal copy is cleared, "granted" not yet recorded.
        start(optional).setTrackingConsent(false)
        saved()
        refusalCopy.delete()
        assertFalse("the main store's refusal still holds", start(optional, clearQueue = false).trackingConsent)
        saved()
        assertFalse("no copy written back", refusalCopy.exists())
        // The grant lands: it is not undone by a copy a start put back.
        mainStore.edit().putString("tracking_consent", "granted").commit()
        assertTrue(start(optional, clearQueue = false).trackingConsent)
    }

    @Test
    fun aGrantClearsBothCopies() {
        val kirora = start(optional)
        kirora.setTrackingConsent(false)
        kirora.setTrackingConsent(true)
        saved()
        assertFalse(refusalCopy.exists())
        assertTrue(start(optional, clearQueue = false).trackingConsent)
    }

    @Test
    fun withoutRequiredConsentAnUnreadableChoiceTracksAsOnAFreshInstall() {
        start(optional).setTrackingConsent(true)
        breakMainStore()
        assertTrue(start(optional, clearQueue = false).trackingConsent)
    }

    @Test
    fun withoutRequiredConsentARefusalCopyStillStopsTracking() {
        start(optional).setTrackingConsent(false)
        breakMainStore()
        assertFalse(start(optional, clearQueue = false).trackingConsent)
    }

    @Test
    fun withRequiredConsentAnUnreadableChoiceIsNeverAGrant() {
        start(required).setTrackingConsent(true)
        breakMainStore()
        assertFalse(start(required, clearQueue = false).trackingConsent)
    }

    @Test
    fun withNeitherCopyReadableNothingIsCollected() {
        start(optional)
        breakMainStore()
        val dir = context.noBackupFilesDir
        try {
            dir.setExecutable(false)
            // Root (some CI images) can search any folder, so the copy is readable there anyway.
            assumeFalse("running as root", dir.canExecute())
            assertFalse(start(optional, clearQueue = false).trackingConsent)
        } finally {
            dir.setExecutable(true)
        }
    }

    @Test
    fun aGrantInterruptedBeforeItIsRecordedStaysARefusal() {
        // A grant clears the refusal copy first; until it is recorded the main store still says refused.
        start(optional).setTrackingConsent(false)
        saved()
        refusalCopy.delete()
        assertFalse(start(optional, clearQueue = false).trackingConsent)
    }

    @Test
    fun trackingDoesNotReadStoragePerEvent() {
        val kirora = start(optional)
        kirora.setTrackingConsent(false)
        saved()
        // Changed behind the client's back: the choice in effect is the one it holds.
        mainStore.edit().putString("tracking_consent", "granted").commit()
        refusalCopy.delete()
        assertFalse(kirora.trackingConsent)
    }

    @Test
    fun anAnswerGivenNowHoldsWhateverStorageSays() {
        start(required)
        breakMainStore()
        val kirora = start(required, clearQueue = false)
        kirora.setTrackingConsent(true)
        assertTrue(kirora.trackingConsent)
    }

    @Test
    fun aReplacementClientTakesAChoiceStillBeingWritten() {
        val first = start(optional)
        val release = java.util.concurrent.CountDownLatch(1)
        Kirora.holdConsentWritesForTesting(release)
        try {
            first.setTrackingConsent(false)
            // Created straight away, before the refusal reaches storage.
            val replacement = Kirora(context, optional.withClientKey("kirora_pk_test"), KiroraEnvironment())
            assertFalse(replacement.trackingConsent)
        } finally {
            release.countDown()
        }
        saved()
        assertFalse(start(optional, clearQueue = false).trackingConsent)
    }
}
