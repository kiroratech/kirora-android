package com.kirora.androidsdk.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.kirora.androidsdk.push.PushDisplayConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * Auto-backup can restore an app's preferences onto another device. A [marker] file in the
 * no-backup dir is not restored, so a missing one means a fresh install or a restore: [forget]
 * runs, then the marker is made. A folder that can't be searched hides the marker: that's not a
 * restore, so nothing is forgotten.
 */
internal fun forgetIfRestoredFromBackup(context: Context, marker: String, forget: () -> Unit) {
    try {
        val dir = context.applicationContext.noBackupFilesDir
        if (!dir.isDirectory || !dir.canExecute()) return
        val file = File(dir, marker)
        if (!file.exists()) {
            forget()
            file.createNewFile()
        }
    } catch (_: Throwable) {
    }
}

/** [key]'s string, or null when it is absent, JSON null or empty. */
private fun JSONObject.optNullableString(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

/** Names one workspace on one API deployment, so two clients never share stored state. */
internal object KiroraNamespace {
    fun of(apiUrl: String, clientKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${apiUrl.trimEnd('/')}|$clientKey".toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}

/**
 * A detachment still to make after a sign-out, kept on the device so it outlives the process:
 * retried with the registration's device secret until [giveUpAt] (24 hours from the sign-out).
 */
internal data class PendingDetach(
    val installationId: String,
    val lastSeenAt: String?,
    val secret: String,
    val giveUpAt: Long
) {
    /** The same sign-out's: one installation's secret serves every sign-out on it. */
    fun sameAs(other: PendingDetach): Boolean =
        installationId == other.installationId && lastSeenAt == other.lastSeenAt && giveUpAt == other.giveUpAt

    fun toJson(): JSONObject = JSONObject().apply {
        put("installationId", installationId)
        put("lastSeenAt", lastSeenAt ?: JSONObject.NULL)
        put("secret", secret)
        put("giveUpAt", giveUpAt)
    }

    companion object {
        fun fromJson(o: JSONObject): PendingDetach? = try {
            PendingDetach(
                installationId = o.getString("installationId"),
                lastSeenAt = o.optNullableString("lastSeenAt"),
                secret = o.getString("secret"),
                giveUpAt = o.getLong("giveUpAt")
            )
        } catch (_: Exception) {
            null
        }
    }
}

/** The device's last push registration, for this workspace. */
internal data class PushRegistration(
    val userSubject: String,
    val pushAppId: String,
    /** The FCM token registered, or null for the denied form. */
    val target: String?,
    val permission: String,
    /** The device details sent, so a change registers again. */
    val details: String,
    val at: Long,
    /** Kirora's answer: a later denial names it. */
    val lastSeenAt: String?
) {
    fun toJson(): String = JSONObject().apply {
        put("userSubject", userSubject)
        put("pushAppId", pushAppId)
        put("target", target ?: JSONObject.NULL)
        put("permission", permission)
        put("details", details)
        put("at", at)
        put("lastSeenAt", lastSeenAt ?: JSONObject.NULL)
    }.toString()

    companion object {
        fun fromJson(text: String?): PushRegistration? = try {
            text?.let {
                val o = JSONObject(it)
                PushRegistration(
                    userSubject = o.getString("userSubject"),
                    pushAppId = o.getString("pushAppId"),
                    target = if (o.isNull("target")) null else o.getString("target"),
                    permission = o.getString("permission"),
                    details = o.getString("details"),
                    at = o.getLong("at"),
                    lastSeenAt = o.optNullableString("lastSeenAt")
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Device-local SDK state for one workspace ([KiroraNamespace]). The user token is never stored
 * here: it lives in memory and the app supplies it again at every start.
 */
internal class KiroraPreferences(context: Context, private val namespace: String) {
    private val app: Context = context.applicationContext

    /**
     * The store, on first use rather than at construction (finding the file checks the disk), and
     * checked for a restore from another device's backup, which starts it from scratch.
     */
    private val prefs: SharedPreferences by lazy {
        app.getSharedPreferences("kirora_$namespace", Context.MODE_PRIVATE).also { store ->
            forgetIfRestoredFromBackup(app, "kirora_install_$namespace") { store.edit().clear().commit() }
        }
    }

    /**
     * The second, independent copy of a tracking refusal: its own file, outside this store and
     * never backed up, which nothing else writes. Present means refused.
     */
    private val refusalFile by lazy { File(app.noBackupFilesDir, "kirora_tracking_refused_$namespace") }

    // ---- Device id ------------------------------------------------------------------------

    /** A random id made on first use; never a hardware or advertising id. */
    @Synchronized
    fun getDeviceId(): String {
        val deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (!deviceId.isNullOrEmpty()) return deviceId
        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
        return newId
    }

    /**
     * The installation's secret (32 random bytes, base64url), made once beside the device id and
     * kept as long as it: sent with every registration, it detaches the device with no user token.
     */
    @Synchronized
    fun getDeviceSecret(): String {
        prefs.getString(KEY_DEVICE_SECRET, null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val secret = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        prefs.edit().putString(KEY_DEVICE_SECRET, secret).apply()
        return secret
    }

    /** The device id if there is one; never creates it. */
    fun peekDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotEmpty() }

    @Synchronized
    fun forgetDeviceId() {
        // The secret goes with the installation it belongs to.
        prefs.edit().remove(KEY_DEVICE_ID).remove(KEY_DEVICE_SECRET).apply()
    }

    // ---- Tracking consent -----------------------------------------------------------------

    /** `true` granted, `false` refused, null unanswered. Throws when storage can't be read. */
    fun readTrackingConsent(): Boolean? {
        val stored = prefs.getString(KEY_TRACKING_CONSENT, null)
        return when (stored) {
            ANSWER_GRANTED -> true
            ANSWER_REFUSED -> false
            else -> null
        }
    }

    /** Whether the refusal copy holds a refusal. Throws when it can't be read. */
    fun readRefusalCopy(): Boolean {
        if (refusalFile.exists()) return true
        // Absent only counts when the folder can be searched (POSIX `x`): otherwise exists() is
        // false whatever is there, and "no refusal" can't be told from "can't see it".
        val dir = refusalFile.parentFile ?: throw IllegalStateException("no folder for the refusal copy")
        if (!dir.isDirectory || !dir.canExecute()) throw IllegalStateException("can't search the refusal copy's folder")
        return false
    }

    /**
     * Saves the answer in both places now: a refusal in this store and in the refusal copy; a grant
     * here, clearing the refusal copy. False when either could not be written.
     */
    fun writeTrackingConsent(granted: Boolean): Boolean {
        val store = {
            try {
                prefs.edit().putString(KEY_TRACKING_CONSENT, if (granted) ANSWER_GRANTED else ANSWER_REFUSED).commit()
            } catch (_: Throwable) {
                false
            }
        }
        val copy = {
            try {
                if (granted) !refusalFile.exists() || refusalFile.delete() else refusalFile.exists() || refusalFile.createNewFile()
            } catch (_: Throwable) {
                false
            }
        }
        // Ordered so a crash between the two never turns an answer into the other one: a refusal
        // goes to its own copy first; a grant clears that copy before it's recorded.
        return if (granted) copy() && store() else copy().let { copied -> store() && copied }
    }

    // ---- Push -----------------------------------------------------------------------------


    /** [subject]'s marketing push choice as Kirora last accepted it from this device; false when there is none. */
    fun recordedPushConsent(subject: String): Boolean =
        prefs.getString(KEY_PUSH_CONSENT_RECORDED_BY, null) == subject && prefs.getBoolean(KEY_PUSH_CONSENT_RECORDED, false)

    /** Forgets the recorded choice unless it is [subject]'s. */
    fun forgetRecordedPushConsentUnlessOf(subject: String) {
        val owner = prefs.getString(KEY_PUSH_CONSENT_RECORDED_BY, null) ?: return
        if (owner != subject) prefs.edit().remove(KEY_PUSH_CONSENT_RECORDED).remove(KEY_PUSH_CONSENT_RECORDED_BY).apply()
    }

    fun setRecordedPushConsent(subject: String, granted: Boolean) {
        prefs.edit().putString(KEY_PUSH_CONSENT_RECORDED_BY, subject).putBoolean(KEY_PUSH_CONSENT_RECORDED, granted).apply()
    }


    fun getPushRegistration(): PushRegistration? = PushRegistration.fromJson(prefs.getString(KEY_PUSH_REGISTRATION, null))

    @Synchronized
    fun getPendingDetaches(): List<PendingDetach> = try {
        val array = JSONArray(prefs.getString(KEY_PENDING_DETACHES, null) ?: "[]")
        (0 until array.length()).mapNotNull { PendingDetach.fromJson(array.getJSONObject(it)) }
    } catch (_: Exception) {
        emptyList()
    }

    /** Adds or removes ([add] false) one pending detachment. */
    @Synchronized
    fun updatePendingDetach(detach: PendingDetach, add: Boolean) {
        val rest = getPendingDetaches().filter { !it.sameAs(detach) }
        val all = if (add) rest + detach else rest
        putString(KEY_PENDING_DETACHES, if (all.isEmpty()) null else JSONArray(all.map { it.toJson() }).toString())
    }

    fun setPushRegistration(registration: PushRegistration?) =
        putString(KEY_PUSH_REGISTRATION, registration?.toJson())

    /** The device's own push choice (`setPushEnabled(false)`), kept across sign-outs. */
    fun isPushDisabled(): Boolean = prefs.getBoolean(KEY_PUSH_DISABLED, false)

    fun setPushDisabled(disabled: Boolean) {
        prefs.edit().putBoolean(KEY_PUSH_DISABLED, disabled).apply()
    }

    /** Set once the notification prompt was answered, to tell `denied` from `unanswered`. */
    fun wasPermissionDenied(): Boolean = prefs.getBoolean(KEY_PERMISSION_DENIED, false)

    fun setPermissionDenied(denied: Boolean) {
        prefs.edit().putBoolean(KEY_PERMISSION_DENIED, denied).apply()
    }

    // ---- Session --------------------------------------------------------------------------

    fun getSessionId(): String? = prefs.getString(KEY_SESSION_ID, null)

    fun setSessionId(sessionId: String?) = putString(KEY_SESSION_ID, sessionId)

    /** Who the stored session belongs to; null when it began before anyone signed in. */
    fun getSessionOwner(): String? = prefs.getString(KEY_SESSION_OWNER, null)

    fun setSessionOwner(owner: String?) = putString(KEY_SESSION_OWNER, owner)

    fun getLastActivityTime(): Long = prefs.getLong(KEY_LAST_ACTIVITY_TIME, 0L)

    fun setLastActivityTime(time: Long) {
        prefs.edit().putLong(KEY_LAST_ACTIVITY_TIME, time).apply()
    }

    fun clearSession() {
        prefs.edit().remove(KEY_SESSION_ID).remove(KEY_SESSION_OWNER).remove(KEY_LAST_ACTIVITY_TIME).apply()
    }

    // ---- App opens and lifecycle ----------------------------------------------------------

    /** When an app open was last recorded: at most one per 30 minutes per device. */
    fun getLastAppOpen(): Long = prefs.getLong(KEY_LAST_APP_OPEN, 0L)

    fun setLastAppOpen(time: Long) {
        prefs.edit().putLong(KEY_LAST_APP_OPEN, time).apply()
    }

    /** Behavioural: forgotten when tracking consent is withdrawn. */
    fun clearLastAppOpen() {
        prefs.edit().remove(KEY_LAST_APP_OPEN).apply()
    }

    /** Deletions of stored [table] rows that failed and are still owed (see `PendingPurges`). */
    fun pendingPurges(table: String): String? = prefs.getString("$KEY_PENDING_PURGES$table", null)

    /** Written at once: it is what keeps withdrawn rows from coming back after a restart. */
    fun setPendingPurges(table: String, value: String?): Boolean = try {
        val edit = prefs.edit()
        if (value == null) edit.remove("$KEY_PENDING_PURGES$table") else edit.putString("$KEY_PENDING_PURGES$table", value)
        edit.commit()
    } catch (_: Exception) {
        false
    }

    fun getLastAppVersion(): String? = prefs.getString(KEY_LAST_APP_VERSION, null)

    fun getLastAppBuild(): String? = prefs.getString(KEY_LAST_APP_BUILD, null)

    fun setLastApp(version: String, build: String) {
        prefs.edit().putString(KEY_LAST_APP_VERSION, version).putString(KEY_LAST_APP_BUILD, build).apply()
    }

    fun isInstalled(): Boolean = prefs.getBoolean(KEY_IS_INSTALLED, false)

    fun setInstalled(installed: Boolean) {
        prefs.edit().putBoolean(KEY_IS_INSTALLED, installed).apply()
    }

    /** Forgets what belonged to the signed-in user: their marketing consent. */
    fun clearUserData() {
        prefs.edit()
            .remove(KEY_PUSH_CONSENT_PENDING)
            .remove(KEY_PUSH_CONSENT_RECORDED)
            .remove(KEY_PUSH_CONSENT_RECORDED_BY)
            .apply()
    }

    private fun putString(key: String, value: String?) {
        if (value == null) prefs.edit().remove(key).apply() else prefs.edit().putString(key, value).apply()
    }

    private companion object {
        const val ANSWER_GRANTED = "granted"
        const val ANSWER_REFUSED = "refused"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_SECRET = "device_secret"
        const val KEY_TRACKING_CONSENT = "tracking_consent"
        const val KEY_PUSH_CONSENT_PENDING = "push_consent_pending"
        const val KEY_PUSH_CONSENT_RECORDED = "push_consent_recorded"
        const val KEY_PUSH_CONSENT_RECORDED_BY = "push_consent_recorded_by"
        const val KEY_PUSH_REGISTRATION = "push_registration"
        const val KEY_PENDING_DETACHES = "pending_detaches"
        const val KEY_PUSH_DISABLED = "push_disabled"
        const val KEY_PERMISSION_DENIED = "permission_denied"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_SESSION_OWNER = "session_owner"
        const val KEY_LAST_ACTIVITY_TIME = "last_activity_time"
        const val KEY_LAST_APP_OPEN = "last_app_open"
        const val KEY_PENDING_PURGES = "pending_purges_"
        const val KEY_LAST_APP_VERSION = "last_app_version"
        const val KEY_LAST_APP_BUILD = "last_app_build"
        const val KEY_IS_INSTALLED = "is_installed"
    }
}

/**
 * What a push needs before the app has set Kirora up (a cold start from a push): the API host
 * to report to, how a notification looks, and the FCM token. Shared by the app's one Firebase
 * project, so not per workspace.
 */
internal class GlobalPreferences(context: Context) {
    private val app = context.applicationContext
    private val prefs: SharedPreferences by lazy { app.getSharedPreferences("kirora_prefs", Context.MODE_PRIVATE) }

    /**
     * Auto-backup can restore this file onto another device, or into another build: the FCM token
     * is the old device's, so is forgotten (Firebase gives this one's). Checked once per process,
     * with a marker in the no-backup dir as [KiroraPreferences] does.
     */
    private fun forgetIfRestored() {
        if (checked) return
        synchronized(GlobalPreferences::class.java) {
            if (checked) return
            forgetIfRestoredFromBackup(app, "kirora_install_global") { prefs.edit().remove(KEY_PUSH_TOKEN).commit() }
            // Only once the token is gone: until then, other callers wait here, so none reads the
            // restored token or has a fresh one it saved meanwhile cleared.
            checked = true
        }
    }

    fun saveApiUrl(url: String) {
        prefs.edit().putString(KEY_API_URL, url).apply()
    }

    fun getApiUrl(): String? = prefs.getString(KEY_API_URL, null)

    fun getPushToken(): String? {
        forgetIfRestored()
        return prefs.getString(KEY_PUSH_TOKEN, null)
    }

    /** The app install (its `lastUpdateTime`) in which a wrapper last installed push at launch; 0 for none. */
    fun wrapperPushInstall(): Long = prefs.getLong(KEY_WRAPPER_PUSH_INSTALL, 0L)

    fun setWrapperPushInstall(appUpdateTime: Long) {
        prefs.edit().putLong(KEY_WRAPPER_PUSH_INSTALL, appUpdateTime).apply()
    }

    fun setPushToken(token: String?) {
        forgetIfRestored()
        if (token == null) prefs.edit().remove(KEY_PUSH_TOKEN).apply() else prefs.edit().putString(KEY_PUSH_TOKEN, token).apply()
    }

    fun savePushDisplay(smallIcon: Int?, accentColor: Int?, channelId: String, channelName: String) {
        prefs.edit()
            // By name: a resource's number can change from one build of the app to the next.
            .putString(KEY_PUSH_ICON_NAME, smallIcon?.let(::resourceName))
            .putBoolean(KEY_PUSH_HAS_COLOR, accentColor != null)
            .putInt(KEY_PUSH_COLOR, accentColor ?: 0)
            .putString(KEY_PUSH_CHANNEL_ID, channelId)
            .putString(KEY_PUSH_CHANNEL_NAME, channelName)
            .apply()
    }

    fun getPushDisplay(): PushDisplayConfig {
        if (!prefs.contains(KEY_PUSH_CHANNEL_ID)) return PushDisplayConfig()
        return PushDisplayConfig(
            smallIconResId = prefs.getString(KEY_PUSH_ICON_NAME, null)?.let(::resourceId),
            accentColor = if (prefs.getBoolean(KEY_PUSH_HAS_COLOR, false)) prefs.getInt(KEY_PUSH_COLOR, 0) else null,
            channelId = prefs.getString(KEY_PUSH_CHANNEL_ID, null) ?: PushDisplayConfig.DEFAULT_CHANNEL_ID,
            channelName = prefs.getString(KEY_PUSH_CHANNEL_NAME, null) ?: PushDisplayConfig.DEFAULT_CHANNEL_NAME
        )
    }

    /** `package:type/name`, e.g. `com.example:drawable/ic_push`; null for an id this app can't name. */
    private fun resourceName(id: Int): String? = try {
        app.resources.getResourceName(id)
    } catch (_: Exception) {
        null
    }

    @SuppressLint("DiscouragedApi")
    private fun resourceId(name: String): Int? = app.resources.getIdentifier(name, null, null).takeIf { it != 0 }

    internal companion object {
        @Volatile
        private var checked = false

        /** As in a new process: the restore check runs again. */
        internal fun resetForTesting() {
            checked = false
        }

        private const val KEY_API_URL = "kirora_api_url"
        private const val KEY_PUSH_TOKEN = "kirora_push_token"
        private const val KEY_WRAPPER_PUSH_INSTALL = "wrapper_push_install"
        private const val KEY_PUSH_ICON_NAME = "kirora_push_icon_name"
        private const val KEY_PUSH_HAS_COLOR = "kirora_push_has_color"
        private const val KEY_PUSH_COLOR = "kirora_push_color"
        private const val KEY_PUSH_CHANNEL_ID = "kirora_push_channel_id"
        private const val KEY_PUSH_CHANNEL_NAME = "kirora_push_channel_name"
    }
}
