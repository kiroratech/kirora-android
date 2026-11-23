package com.kirora.androidsdk.utils

import android.content.Context
import android.os.Build
import com.kirora.androidsdk.model.AppInfo
import com.kirora.androidsdk.model.DeviceInfo
import com.kirora.androidsdk.model.KiroraContext
import com.kirora.androidsdk.model.ScreenInfo
import java.util.Locale
import java.util.TimeZone

internal data class RegistrationDetails(
    val appVersion: String? = null,
    val osVersion: String? = null,
    val locale: String? = null,
    val timezone: String? = null
)

internal class KiroraContextProvider(
    private val context: Context,
    private val preferences: KiroraPreferences,
    /** Behavioural tracking is still allowed: only then may an event make the device id. */
    private val trackingAllowed: () -> Boolean = { true }
) {
    // Neither changes while the process runs, so they are read once.
    val app: AppInfo by lazy { readApp() }
    private val device: DeviceInfo by lazy { readDevice() }

    /**
     * What can change, read when the event is tracked, on the caller's thread: all in memory. The
     * app's details never change while the process runs, so the queue adds them (see [app]).
     */
    fun getContext(sessionId: String?, surroundings: Surroundings): KiroraContext = KiroraContext(
        // Read each time: withdrawing tracking consent can forget it. Empty only for an event
        // captured as consent is withdrawn, which the queue then drops.
        deviceId = preferences.getDeviceId(trackingAllowed).orEmpty(),
        sessionId = sessionId,
        locale = surroundings.locale,
        timezone = surroundings.timezone,
        device = device,
        screen = surroundings.screen
    )

    /** What can change, all in memory: read at the call, even for an event the queue captures later. */
    class Surroundings(val locale: String, val timezone: String, val screen: ScreenInfo)

    fun surroundings(): Surroundings = Surroundings(locale().orEmpty(), timezone().orEmpty(), readScreen())

    /**
     * What device registration sends beyond the push token: the app's and the OS's version, the
     * device's language and time zone. A value that can't be read is null, so it is left out.
     */
    fun registrationDetails(): RegistrationDetails = RegistrationDetails(
        appVersion = app.version.ifBlank { null },
        osVersion = device.osVersion.ifBlank { null },
        locale = locale(),
        timezone = timezone()
    )

    /** The device's language as BCP 47, or null when it has none ("und") or can't be read. */
    private fun locale(): String? = try {
        localeTag(Locale.getDefault())
    } catch (_: Exception) {
        null
    }

    private fun timezone(): String? = try {
        TimeZone.getDefault().id.ifBlank { null }
    } catch (_: Exception) {
        null
    }

    private fun readApp(): AppInfo = try {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val build = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toString() else info.versionCode.toString()
        AppInfo(
            name = context.applicationInfo.loadLabel(pm).toString(),
            version = info.versionName.orEmpty(),
            build = build,
            namespace = context.packageName
        )
    } catch (_: Exception) {
        AppInfo(namespace = context.packageName)
    }

    private fun readDevice(): DeviceInfo = DeviceInfo(
        manufacturer = Build.MANUFACTURER.orEmpty(),
        osVersion = Build.VERSION.RELEASE.orEmpty()
    )

    private fun readScreen(): ScreenInfo = try {
        val metrics = context.resources.displayMetrics
        ScreenInfo(metrics.widthPixels, metrics.heightPixels, metrics.density)
    } catch (_: Exception) {
        ScreenInfo()
    }

    companion object {
        /**
         * Language, script and region only (`en-GB`, `zh-Hant-TW`): Android 14's regional
         * preferences add `-u-…` extensions (first day of the week, units) that can make the full
         * tag longer than the 35 characters the server takes. These three subtags are 17 at most.
         * Null for `und`.
         */
        fun localeTag(locale: Locale): String? {
            val tag = try {
                Locale.Builder()
                    .setLanguage(locale.language)
                    .setScript(locale.script)
                    .setRegion(locale.country)
                    .build()
                    .toLanguageTag()
            } catch (_: Exception) {
                // A legacy value Builder refuses (an odd region, say): the language alone.
                Locale(locale.language).toLanguageTag()
            }
            return tag.takeIf { it.isNotBlank() && it != "und" }
        }
    }
}
