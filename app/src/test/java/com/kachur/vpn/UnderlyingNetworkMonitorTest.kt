package com.kachur.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class UnderlyingNetworkMonitorTest {
    @Test
    fun detectsWifiAndCellularHandoffsAndValidationLossWithoutFollowingVpn() {
        val context: Context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val main = Looper.getMainLooper()
        val seen = mutableListOf<Long?>()
        val monitor = UnderlyingNetworkMonitor(context, Handler(main)) { seen.add(it) }
        monitor.start()
        val callback = shadowOf(manager).networkCallbacks.single()
        val wifi = ShadowNetwork.newInstance(10)
        val mobile = ShadowNetwork.newInstance(11)
        val vpn = ShadowNetwork.newInstance(12)
        fun settle() = shadowOf(main).idleFor(Duration.ofSeconds(2))

        callback.onCapabilitiesChanged(wifi, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
        callback.onCapabilitiesChanged(mobile, capabilities(NetworkCapabilities.TRANSPORT_CELLULAR))
        settle()
        assertEquals(listOf(wifi.networkHandle), seen)
        callback.onLost(wifi)
        settle()
        callback.onCapabilitiesChanged(mobile, capabilities(NetworkCapabilities.TRANSPORT_CELLULAR, online = false))
        callback.onCapabilitiesChanged(vpn, capabilities(NetworkCapabilities.TRANSPORT_VPN))
        settle()
        callback.onCapabilitiesChanged(mobile, capabilities(NetworkCapabilities.TRANSPORT_CELLULAR))
        settle()
        callback.onCapabilitiesChanged(wifi, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
        settle()
        assertEquals(listOf(wifi.networkHandle, mobile.networkHandle, null, mobile.networkHandle, wifi.networkHandle), seen)
        monitor.close()
        callback.onLost(wifi)
        settle()
        assertEquals(5, seen.size)
        assertTrue(shadowOf(manager).networkCallbacks.isEmpty())
    }

    @Test
    fun blockedOrLostSelectedNetworkIsReportedWithoutDebouncingTheLoss() {
        val context: Context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val main = Looper.getMainLooper()
        val seen = mutableListOf<Long?>()
        val monitor = UnderlyingNetworkMonitor(context, Handler(main)) { seen.add(it) }
        monitor.start()
        try {
            val callback = shadowOf(manager).networkCallbacks.single()
            val wifi = ShadowNetwork.newInstance(20)
            callback.onCapabilitiesChanged(wifi, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
            shadowOf(main).idleFor(Duration.ofSeconds(2))

            callback.onBlockedStatusChanged(wifi, true)
            assertEquals(listOf(wifi.networkHandle, null), seen)
            callback.onBlockedStatusChanged(wifi, false)
            shadowOf(main).idleFor(Duration.ofSeconds(2))
            callback.onLost(wifi)
            assertEquals(listOf(wifi.networkHandle, null, wifi.networkHandle, null), seen)
        } finally {
            monitor.close()
        }
    }

    private fun capabilities(transport: Int, online: Boolean = true): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().apply {
            shadowOf(this).addTransportType(transport)
            shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (transport != NetworkCapabilities.TRANSPORT_VPN) {
                shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            } else {
                shadowOf(this).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            }
            if (online) shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
}
