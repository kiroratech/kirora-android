package com.kirora.androidsdk.push

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.kirora.androidsdk.Kirora
import com.kirora.androidsdk.model.KiroraPushOptions
import com.kirora.androidsdk.utils.KiroraLogger
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** How a notification looks: from the app's [KiroraPushOptions], kept on the device so a push can be drawn before the app has set Kirora up. */
internal data class PushDisplayConfig(
    val smallIconResId: Int? = null,
    val accentColor: Int? = null,
    val channelId: String = DEFAULT_CHANNEL_ID,
    val channelName: String = DEFAULT_CHANNEL_NAME
) {
    companion object {
        const val DEFAULT_CHANNEL_ID = "kirora_default"
        const val DEFAULT_CHANNEL_NAME = "Notifications"

        fun of(options: KiroraPushOptions?) = PushDisplayConfig(
            smallIconResId = options?.smallIconResId,
            accentColor = options?.accentColor,
            channelId = options?.defaultChannelId ?: DEFAULT_CHANNEL_ID,
            channelName = options?.defaultChannelName ?: DEFAULT_CHANNEL_NAME
        )
    }
}

/**
 * Draws Kirora's data-only pushes. A push is Kirora's when `kirora` is `"true"`; anything else is left
 * to the app. It always shows, in every app state, unless the app's foreground hook says no.
 */
internal object KiroraPushDisplay {

    /** Extras of the tap activity that are not the push's own data. */
    val INTERNAL_EXTRAS = setOf(
        KiroraPushTrampolineActivity.EXTRA_BUTTON_INDEX,
        KiroraPushTrampolineActivity.EXTRA_BUTTON_TITLE,
        KiroraPushTrampolineActivity.EXTRA_BUTTON_URL,
        KiroraPushTrampolineActivity.EXTRA_ACTION_TYPE,
        KiroraPushTrampolineActivity.EXTRA_NOTIFICATION_TAG,
        KiroraPushTrampolineActivity.EXTRA_NOTIFICATION_ID
    )

    private const val IMAGE_TIMEOUT_MS = 3_000
    /** For the whole download, redirects included. */
    private const val IMAGE_TOTAL_MS = 5_000L
    private const val IMAGE_MAX_REDIRECTS = 3
    private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    private const val IMAGE_MAX_BYTES = 5 * 1024 * 1024
    private const val IMAGE_MAX_EDGE = 1024

    fun isKiroraPush(data: Map<String, String>): Boolean =
        data["kirora"] == "true"

    /** Returns false when [data] is not Kirora's, so the caller can pass it on to the app. */
    fun handle(
        context: Context,
        data: Map<String, String>,
        listeners: PushListeners,
        config: PushDisplayConfig,
        foregroundHook: ((Map<String, String>) -> Boolean)?
    ): Boolean {
        if (!isKiroraPush(data)) return false
        KiroraLogger.d("Push message received.")
        listeners.foreground.forEach { listener -> Kirora.appCallback("foreground notification listener") { listener(data) } }
        if (isAppInForeground()) {
            val show = try {
                foregroundHook?.invoke(data) ?: askWrapper(data)
            } catch (e: Exception) {
                KiroraLogger.e("Error in the foreground notification hook; showing the push", e)
                true
            }
            if (!show) {
                KiroraLogger.d("Foreground notification suppressed by the app's hook.")
                return true
            }
        }
        render(context, data, config)
        return true
    }

    /**
     * A wrapper's foreground hook, answered across its bridge: awaited at most 1.5 seconds, then
     * the push is shown. Never asked on the main thread, which the bridge itself may need.
     */
    private fun askWrapper(data: Map<String, String>): Boolean {
        val hook = Kirora.wrapperForegroundHandler ?: return true
        if (Looper.myLooper() == Looper.getMainLooper()) return true
        return runBlocking {
            withTimeoutOrNull(Kirora.WRAPPER_ANSWER_MS) {
                try {
                    hook(data)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    KiroraLogger.e("Error in the wrapper's foreground hook; showing the push", e)
                    true
                }
            } ?: true
        }
    }

    /** Whether the app is open; replaceable so tests can say so. */
    @Volatile
    var foregroundCheck: () -> Boolean = {
        try {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        } catch (_: Throwable) {
            false
        }
    }

    fun isAppInForeground(): Boolean = foregroundCheck()

