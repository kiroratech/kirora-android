package com.kirora.androidsdk.utils

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleInitializer
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.startup.AppInitializer

/**
 * Whether any of the app's screens is showing: AndroidX's [ProcessLifecycleOwner], which
 * androidx.startup's `InitializationProvider` sets up as the app starts. An app that removed that
 * provider (or its `ProcessLifecycleInitializer` entry) would leave it stuck, never started, and
 * AndroidX refuses to be set up later; so the SDK then follows the app's activities itself, the
 * same way, and logs how to restore the provider.
 */
internal object AppLifecycle {
    @Volatile
    private var fallback: ActivityCounter? = null

    /** Called as the client is created, with the app's context: from then on [get] knows which to use. */
    fun install(context: Context) {
        if (fallback != null) return
        val app = context.applicationContext as? Application ?: return
        val provided = try {
            AppInitializer.getInstance(app).isEagerlyInitialized(ProcessLifecycleInitializer::class.java)
        } catch (_: Throwable) {
            true
        }
        if (provided) return
        synchronized(this) {
            if (fallback != null) return
            KiroraLogger.e(
                "androidx.startup's InitializationProvider, or its ProcessLifecycleInitializer entry, " +
                    "was removed from the app's manifest: Kirora follows the app's activities instead, " +
                    "from now on only. Keep <meta-data android:name=\"androidx.lifecycle.ProcessLifecycleInitializer\" " +
                    "android:value=\"androidx.startup\" /> under the provider to restore it."
            )
            fallback = ActivityCounter(app)
        }
    }

    fun get(): Lifecycle = fallback?.lifecycle ?: ProcessLifecycleOwner.get().lifecycle

    /** Started when one of the app's activities is, stopped 700 ms after the last one stops (as AndroidX does, to ride out a rotation). */
    internal class ActivityCounter(app: Application) : LifecycleOwner, Application.ActivityLifecycleCallbacks {
        // Driven only from the main thread, where activity callbacks arrive; created on any.
        private val registry = LifecycleRegistry.createUnsafe(this)
        private val main = Handler(Looper.getMainLooper())
        private var started = 0
        private val stopIfStillHidden = Runnable {
            if (started == 0 && registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
        }

        override val lifecycle: Lifecycle get() = registry

        init {
            main.post {
                if (registry.currentState == Lifecycle.State.INITIALIZED) registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            }
            app.registerActivityLifecycleCallbacks(this)
        }

        override fun onActivityStarted(activity: Activity) {
            started++
            main.removeCallbacks(stopIfStillHidden)
            if (!registry.currentState.isAtLeast(Lifecycle.State.STARTED)) registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }

        override fun onActivityStopped(activity: Activity) {
            if (started > 0) started--
            if (started == 0) main.postDelayed(stopIfStillHidden, STOP_DELAY_MS)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    internal const val STOP_DELAY_MS = 700L
}
