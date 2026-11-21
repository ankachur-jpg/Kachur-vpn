package com.kachur.vpn

import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/** A numeric network with host bits cleared. No hostnames are resolved. */
internal data class IpRoutePrefix private constructor(val address: InetAddress, val length: Int) {
    override fun toString(): String = "${address.hostAddress}/$length"

    companion object {
        fun parse(input: String): IpRoutePrefix? {
            val parts = input.trim().split('/', limit = 2)
            val host = parts[0]
            val bytes = if (':' in host) {
                if (!ipv6Characters.matches(host) ||
                    ('.' in host && parseIpv4(host.substringAfterLast(':')) == null)
                ) return null
                try {
                    // A colon forces literal IPv6 parsing in InetAddress. Reject mapped IPv4.
                    (InetAddress.getByName(host) as? Inet6Address)?.address ?: return null
                } catch (_: UnknownHostException) {
                    return null
                }
            } else {
                parseIpv4(host) ?: return null
            }
            val width = bytes.size * 8
            val length = if (parts.size == 1) width else {
                val text = parts[1]
                if (!prefixLength.matches(text)) return null
                text.toInt().takeIf { it <= width } ?: return null
            }
            return from(bytes, length)
        }

        fun from(bytes: ByteArray, length: Int): IpRoutePrefix {
            val network = bytes.copyOf()
            for (i in network.indices) {
                val bits = (length - i * 8).coerceIn(0, 8)
                network[i] = (network[i].toInt() and (0xff shl (8 - bits))).toByte()
            }
            val address = if (network.size == 16) Inet6Address.getByAddress(null, network, -1)
                else InetAddress.getByAddress(network)
            return IpRoutePrefix(address, length)
        }

        private val ipv4Part = Regex("0|[1-9][0-9]{0,2}")
        private val ipv6Characters = Regex("[0-9a-fA-F:.]{2,45}")
        private val prefixLength = Regex("0|[1-9][0-9]{0,2}")

        private fun parseIpv4(input: String): ByteArray? {
            val parts = input.split('.', limit = 5)
            if (parts.size != 4) return null
            return ByteArray(4) { i ->
                val part = parts[i]
                if (!ipv4Part.matches(part)) return null
                val number = part.toInt()
                if (number > 255) return null
                number.toByte()
            }
        }
    }
}

internal class TooManyVpnRoutesException : Exception()

internal data class IpRoutePlan(val tunnel: List<IpRoutePrefix>, val direct: List<IpRoutePrefix>) {
    companion object {
        // Routes are sent in a single VpnConfig over Binder, together with other profile data.
        const val MAX_ROUTES = 4096

        fun build(
            allowed: Iterable<IpRoutePrefix>, bypass: Iterable<IpRoutePrefix>, nativeExclusion: Boolean,
        ): IpRoutePlan {
            val included = PrefixTrees(allowed)
            val excluded = PrefixTrees(bypass)
            val tunnel = mutableListOf<IpRoutePrefix>()
            val direct = mutableListOf<IpRoutePrefix>()

            fun emit(target: MutableList<IpRoutePrefix>, path: ByteArray, depth: Int) {
                // IPv4-mapped addresses use IPv4 routes. Subtraction can produce this reserved
                // IPv6 range even though imported IPv4-mapped literals are rejected.
                if (path.size == 16 && depth >= 96 && path[10].toInt() == -1 && path[11].toInt() == -1 &&
                    (0 until 10).all { path[it].toInt() == 0 }
                ) return
                val prefix = IpRoutePrefix.from(path, depth)
                // Local loopback never uses the VPN. Android refuses it as a route destination.
                if (prefix.address.isLoopbackAddress) return
                if (tunnel.size + direct.size == MAX_ROUTES) throw TooManyVpnRoutesException()
                target.add(prefix)
            }

            fun visit(vpn: PrefixTree?, skip: PrefixTree?, path: ByteArray, depth: Int, excludeOnly: Boolean) {
                when {
                    vpn == null -> return
                    skip == null && !excludeOnly && vpn == FullPrefix -> emit(tunnel, path, depth)
                    skip == null && excludeOnly -> return
                    vpn == FullPrefix && skip == FullPrefix -> {
                        if (nativeExclusion) emit(direct, path, depth)
                    }
                    else -> {
                        val emitInclude = nativeExclusion && !excludeOnly && vpn == FullPrefix
                        if (emitInclude) emit(tunnel, path, depth)
                        val byteIndex = depth / 8
                        val bit = 0x80 shr (depth % 8)
                        path[byteIndex] = (path[byteIndex].toInt() and bit.inv()).toByte()
                        visit(vpn.child(false), skip.child(false), path, depth + 1, excludeOnly || emitInclude)
                        path[byteIndex] = (path[byteIndex].toInt() or bit).toByte()
                        visit(vpn.child(true), skip.child(true), path, depth + 1, excludeOnly || emitInclude)
                    }
                }
            }

            // A normalized include never has an included child. An excluded child therefore
            // wins Android's longest-prefix lookup. On older Android emit only the complement.
            visit(included.ipv4, excluded.ipv4, ByteArray(4), 0, false)
            visit(included.ipv6, excluded.ipv6, ByteArray(16), 0, false)
            return IpRoutePlan(tunnel, direct)
        }
    }
}

private sealed interface PrefixTree
private object FullPrefix : PrefixTree
private class PrefixBranch(val left: PrefixTree?, val right: PrefixTree?) : PrefixTree

private fun PrefixTree?.child(right: Boolean): PrefixTree? = when (this) {
    is PrefixBranch -> if (right) this.right else left
    else -> this
}

private class PrefixTrees(prefixes: Iterable<IpRoutePrefix>) {
    var ipv4: PrefixTree? = null
        private set
    var ipv6: PrefixTree? = null
        private set

    init {
        for (prefix in prefixes) {
            val bytes = prefix.address.address
            if (bytes.size == 4) ipv4 = insert(ipv4, bytes, prefix.length, 0)
            else ipv6 = insert(ipv6, bytes, prefix.length, 0)
        }
    }

    private fun insert(node: PrefixTree?, bytes: ByteArray, length: Int, depth: Int): PrefixTree {
        if (depth == length || node == FullPrefix) return FullPrefix
        val right = (bytes[depth / 8].toInt() and (0x80 shr (depth % 8))) != 0
        val leftChild = if (right) node.child(false) else insert(node.child(false), bytes, length, depth + 1)
        val rightChild = if (right) insert(node.child(true), bytes, length, depth + 1) else node.child(true)
        return if (leftChild == FullPrefix && rightChild == FullPrefix) FullPrefix else PrefixBranch(leftChild, rightChild)
    }
}
