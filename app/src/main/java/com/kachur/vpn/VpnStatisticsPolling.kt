package com.kachur.vpn

import android.os.Handler
import android.os.SystemClock

/** Schedules one read at a time for the lifetime of a single native tunnel. */
internal class VpnStatisticsPolling(
    private val main: Handler,
    fast: Boolean,
    private val read: (finished: () -> Unit) -> Unit,
) {
    private var active = false
    private var reading = false
    private var interval = intervalFor(fast)
    private var handshakeUntil = 0L

    private val tick = Runnable {
        if (!active) return@Runnable
        reading = true
        read(::completed)
    }

    fun start(probingHandshake: Boolean = false) {
        if (active) return
        active = true
        handshakeUntil = if (probingHandshake) SystemClock.elapsedRealtime() + HANDSHAKE_WINDOW_MS else 0L
        main.postDelayed(tick, if (probingHandshake) HANDSHAKE_READ_MS else FIRST_READ_MS)
    }

    fun handshakeObserved() { handshakeUntil = 0L }

    fun setFast(fast: Boolean) {
        val next = intervalFor(fast)
        if (next == interval) return
        interval = next
        if (active && !reading && handshakeUntil <= SystemClock.elapsedRealtime()) {
            main.removeCallbacks(tick)
            // Refresh the cached counters when the user returns.
            main.postDelayed(tick, if (fast) 0L else interval)
        }
    }

    fun stop() {
        active = false
        main.removeCallbacks(tick)
    }

    private fun completed() {
        reading = false
        val remaining = handshakeUntil - SystemClock.elapsedRealtime()
        if (active) main.postDelayed(tick, if (remaining > 0L) minOf(HANDSHAKE_READ_MS, remaining) else interval)
    }

    private fun intervalFor(fast: Boolean) = if (fast) FAST_MS else BACKGROUND_MS

    companion object {
        const val HANDSHAKE_READ_MS = 250L
        const val HANDSHAKE_WINDOW_MS = 6_000L
        const val FIRST_READ_MS = 1_000L
        const val FAST_MS = 2_000L
        const val BACKGROUND_MS = 60_000L
    }
}
