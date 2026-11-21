package com.kachur.vpn

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.PowerManager

/** Main-thread signals from the Android activity and screen lifecycles. */
internal class AppVisibility(private val application: Application) : Application.ActivityLifecycleCallbacks {
    private val activities = mutableSetOf<Activity>()
    private val observers = mutableSetOf<() -> Unit>()
    private val power by lazy { application.getSystemService(PowerManager::class.java) }
    private var screenRegistered = false

    val screenInteractive: Boolean get() = power?.isInteractive == true
    val interactiveUi: Boolean get() = activities.isNotEmpty() && screenRegistered && screenInteractive

    private val screenChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = notifyObservers()
    }

    fun observe(observer: () -> Unit) {
        if (!observers.add(observer)) return
        if (observers.size == 1) registerScreenChanges()
        observer()
    }

    fun removeObserver(observer: () -> Unit) {
        if (!observers.remove(observer) || observers.isNotEmpty() || !screenRegistered) return
        screenRegistered = false
        try {
            application.unregisterReceiver(screenChanges)
        } catch (_: IllegalArgumentException) {
            // Android can remove the receiver when it tears down the process.
        }
    }

    private fun registerScreenChanges() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        screenRegistered = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                application.registerReceiver(screenChanges, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                application.registerReceiver(screenChanges, filter)
            }
            true
        } catch (_: RuntimeException) {
            // Fall back to the slower statistics cadence if screen events are unavailable.
            false
        }
    }

    private fun notifyObservers() = observers.toList().forEach { it() }

    override fun onActivityStarted(activity: Activity) {
        if (activities.add(activity) && activities.size == 1) notifyObservers()
    }

    override fun onActivityStopped(activity: Activity) {
        if (activities.remove(activity) && activities.isEmpty()) notifyObservers()
    }

    override fun onActivityDestroyed(activity: Activity) = onActivityStopped(activity)
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
