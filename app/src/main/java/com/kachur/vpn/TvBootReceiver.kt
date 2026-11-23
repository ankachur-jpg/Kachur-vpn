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
        val app = context.applicationContext
        if (!shouldConnectAfterBoot(app, intent.action)) return
        val settings = VpnLaunchSettings(app)
        val pending = goAsync()
        VpnConnection.systemCalls.execute {
            val selection = try { app.localProfiles().readSelected() }
                catch (_: Exception) { null }
            Handler(Looper.getMainLooper()).post {
                try {
                    val profile = selection?.selected?.profile
                    if (selection != null && profile != null && settings.enabled &&
                        app.localProfiles().revision() == selection.selected.revision &&
                        VpnService.prepare(app) == null && VpnConnection.phase == VpnPhase.IDLE &&
                        !VpnConnection.preparing) {
                        // A manual stop pauses the current session, not the next device boot.
                        settings.setPaused(false)
                        VpnConnection.start(app, profile.config,
                            VpnProfileLabels(app).country(profile.documentKey),
                            profile.documentKey, profile.name)
                    }
                } catch (_: RuntimeException) {
                    VpnConnection.record(R.string.vpn_start_error)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    internal fun shouldConnectAfterBoot(context: Context, action: String?): Boolean {
        if (action != Intent.ACTION_BOOT_COMPLETED ||
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            !VpnLaunchSettings(context).enabled) return false
        return try { VpnService.prepare(context) == null }
            catch (_: RuntimeException) { false }
    }
}
