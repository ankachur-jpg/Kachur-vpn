package com.kachur.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler

/** Observe physical networks without binding sockets or initiating a network request. */
internal class UnderlyingNetworkMonitor(context: Context, private val handler: Handler, private val changed: (Long?) -> Unit) {
    private val manager = checkNotNull(context.getSystemService(ConnectivityManager::class.java))
    private val validated = mutableMapOf<Network, Int>()
    private var preferred: Network? = null
    private var selected: Network? = null
    private var reported = false
    private var registered = false
    private var bestRegistered = false

    private val dispatch = Runnable {
        val best = validated.maxOfOrNull { it.value }
        val next = preferred?.takeIf(validated::containsKey)
            ?: selected?.takeIf { best != null && validated[it] == best }
            ?: validated.entries.firstOrNull { it.value == best }?.key
        if (!reported || next != selected) {
            selected = next
            reported = true
            changed(next?.networkHandle)
        }
    }

    private val available = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (!registered) return
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            ) {
                validated[network] = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 2
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
                    else -> 0
                }
            } else {
                validated.remove(network)
            }
            scheduleDispatch()
        }

        override fun onLost(network: Network) {
            if (!registered) return
            validated.remove(network)
            scheduleDispatch()
        }
    }

    private val best = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!bestRegistered) return
            preferred = network
            scheduleDispatch()
        }

        override fun onLost(network: Network) {
            if (!bestRegistered || preferred != network) return
            preferred = null
            scheduleDispatch()
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        manager.registerNetworkCallback(request, available, handler)
        registered = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                manager.registerBestMatchingNetworkCallback(request, best, handler)
                bestRegistered = true
            } catch (_: RuntimeException) {
                // The validated network list still supplies a fallback.
            }
        }
        scheduleDispatch()
    }

    fun close() {
        handler.removeCallbacks(dispatch)
        if (registered) {
            registered = false
            unregister(available)
        }
        if (bestRegistered) {
            bestRegistered = false
            unregister(best)
        }
        validated.clear()
    }

    private fun scheduleDispatch() {
        handler.removeCallbacks(dispatch)
        handler.postDelayed(dispatch, 1_200L)
    }

    private fun unregister(callback: ConnectivityManager.NetworkCallback) {
        try {
            manager.unregisterNetworkCallback(callback)
        } catch (_: RuntimeException) {
            // Android may already have removed the callback during service shutdown.
        }
    }
}
