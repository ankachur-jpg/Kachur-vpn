package com.kachur.vpn

internal data class VpnSessionReading(val traffic: VpnTraffic?, val elapsedSeconds: Long)

/** Main-thread projection of the existing notification counters and session start time. */
internal object VpnTrafficUi {
    private val observers = mutableSetOf<() -> Unit>()
    private var owner = 0L
    var latest: VpnSessionReading? = null
        private set

    fun publish(attempt: Long, traffic: VpnTraffic?, connectedAt: Long) {
        if (!VpnConnection.ownsAttempt(attempt)) return
        owner = attempt
        update(connectedAt.takeIf { it > 0L }?.let {
            VpnSessionReading(traffic, (System.currentTimeMillis() - it).coerceAtLeast(0L) / 1000)
        })
    }

    fun clear(attempt: Long) {
        if (attempt != owner) return
        owner = 0L
        update(null)
    }

    fun observe(observer: () -> Unit) { observers.add(observer) }
    fun removeObserver(observer: () -> Unit) { observers.remove(observer) }

    private fun update(sample: VpnSessionReading?) {
        if (latest == sample) return
        latest = sample
        observers.toList().forEach { it() }
    }
}
