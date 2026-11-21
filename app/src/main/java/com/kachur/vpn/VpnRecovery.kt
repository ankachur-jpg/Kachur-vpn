package com.kachur.vpn

import android.os.Handler
import kotlin.random.Random

/** Main-thread policy for a single user-started VPN session. */
internal class VpnRecovery(
    private val handler: Handler,
    private val state: (Int) -> Unit,
    private val releaseTunnel: () -> Unit,
    private val startTunnel: () -> Unit,
    private val jitter: (Long) -> Long = { Random.nextLong(it + 1L) },
) {
    private var enabled = true
    private var closed = false
    private var networkKnown = false
    private var network: Long? = null
    private var nextDelayMs = INITIAL_DELAY_MS
    private var inFlight = false
    var recovering = false
        private set

    private val attemptTimeout = Runnable { if (inFlight) recoverAfterFailure() }
    private val retry = Runnable {
        if (!recovering || inFlight || network == null) return@Runnable
        inFlight = true
        handler.postDelayed(attemptTimeout, ATTEMPT_TIMEOUT_MS)
        state(R.string.vpn_recovery_connecting)
        if (inFlight) startTunnel()
    }

    fun networkChanged(handle: Long?) {
        if (closed || (networkKnown && network == handle)) return
        val changed = networkKnown
        networkKnown = true
        network = handle
        if (enabled && (changed || recovering)) begin()
    }

    fun recoverAfterFailure(): Boolean {
        if (closed || !enabled) return false
        if (inFlight) nextDelayMs = (nextDelayMs * 2).coerceAtMost(MAX_DELAY_MS)
        begin()
        return true
    }

    fun finishRecovery() {
        clearRecoveryTimers()
        recovering = false
        inFlight = false
    }

    // A new tunnel has its own handshake and counters. Old engine callbacks are discarded by the service.
    fun handshakeReceived(): Boolean {
        if (closed || (recovering && !inFlight)) return false
        finishRecovery()
        nextDelayMs = INITIAL_DELAY_MS
        return true
    }

    // Return true when the caller must end an interrupted recovery session.
    fun setEnabled(enabled: Boolean): Boolean {
        this.enabled = enabled
        if (enabled || !recovering) return false
        finishRecovery()
        return true
    }

    fun cancel() {
        closed = true
        finishRecovery()
    }

    private fun begin() {
        val needsRelease = !recovering || inFlight
        clearRecoveryTimers()
        recovering = true
        inFlight = false
        if (needsRelease) releaseTunnel()
        if (network == null) {
            state(R.string.vpn_recovery_waiting_network)
        } else {
            handler.postDelayed(retry, nextDelayMs - jitter(nextDelayMs / 10))
            state(R.string.vpn_recovery_scheduled)
        }
    }

    private fun clearRecoveryTimers() {
        handler.removeCallbacks(retry)
        handler.removeCallbacks(attemptTimeout)
    }

    companion object {
        const val INITIAL_DELAY_MS = 1_000L
        const val MAX_DELAY_MS = 5 * 60_000L
        const val ATTEMPT_TIMEOUT_MS = 40_000L
    }
}
