package com.kachur.vpn

import android.content.Context
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Future

/** Preparation for a user click while SystemUI's foreground-service allowance is active. */
internal object VpnTileStart {
    private val main = Handler(Looper.getMainLooper())
    private var pendingWindow: Window? = null

    internal class Window(val startup: VpnStartupTrace, val error: Int?)

    private sealed interface Preparation {
        class Ready(val profile: ImportedProfile, val revision: Long, val country: String,
                    val routing: VpnRoutingSettings) : Preparation
        class NeedsWindow(val error: Int? = null) : Preparation
    }

    fun start(context: Context, clickedAt: Long, launchWindow: (String) -> Boolean) {
        if (VpnConnection.phase != VpnPhase.IDLE || VpnConnection.preparing) return
        val app = context.applicationContext
        val startup = VpnStartupTiming.begin(VpnStartSource.TILE, clickedAt)
        val key = startup.id
        if (!VpnConnection.reservePreparation(key)) return
        VpnConnection.record(R.string.vpn_quick_preparing)

        lateinit var work: Future<*>
        val timeout = Runnable {
            if (VpnConnection.ownsPreparation(key)) {
                work.cancel(true)
                finish(startup, R.string.vpn_permission_check_timeout)
            }
        }
        main.postDelayed(timeout, PREPARATION_TIMEOUT_MS)
        work = VpnConnection.systemCalls.submit {
            val prepared = prepare(app, startup)
            main.post {
                main.removeCallbacks(timeout)
                if (!VpnConnection.ownsPreparation(key)) {
                    startup.mark(VpnStartStage.ENDED)
                    return@post
                }
                when (prepared) {
                    is Preparation.NeedsWindow -> openWindow(startup, prepared.error, launchWindow)
                    is Preparation.Ready -> {
                        if (app.localProfiles().revision() != prepared.revision) {
                            openWindow(startup, R.string.profiles_selection_changed, launchWindow)
                            return@post
                        }
                        val profile = prepared.profile
                        VpnConnection.record(R.string.vpn_permission_granted)
                        VpnLaunchSettings(app).setPaused(false)
                        val started = VpnConnection.start(app, profile.config, prepared.country,
                            profile.documentKey, profile.name, startup = startup, routing = prepared.routing,
                            onBackgroundStartDenied = { openWindow(startup, null, launchWindow) })
                        VpnConnection.releasePreparation(key)
                        if (!started) startup.mark(VpnStartStage.ENDED)
                    }
                }
            }
        }
    }

    fun takeWindow(key: String?): Window? {
        val window = pendingWindow?.takeIf { it.startup.id == key } ?: return null
        pendingWindow = null
        return window.takeIf { VpnConnection.ownsPreparation(window.startup.id) }
    }

    private fun prepare(context: Context, startup: VpnStartupTrace): Preparation {
        startup.mark(VpnStartStage.PROFILE_LOOKUP)
        val read = try {
            context.localProfiles().readSelected()
        } catch (_: Exception) {
            return Preparation.NeedsWindow(R.string.profile_restore_error)
        }
        startup.mark(if (read.fromCache) VpnStartStage.PROFILE_CACHED else VpnStartStage.PROFILE_READ)
        val profile = read.selected.profile ?: return Preparation.NeedsWindow(R.string.vpn_profile_required)

        startup.mark(VpnStartStage.PERMISSION_CHECK)
        val consent = try {
            VpnService.prepare(context)
        } catch (_: RuntimeException) {
            return Preparation.NeedsWindow(R.string.vpn_permission_unavailable)
        }
        startup.mark(VpnStartStage.PERMISSION_CHECKED)
        if (consent != null) return Preparation.NeedsWindow()
        return try {
            val routing = SplitTunnelingSettings(context).snapshot()
            val country = VpnProfileLabels(context).country(profile.documentKey)
            Preparation.Ready(profile, read.selected.revision, country, routing)
        } catch (_: RuntimeException) {
            Preparation.NeedsWindow(R.string.vpn_start_error)
        }
    }

    private fun openWindow(startup: VpnStartupTrace, error: Int?, launchWindow: (String) -> Boolean) {
        if (VpnConnection.phase != VpnPhase.IDLE || !VpnConnection.reservePreparation(startup.id)) {
            startup.mark(VpnStartStage.ENDED)
            return
        }
        val window = Window(startup, error)
        pendingWindow = window
        startup.mark(VpnStartStage.WINDOW_REQUESTED)
        VpnConnection.record(R.string.vpn_quick_preparing)
        if (!launchWindow(startup.id)) {
            pendingWindow = null
            finish(startup, R.string.vpn_quick_interrupted)
            return
        }
        main.postDelayed({
            if (pendingWindow === window) {
                pendingWindow = null
                if (VpnConnection.ownsPreparation(startup.id)) finish(startup, R.string.vpn_quick_interrupted)
            }
        }, WINDOW_TIMEOUT_MS)
    }

    private fun finish(startup: VpnStartupTrace, message: Int) {
        startup.mark(VpnStartStage.ENDED)
        VpnConnection.releasePreparation(startup.id)
        VpnConnection.record(message, VpnError(message))
    }

    private const val PREPARATION_TIMEOUT_MS = 30_000L
    private const val WINDOW_TIMEOUT_MS = 10_000L
}
