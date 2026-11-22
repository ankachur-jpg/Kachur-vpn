package com.kachur.vpn

import android.app.AlertDialog
import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

class VpnTileService : TileService() {
    private val observer: () -> Unit = ::render
    private var lastStatus: Pair<VpnNoticeStatus, Int>? = null

    override fun onStartListening() {
        super.onStartListening()
        lastStatus = null
        VpnConnection.observe(observer)
        render()
    }

    override fun onStopListening() {
        VpnConnection.removeObserver(observer)
        super.onStopListening()
    }

    override fun onDestroy() {
        VpnConnection.removeObserver(observer)
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val clickedAt = SystemClock.elapsedRealtime()
        val requested = VpnConnection.noticeStatus
        val phase = VpnConnection.phase
        if (requested.tileState(phase) == Tile.STATE_UNAVAILABLE) return
        val attempt = VpnConnection.attemptId
        val stopped = VpnConnection.manualStopGeneration
        val reset = VpnConnection.resetGeneration
        val action = Runnable {
            when (requested) {
                VpnNoticeStatus.OFF -> if (VpnConnection.noticeStatus == VpnNoticeStatus.OFF &&
                    stopped == VpnConnection.manualStopGeneration && reset == VpnConnection.resetGeneration
                ) VpnTileStart.start(this, clickedAt, ::launchQuickWindow)
                VpnNoticeStatus.CONNECTING, VpnNoticeStatus.CONNECTED, VpnNoticeStatus.RECONNECTING ->
                    attempt?.let(VpnConnection::stopFromNotification)
                else -> Unit
            }
        }
        if (isLocked && isSecure) unlockAndRun(action) else action.run()
    }

    private fun render() {
        val tile = qsTile ?: return
        val status = VpnConnection.noticeStatus
        val state = status.tileState(VpnConnection.phase)
        val snapshot = status to state
        if (lastStatus == snapshot) return
        lastStatus = snapshot
        val description = getString(status.label)
        val name = getString(R.string.app_name)
        tile.state = state
        tile.label = name
        tile.contentDescription = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) name
            else getString(R.string.vpn_control_title, name, description)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = description
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tile.stateDescription = description
        tile.updateTile()
    }

    @Suppress("DEPRECATION")
    private fun launchQuickWindow(key: String): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(VpnQuickActivity.tilePendingIntent(this, key))
            } else {
                startActivityAndCollapse(VpnQuickActivity.tileIntent(this, key))
            }
            return true
        } catch (_: RuntimeException) {
            Toast.makeText(this, R.string.vpn_quick_interrupted, Toast.LENGTH_SHORT).show()
            return false
        }
    }
}

internal fun VpnNoticeStatus.tileState(phase: VpnPhase): Int = when (this) {
    VpnNoticeStatus.CONNECTED -> Tile.STATE_ACTIVE
    VpnNoticeStatus.OFF, VpnNoticeStatus.RECONNECTING -> Tile.STATE_INACTIVE
    // Before the tunnel exists repeated taps are ignored. Once installed, waiting can be cancelled.
    VpnNoticeStatus.CONNECTING -> if (phase == VpnPhase.WAITING) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
    else -> Tile.STATE_UNAVAILABLE
}

internal fun MainActivity.requestVpnTile() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val manager = getSystemService(StatusBarManager::class.java)
        if (manager != null) {
            try {
                manager.requestAddTileService(
                    ComponentName(this, VpnTileService::class.java),
                    getString(R.string.app_name),
                    Icon.createWithResource(this, R.drawable.ic_vpn_tile),
                    mainExecutor,
                ) { result ->
                    val outcome = when (result) {
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> R.string.vpn_tile_added
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> R.string.vpn_tile_already_added
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> null
                        else -> R.string.vpn_tile_request_failed
                    }
                    if (outcome != null) Toast.makeText(this, outcome, Toast.LENGTH_SHORT).show()
                }
                return
            } catch (_: RuntimeException) {
                // Some devices restrict the system request. The editor is still available.
            }
        }
    }
    AlertDialog.Builder(this).setTitle(R.string.vpn_tile_add).setMessage(R.string.vpn_tile_add_manually)
        .setPositiveButton(android.R.string.ok, null).show()
}