    /** Creates the default channel; done at start so the app can see and tune it. */
    fun ensureDefaultChannel(context: Context, config: PushDisplayConfig) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) notifications(context)?.let { channelFor(it, null, config) }
    }

    /**
     * Whether the user turned off Kirora's default channel (or its group) in the system settings:
     * nothing on it is shown, and only the settings can change that. False before Android 8, which
     * has no channels, and while the channel doesn't exist yet. Never creates it.
     */
    fun defaultChannelBlocked(context: Context, config: PushDisplayConfig): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = notifications(context) ?: return false
        val channel = manager.getNotificationChannel(config.channelId) ?: return false
        return blocked(manager, channel)
    }

    /** The channel [requested] names, if the app made it; else the default one, created if need be. */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun channelFor(manager: NotificationManager, requested: String?, config: PushDisplayConfig): NotificationChannel? {
        requested?.takeIf { it.isNotBlank() }?.let(manager::getNotificationChannel)?.let { return it }
        return manager.getNotificationChannel(config.channelId)
            ?: NotificationChannel(config.channelId, config.channelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Push notifications"
                manager.createNotificationChannel(this)
            }
    }

    /** Blocks for the image (a few seconds at most): the push service may be stopped as soon as it returns. */
    @SuppressLint("MissingPermission")
    fun render(context: Context, data: Map<String, String>, config: PushDisplayConfig) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val title = data["title"]
        val body = data["body"]
        if (title.isNullOrBlank() && body.isNullOrBlank()) return

        // Android 8+ draws a push on a channel; before, there are none.
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = notifications(context)
            val channel = manager?.let { channelFor(it, data["channelId"], config) }
            if (manager != null && channel != null && blocked(manager, channel)) {
                // Posting succeeds but nothing appears: not shown, so not reported as delivered.
                KiroraLogger.d("Push not shown: its notification channel is turned off.")
                return
            }
            channel?.id ?: config.channelId
        } else {
            config.channelId
        }

        val tag = data["tag"]?.takeIf { it.isNotBlank() }
        // A tag names the notification, so a newer push with the same tag replaces the older one.
        val notificationId = if (tag != null) 0 else System.currentTimeMillis().toInt()

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIcon(context, config))
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(tapIntent(context, data, notificationId, tag, null))
        // From Android 8 the channel decides the sound; before, the push's own `sound` applies.
        val sound = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) soundUri(context, data["sound"]) else null
        builder.setDefaults(
            if (sound != null) NotificationCompat.DEFAULT_ALL and NotificationCompat.DEFAULT_SOUND.inv() else NotificationCompat.DEFAULT_ALL
        )
        sound?.let(builder::setSound)
        accentColor(context, config)?.let { builder.setColor(it) }

        val picture = data["image"]?.let { url -> downloadImage(url) }
        if (picture != null) {
            builder.setLargeIcon(null as Bitmap?)
            builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).bigLargeIcon(null as Bitmap?))
        } else if (!body.isNullOrBlank()) {
            // Expandable, so a long body is read in full rather than cut to one line.
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        parseButtonsJson(data["buttons"].orEmpty()).take(MAX_BUTTONS).forEachIndexed { index, button ->
            builder.addAction(0, button.title, tapIntent(context, data, notificationId, tag, index to button))
        }

        try {
            NotificationManagerCompat.from(context).notify(tag, notificationId, builder.build())
            KiroraLogger.d("System notification posted (tag=$tag).")
        } catch (e: Exception) {
            KiroraLogger.e("Failed to post system notification", e)
            return
        }
        // The notification is on screen: `delivered`, once per notification, retried once.
        val active = Kirora.active
        KiroraPushTracker.report(context, active?.options?.apiUrl, data["trackingUrl"], KiroraPushTracker.DELIVERED, active?.apiService)
    }

    private fun tapIntent(
        context: Context,
        data: Map<String, String>,
        notificationId: Int,
        tag: String?,
        button: Pair<Int, KiroraNotificationButton>?
    ): PendingIntent {
        val intent = Intent(context, KiroraPushTrampolineActivity::class.java).apply {
            data.forEach { (k, v) -> putExtra(k, v) }
            putExtra(KiroraPushTrampolineActivity.EXTRA_NOTIFICATION_ID, notificationId)
            tag?.let { putExtra(KiroraPushTrampolineActivity.EXTRA_NOTIFICATION_TAG, it) }
            if (button != null) {
                putExtra(KiroraPushTrampolineActivity.EXTRA_BUTTON_INDEX, button.first)
                putExtra(KiroraPushTrampolineActivity.EXTRA_BUTTON_TITLE, button.second.title)
                putExtra(KiroraPushTrampolineActivity.EXTRA_BUTTON_URL, button.second.url)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        // One request code per (notification, button): a newer push with the same tag updates its own.
        val requestCode = (tag?.hashCode() ?: notificationId) * 31 + (button?.first?.plus(1) ?: 0)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notifications(context: Context): NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    /** The user turned off [channel], or (Android 9+) its group, in the system settings. */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun blocked(manager: NotificationManager, channel: NotificationChannel): Boolean {
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return channel.group?.let { manager.getNotificationChannelGroup(it)?.isBlocked } == true
    }

    /** The app's setting, then the manifest meta-data (`io.kirora.default_notification_icon`, FCM's own), then the launcher icon. */
    private fun smallIcon(context: Context, config: PushDisplayConfig): Int =
        config.smallIconResId
            ?: metaDataInt(context, META_ICON)
            ?: metaDataInt(context, FCM_META_ICON)
            ?: context.applicationInfo.icon.takeIf { it != 0 }
            ?: android.R.drawable.ic_dialog_info

    private fun accentColor(context: Context, config: PushDisplayConfig): Int? {
        config.accentColor?.let { return it }
        val res = metaDataInt(context, META_COLOR) ?: metaDataInt(context, FCM_META_COLOR) ?: return null
        return try {
            androidx.core.content.ContextCompat.getColor(context, res)
        } catch (_: Exception) {
            null
        }
    }

    private fun metaDataInt(context: Context, key: String): Int? = try {
        val info = context.packageManager.getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA)
        info.metaData?.getInt(key, 0)?.takeIf { it != 0 }
    } catch (_: Exception) {
        null
    }

    /**
     * The app's raw resource named by a push's `sound` (`chime` or `chime.mp3` for `res/raw/chime.mp3`),
     * or null for none, `default`, or a name the app doesn't have, which keep the default sound.
     */
    // A push names its sound as text, so the resource can only be found by name.
    @SuppressLint("DiscouragedApi")
    internal fun soundUri(context: Context, sound: String?): Uri? {
        val name = sound?.trim()?.substringBeforeLast('.')?.takeIf { it.isNotEmpty() && it != "default" } ?: return null
        val id = try {
            context.resources.getIdentifier(name, "raw", context.packageName)
        } catch (_: Exception) {
            0
        }
        if (id == 0) {
            KiroraLogger.w("No raw resource named '$name' for the push sound; using the default sound.")
            return null
        }
        return "${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/$id".toUri()
    }

    /**
     * https only, following up to [IMAGE_MAX_REDIRECTS] https redirects (a CDN, a link shortener),
     * a few seconds in all, a few MB; the push is shown without the picture when any of it fails.
     */
    private fun downloadImage(urlText: String): Bitmap? {
        val deadline = SystemClock.elapsedRealtime() + IMAGE_TOTAL_MS
        var url = urlText
        var connection: HttpURLConnection? = null
        return try {
            var hops = 0
            while (true) {
                if (!url.startsWith("https://", ignoreCase = true)) return null
                val left = remaining(deadline) ?: return null
                connection?.disconnect()
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = minOf(IMAGE_TIMEOUT_MS, left)
                    readTimeout = minOf(IMAGE_TIMEOUT_MS, left)
                    // Followed by hand: HttpURLConnection would also follow one to plain http.
                    instanceFollowRedirects = false
                }
                val status = connection.responseCode
                if (status == 200) break
                if (status !in REDIRECT_STATUSES || ++hops > IMAGE_MAX_REDIRECTS) return null
                val location = connection.getHeaderField("Location") ?: return null
                url = URL(URL(url), location).toString()
            }
            val open = connection ?: return null
            val bytes = ByteArrayOutputStream()
            open.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    bytes.write(buffer, 0, read)
                    if (bytes.size() > IMAGE_MAX_BYTES) return null
                    if (remaining(deadline) == null) return null
                }
            }
            decodeSampled(bytes.toByteArray())
        } catch (e: Exception) {
            KiroraLogger.w("Could not download the push image; showing the push without it.", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Milliseconds left before [deadline], or null once it has passed. */
    private fun remaining(deadline: Long): Int? =
        (deadline - SystemClock.elapsedRealtime()).takeIf { it > 0 }?.toInt()

    private fun decodeSampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > IMAGE_MAX_EDGE * 2 || bounds.outHeight / sample > IMAGE_MAX_EDGE * 2) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private const val MAX_BUTTONS = 2
    private const val META_ICON = "io.kirora.default_notification_icon"
    private const val META_COLOR = "io.kirora.default_notification_color"
    private const val FCM_META_ICON = "com.google.firebase.messaging.default_notification_icon"
    private const val FCM_META_COLOR = "com.google.firebase.messaging.default_notification_color"
}
