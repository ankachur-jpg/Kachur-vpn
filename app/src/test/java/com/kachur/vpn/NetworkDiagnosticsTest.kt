package com.kachur.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class NetworkDiagnosticsTest {
    @Test
    fun acceptsOnlyExpectedEchoRepliesForTheRequestedAddressFamily() {
        val payload = ByteBuffer.allocate(16).apply { putLong(8, 478L) }.array()
        assertTrue(isEchoReply(payload, 16, false, 478L))
        assertFalse(isEchoReply(payload, 15, false, 478L))
        assertFalse(isEchoReply(payload, 16, false, 479L))
        assertFalse(isEchoReply(payload, 16, true, 478L))
        payload[0] = 129.toByte()
        assertTrue(isEchoReply(payload, 16, true, 478L))
    }

    @Test
    fun computesRttJitterAndLossFromActualAttempts() {
        val result = ProbeStats.from(listOf(ProbeReading.Reply(14.0), ProbeReading.NoReply, ProbeReading.Reply(22.0)))
        assertEquals(3, result.samples)
        assertEquals(2, result.replies)
        assertEquals(18.0, checkNotNull(result.average), 0.001)
        assertEquals(8.0, checkNotNull(result.jitter), 0.001)
        assertEquals(33, result.failedPercent)
    }

    @Test
    fun reportsUnavailableSeparatelyFromMeasuredLoss() {
        val unavailable = ProbeStats.from(listOf(ProbeReading.Unavailable))
        assertEquals(0, unavailable.samples)
        assertNull(unavailable.average)
        assertNull(unavailable.failedPercent)
        val lost = ProbeStats.from(listOf(ProbeReading.NoReply, ProbeReading.NoReply))
        assertEquals(100, lost.failedPercent)
        assertNull(lost.average)
        assertNull(lost.jitter)
        val onlyOneReply = ProbeStats.from(listOf(ProbeReading.Reply(11.0), ProbeReading.Unavailable))
        assertEquals(0, onlyOneReply.failedPercent)
        assertNull(onlyOneReply.jitter)
    }

    @Test
    fun routeMembershipRespectsTheAddressFamilyAndCidrBoundary() {
        fun prefix(value: String) = checkNotNull(IpRoutePrefix.parse(value))
        val ipv4 = prefix("192.0.2.0/25")
        assertTrue(ipv4.contains(prefix("192.0.2.127").address))
        assertFalse(ipv4.contains(prefix("192.0.2.128").address))
        assertFalse(ipv4.contains(prefix("2001:db8::1").address))
        assertTrue(prefix("::/0").contains(prefix("2001:db8::1").address))
    }
}
