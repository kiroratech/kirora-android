package com.kirora.androidsdk.push.fcm

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Transparent trampoline that shows the Android 13+ notification prompt and reports the answer,
 * so apps need no `onRequestPermissionsResult` plumbing.
 */
internal class KiroraPermissionActivity : Activity() {
    /** Opened too late for a prompt whose callers were already answered, after a newer one was asked for. */
    private var stale = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opened too late: a newer prompt has its own screen on the way, and these callers were answered.
        if (intent.getIntExtra(EXTRA_PROMPT, prompt) != prompt) {
            stale = true
            finish()
            return
        }
        // Opened in time: no longer `unanswered` when the wait for it ends.
        synchronized(pending) { openTimeout }?.let(main::removeCallbacks)
        if (savedInstanceState != null) return // recreated while the prompt is up
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE)
        } else {
            deliver(true)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE) return
        // No result: the request was interrupted (another prompt, the app sent away), not refused.
        val result = grantResults.firstOrNull() ?: return deliver(null)
        deliver(result == PackageManager.PERMISSION_GRANTED)
    }

    private fun deliver(granted: Boolean?) {
        if (stale) return
        val callbacks = takePending()
        finish()
        callbacks.forEach { it(granted) }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Dismissed without an answer (e.g. the app was backgrounded): don't leak the callback.
        if (isFinishing && !stale) takePending().forEach { it(null) }
    }

    companion object {
        private const val REQUEST_CODE = 7301
        /** How long a prompt may take to open before its callers are answered `unanswered`. */
        private const val OPEN_TIMEOUT_MS = 5_000L
        private const val EXTRA_PROMPT = "io.kirora.sdk.permission_prompt"

        private val main = Handler(Looper.getMainLooper())

        /** Numbers each prompt asked for, so a screen that opens after a newer one was asked for knows. */
        @Volatile
        private var prompt = 0

        /** Answers the latest prompt's callers if its screen hasn't opened in time. */
        private var openTimeout: Runnable? = null

        // Callers that ask while a prompt is already up share its answer.
        private val pending = mutableListOf<(Boolean?) -> Unit>()

        private fun takePending(): List<(Boolean?) -> Unit> = synchronized(pending) {
            pending.toList().also { pending.clear() }
        }

        fun request(context: Context, onResult: (Boolean?) -> Unit) {
            val alreadyShowing = synchronized(pending) {
                val showing = pending.isNotEmpty()
                pending.add(onResult)
                showing
            }
            if (alreadyShowing) return
            // Android 10+ silently refuses to start it from the background: answer rather than wait
            // forever, which would also keep every later request from showing a prompt. Set before
            // the start, so a screen that opens at once finds it to cancel.
            val timeout = Runnable { takePending().forEach { it(null) } }
            val thisPrompt = synchronized(pending) {
                openTimeout = timeout
                ++prompt
            }
            main.postDelayed(timeout, OPEN_TIMEOUT_MS)
            val intent = Intent(context, KiroraPermissionActivity::class.java).putExtra(EXTRA_PROMPT, thisPrompt)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                main.removeCallbacks(timeout)
                takePending().forEach { it(false) }
            }
        }
    }
}
