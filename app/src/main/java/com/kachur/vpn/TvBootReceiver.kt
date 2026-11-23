package com.kachur.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Handler
import android.os.Looper

/**
 * Best-effort Android TV boot connection. Never displays an Activity from the background.
 * Requires a previously imported profile and previously granted VpnService consent.
 */
class TvBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return
        val settings = VpnLaunchSettings(app)
        if (!settings.enabled || settings.paused || VpnService.prepare(app) != null) return
        val pending = goAsync()
        VpnConnection.systemCalls.execute {
            val selection = try { app.localProfiles().readSelected()?.selected?.profile }
                catch (_: Exception) { null }
            Handler(Looper.getMainLooper()).post {
                try {
                    if (selection != null && settings.enabled && !settings.paused &&
                        VpnService.prepare(app) == null && VpnConnection.phase == VpnPhase.IDLE &&
                        !VpnConnection.preparing) {
                        VpnConnection.start(app, selection.config,
                            VpnProfileLabels(app).country(selection.documentKey),
                            selection.documentKey, selection.name)
                    }
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
