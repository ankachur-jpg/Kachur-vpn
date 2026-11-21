package com.kachur.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import kotlin.math.abs

internal class DiagnosticSession(val options: VpnStartOptions, val service: VpnService, val confirmed: Boolean)

internal enum class DiagnosticTarget(val label: Int, val throughVpn: Boolean, val literal: String?) {
    SERVER(R.string.diag_server, false, null),
    FIRST_DIRECT(R.string.diag_first_direct, false, "1.1.1.1"),
    FIRST_VPN(R.string.diag_first_vpn, true, "1.1.1.1"),
    SECOND_DIRECT(R.string.diag_second_direct, false, "9.9.9.9"),
    SECOND_VPN(R.string.diag_second_vpn, true, "9.9.9.9"),
}

internal sealed interface ProbeReading {
    data class Reply(val millis: Double) : ProbeReading
    data object NoReply : ProbeReading
    data object Unavailable : ProbeReading
}

internal data class ProbeStats(val samples: Int, val replies: Int, val average: Double?, val jitter: Double?, val failedPercent: Int?) {
    companion object {
        fun from(readings: List<ProbeReading>): ProbeStats {
            val times = readings.filterIsInstance<ProbeReading.Reply>().map { it.millis }
            val samples = readings.count { it != ProbeReading.Unavailable }
            val average = times.takeIf { it.isNotEmpty() }?.average()
            val jitter = if (times.size < 2) null else times.zipWithNext { a, b -> abs(a - b) }.average()
            return ProbeStats(samples, times.size, average, jitter, if (samples == 0) null else (samples - times.size) * 100 / samples)
        }
    }
}

internal data class DiagnosticResult(val target: DiagnosticTarget, val icmp: ProbeStats?, val tcp: ProbeStats?)

internal class NetworkDiagnostics(context: Context) {
    private val networks = checkNotNull(context.getSystemService(ConnectivityManager::class.java))

