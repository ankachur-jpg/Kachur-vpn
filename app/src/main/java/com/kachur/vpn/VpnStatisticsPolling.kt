package com.kachur.vpn

import android.os.Handler

/** Schedules one read at a time for the lifetime of a single native tunnel. */
internal class VpnStatisticsPolling(
    private val main: Handler,
    fast: Boolean,
    private val read: (finished: () -> Unit) -> Unit,
) {
    private var active = false
    private var reading = false
    private var interval = intervalFor(fast)

    private val tick = Runnable {
        if (!active) return@Runnable
        reading = true
        read(::completed)
    }

    fun start() {
        if (active) return
        active = true
        main.postDelayed(tick, FIRST_READ_MS)
    }

    fun setFast(fast: Boolean) {
        val next = intervalFor(fast)
        if (next == interval) return
        interval = next
        if (active && !reading) {
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
        if (active) main.postDelayed(tick, interval)
    }

    private fun intervalFor(fast: Boolean) = if (fast) FAST_MS else BACKGROUND_MS

    companion object {
        const val FIRST_READ_MS = 1_000L
        const val FAST_MS = 2_000L
        const val BACKGROUND_MS = 60_000L
    }
}
