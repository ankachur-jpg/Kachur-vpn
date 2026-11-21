package com.kachur.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IpRoutingTest {
    @Test
    fun validatesAndCanonicalizesLiteralIpv4AndIpv6() {
        assertEquals("192.0.2.7/32", IpRoutePrefix.parse(" 192.0.2.7 ").toString())
        assertEquals("192.0.2.0/24", IpRoutePrefix.parse("192.0.2.7/24").toString())
        assertEquals("0.0.0.0/0", IpRoutePrefix.parse("192.0.2.7/0").toString())
        assertEquals(prefix("2001:db8::/48"), prefix("2001:0db8:0000:1234::abcd/48"))
        assertEquals(128, prefix("2001:db8::7").length)

        for (invalid in listOf(
            "example.test", "192.0.2", "192.0.2.256", "192.0.002.1", "192.0.2.1/33", "192.0.2.1/-1",
            "192.0.2.1/ 24", "192.0.2.1/24/24", "::ffff:192.0.2.7", "fe80::1%wlan0", "2001:db8::1/129",
            "2001:DBX::", "2001:db8:::1",
        )) assertNull(invalid, IpRoutePrefix.parse(invalid))
    }

    @Test
    fun routesTheExactSetDifferenceEvenWithMoreSpecificAllowedIps() {
        val allowed = listOf("192.0.2.0/25", "192.0.2.16/28", "192.0.2.192/26").map(::prefix)
        val bypass = listOf("192.0.2.0/27", "192.0.2.128/25", "198.51.100.0/24").map(::prefix)
        for (native in listOf(false, true)) {
            val plan = IpRoutePlan.build(allowed, bypass, native)
            for (host in 0..255) {
                assertEquals("host=$host, native=$native", host in 32..127, plan.routesThroughVpn("192.0.2.$host"))
            }
            assertFalse(plan.routesThroughVpn("198.51.100.1"))
            if (native) {
                assertEquals(listOf(prefix("192.0.2.0/25")), plan.tunnel)
                assertEquals(listOf(prefix("192.0.2.0/27"), prefix("192.0.2.192/26")), plan.direct)
            } else {
                assertTrue(plan.direct.isEmpty())
            }
        }
    }

    @Test
    fun handlesDefaultRoutesFamiliesAndLoopback() {
        val allowed = listOf("0.0.0.0/0", "::/0").map(::prefix)
        val bypass = listOf("192.0.2.7", "2001:db8::/32", "127.0.0.1", "::1").map(::prefix)
        for (native in listOf(false, true)) {
            val plan = IpRoutePlan.build(allowed, bypass, native)
            assertFalse(plan.routesThroughVpn("192.0.2.7"))
            assertTrue(plan.routesThroughVpn("192.0.2.8"))
            assertFalse(plan.routesThroughVpn("2001:db8::7"))
            assertTrue(plan.routesThroughVpn("2001:db9::7"))
            assertFalse((plan.tunnel + plan.direct).any { it.address.isLoopbackAddress })
        }
        val ipv4Only = IpRoutePlan.build(allowed.take(1), bypass, nativeExclusion = true)
        assertEquals(listOf(prefix("192.0.2.7")), ipv4Only.direct)
    }

    @Test
    fun refusesAnOversizedPlanWithoutReturningPartialRoutes() {
        val bypass = (0..IpRoutePlan.MAX_ROUTES).map { i -> prefix("198.0.${i / 128}.${(i % 128) * 2}") }
        for (native in listOf(false, true)) {
            assertThrows(TooManyVpnRoutesException::class.java) {
                IpRoutePlan.build(listOf(prefix("0.0.0.0/0")), bypass, native)
            }
        }
    }

    private fun IpRoutePlan.routesThroughVpn(ip: String): Boolean {
        val bytes = prefix(ip).address.address
        val include = tunnel.filter { IpRoutePrefix.from(bytes, it.length) == it }.maxOfOrNull { it.length }
        val exclude = direct.filter { IpRoutePrefix.from(bytes, it.length) == it }.maxOfOrNull { it.length }
        return include != null && (exclude == null || include > exclude)
    }

    private fun prefix(value: String) = checkNotNull(IpRoutePrefix.parse(value))
}