    @Suppress("DEPRECATION")
    fun measure(session: DiagnosticSession?, selectedProfile: AwgConfig?, cancelled: () -> Boolean): List<DiagnosticResult> {
        val active = networks.activeNetwork
        val activeIsVpn = active?.let(networks::getNetworkCapabilities)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val vpn = active?.takeIf { activeIsVpn && session?.confirmed == true }
        val direct = when {
            active != null && !activeIsVpn -> active
            session != null -> networks.allNetworks.mapNotNull { network ->
                val caps = networks.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                ) return@mapNotNull null
                val rank = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) 1 else 0
                network to rank
            }.maxByOrNull { it.second }?.first
            else -> null
        }
        val profile = session?.options?.profile ?: selectedProfile
        val allowed = profile?.peers.orEmpty().flatMap { peer -> parsePrefixes(peer["AllowedIPs"]?.split(',').orEmpty()) }
        val excluded = parsePrefixes(session?.options?.routing?.ipRoutes.orEmpty())

        return buildList {
            for (target in DiagnosticTarget.entries) {
                if (cancelled()) break
                val network = if (target.throughVpn) vpn else direct
                val address = network?.let { path ->
                    if (target.literal == null) serverAddress(path, profile) else IpRoutePrefix.parse(target.literal)?.address
                }
                if (network == null || address == null ||
                    (target.throughVpn && (allowed.none { it.contains(address) } || excluded.any { it.contains(address) }))
                ) {
                    add(DiagnosticResult(target, null, null))
                    continue
                }
                val protector = session?.service.takeUnless { target.throughVpn }
                val icmp = samples(cancelled) { icmp(network, protector, address) }
                val tcp = if (icmp.replies == 0 && target.literal != null && !cancelled()) {
                    samples(cancelled) { tcp(network, protector, address) }
                } else null
                add(DiagnosticResult(target, icmp, tcp))
            }
        }
    }

    private fun parsePrefixes(values: Iterable<String>) = values.mapNotNull(IpRoutePrefix::parse)

    private fun serverAddress(network: Network, profile: AwgConfig?): InetAddress? {
        val endpoint = profile?.peers?.firstNotNullOfOrNull { it["Endpoint"] } ?: return null
        val host = endpoint.substringBeforeLast(':').removeSurrounding("[", "]")
        return try { IpRoutePrefix.parse(host)?.address ?: network.getByName(host) } catch (_: Exception) { null }
    }

    private fun samples(cancelled: () -> Boolean, probe: () -> ProbeReading): ProbeStats {
        val readings = mutableListOf<ProbeReading>()
        repeat(3) {
            if (cancelled()) return ProbeStats.from(readings)
            val result = probe()
            readings.add(result)
            if (result == ProbeReading.Unavailable) return ProbeStats.from(readings)
        }
        return ProbeStats.from(readings)
    }

    private fun icmp(network: Network, service: VpnService?, target: InetAddress): ProbeReading {
        val ipv6 = target is Inet6Address
        val fd = try {
            Os.socket(if (ipv6) OsConstants.AF_INET6 else OsConstants.AF_INET, OsConstants.SOCK_DGRAM,
                if (ipv6) OsConstants.IPPROTO_ICMPV6 else OsConstants.IPPROTO_ICMP)
        } catch (_: Exception) { return ProbeReading.Unavailable }
        var sent = false
        try {
            network.bindSocket(fd)
            if (service != null && !ParcelFileDescriptor.dup(fd).use { service.protect(it.fd) }) return ProbeReading.Unavailable
            Os.connect(fd, target, 0)
            val nonce = random.nextLong()
            // Unprivileged datagram ICMP sockets let Linux set the identifier and checksum.
            val packet = ByteBuffer.allocate(16).put(if (ipv6) 128.toByte() else 8.toByte())
                .put(0.toByte()).putShort(0).putShort(0).putShort(1).putLong(nonce).array()
            val start = SystemClock.elapsedRealtimeNanos()
            sent = Os.write(fd, packet, 0, packet.size) == packet.size
            if (!sent) return ProbeReading.Unavailable
            val poll = StructPollfd()
            poll.fd = fd
            poll.events = OsConstants.POLLIN.toShort()
            val reply = ByteArray(64)
            while (elapsedMs(start) < TIMEOUT_MS) {
                val remaining = (TIMEOUT_MS - elapsedMs(start)).toInt().coerceAtLeast(1)
                if (Os.poll(arrayOf(poll), remaining) == 0 || (poll.revents.toInt() and OsConstants.POLLIN) == 0) break
                val count = Os.read(fd, reply, 0, reply.size)
                if (count >= 16 && (reply[0].toInt() and 255) == (if (ipv6) 129 else 0) &&
                    reply[1] == 0.toByte() && ByteBuffer.wrap(reply).getLong(8) == nonce
                ) return ProbeReading.Reply(elapsedMs(start))
            }
            return ProbeReading.NoReply
        } catch (_: Exception) {
            return if (sent) ProbeReading.NoReply else ProbeReading.Unavailable
        } finally {
            try { Os.close(fd) } catch (_: ErrnoException) { }
        }
    }

    private fun tcp(network: Network, service: VpnService?, target: InetAddress): ProbeReading {
        var started = false
        return try {
            Socket().use { socket ->
                network.bindSocket(socket)
                if (service != null && !service.protect(socket)) return ProbeReading.Unavailable
                val start = SystemClock.elapsedRealtimeNanos()
                started = true
                socket.connect(InetSocketAddress(target, 443), TIMEOUT_MS)
                ProbeReading.Reply(elapsedMs(start))
            }
        } catch (_: Exception) { if (started) ProbeReading.NoReply else ProbeReading.Unavailable }
    }

    private fun elapsedMs(start: Long): Double = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0

    private companion object {
        const val TIMEOUT_MS = 900
        val random = SecureRandom()
    }
}
