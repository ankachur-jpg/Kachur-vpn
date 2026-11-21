package com.kachur.vpn

/** Main-thread projection of the counters already collected for the VPN notification. */
internal object VpnTrafficUi {
    private val observers = mutableSetOf<() -> Unit>()
    private var owner = 0L
    var latest: VpnTraffic? = null
        private set

    fun publish(attempt: Long, sample: VpnTraffic?) {
        if (!VpnConnection.ownsAttempt(attempt)) return
        owner = attempt
        update(sample)
    }

    fun clear(attempt: Long) {
        if (attempt != owner) return
        owner = 0L
        update(null)
    }

    fun observe(observer: () -> Unit) { observers.add(observer) }
    fun removeObserver(observer: () -> Unit) { observers.remove(observer) }

    private fun update(sample: VpnTraffic?) {
        if (latest == sample) return
        latest = sample
        observers.toList().forEach { it() }
    }
}
