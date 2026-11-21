package com.kachur.vpn

import android.os.Handler

/** Main-thread policy for a single user-started VPN session. */
internal class VpnRecovery(
    private val handler: Handler,
    private val state: (Int) -> Unit,
    private val releaseTunnel: () -> Unit,
    private val startTunnel: () -> Unit,
    private val failed: (Int) -> Unit,
) {
    private var closed = false
    private var networkKnown = false
    private var network: Long? = null
    private var attempts = 0
    private var inFlight = false
    var recovering = false
        private set

    private val attemptTimeout = Runnable { retryFailed() }
    private val retry = Runnable {
        if (recovering && network != null) {
            attempts++
            inFlight = true
            state(R.string.vpn_recovery_connecting)
            handler.postDelayed(attemptTimeout, ATTEMPT_TIMEOUT_MS)
            startTunnel()
        }
    }
    private val recoveryTimeout = Runnable { finishWithError(R.string.vpn_recovery_timeout) }
    private val stable = Runnable { attempts = 0 }

    fun networkChanged(handle: Long?) {
        if (closed || (networkKnown && network == handle)) return
        val changed = networkKnown
        networkKnown = true
        network = handle
        if (changed || recovering) begin()
    }

    // Preserve local-server profiles on the initial start, even on unvalidated Wi-Fi.
    fun waitAfterOfflineFailure(): Boolean {
        if (closed || network != null) return false
        begin()
        return true
    }

    fun retryFailed() {
        if (!inFlight) return
        begin()
    }

    fun waitForPeer() {
        clearRecoveryTimers()
        recovering = false
        inFlight = false
    }

    // A new tunnel has its own handshake and counters. Old engine callbacks are discarded by the service.
    fun handshakeReceived(): Boolean {
        if (closed || (recovering && !inFlight)) return false
        if (recovering) waitForPeer()
        if (attempts > 0) handler.postDelayed(stable, STABLE_NETWORK_MS)
        return true
    }

    fun cancel() {
        closed = true
        recovering = false
        inFlight = false
        clearRecoveryTimers()
        handler.removeCallbacks(stable)
    }

    private fun begin() {
        if (!recovering) {
            recovering = true
            handler.postDelayed(recoveryTimeout, RECOVERY_TIMEOUT_MS)
        }
        handler.removeCallbacks(stable)
        handler.removeCallbacks(retry)
        handler.removeCallbacks(attemptTimeout)
        inFlight = false
        releaseTunnel()
        when {
            network == null -> state(R.string.vpn_recovery_waiting_network)
            attempts == delays.size -> finishWithError(R.string.vpn_recovery_exhausted)
            else -> {
                state(R.string.vpn_recovery_scheduled)
                handler.postDelayed(retry, delays[attempts])
            }
        }
    }

    private fun finishWithError(message: Int) {
        cancel()
        failed(message)
    }

    private fun clearRecoveryTimers() {
        handler.removeCallbacks(retry)
        handler.removeCallbacks(attemptTimeout)
        handler.removeCallbacks(recoveryTimeout)
    }

    companion object {
        private val delays = longArrayOf(1_000L, 3_000L, 8_000L)
        const val ATTEMPT_TIMEOUT_MS = 40_000L
        const val RECOVERY_TIMEOUT_MS = 5 * 60_000L
        const val STABLE_NETWORK_MS = 60_000L
    }
}
