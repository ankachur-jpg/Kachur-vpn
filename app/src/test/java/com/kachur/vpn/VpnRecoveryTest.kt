package com.kachur.vpn

import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VpnRecoveryTest {
    @Test
    fun networkLossPausesRetriesAndRestoredInternetRequiresANewHandshake() {
        val session = Session()
        with(session) {
            policy.networkChanged(1L)
            policy.networkChanged(1L)
            assertEquals(0, releases)
            policy.networkChanged(null)
            assertEquals(R.string.vpn_recovery_waiting_network, state)
            advance(40_000L)
            assertEquals(0, starts)
            assertFalse(policy.handshakeReceived())
            policy.networkChanged(2L)
            advance(1_000L)
            assertEquals(1, starts)
            assertTrue(policy.recovering)
            assertTrue(policy.handshakeReceived())
            advance(VpnRecovery.ATTEMPT_TIMEOUT_MS)
            assertEquals(1, starts)
            assertFalse(policy.recovering)
            assertNull(error)
        }
    }

    @Test
    fun timesOutAfterThreeAttemptsAndIgnoresFurtherNetworkChanges() {
        val session = Session()
        with(session) {
            policy.networkChanged(1L)
            policy.networkChanged(2L)
            advance(1_000L + 3_000L + 8_000L + 3 * VpnRecovery.ATTEMPT_TIMEOUT_MS)
            assertEquals(3, starts)
            assertEquals(R.string.vpn_recovery_exhausted, error)
            policy.networkChanged(3L)
            advance(60_000L)
            assertEquals(3, starts)
            assertFalse(policy.handshakeReceived())
        }
    }

    @Test
    fun rapidHandoffsDoNotResetTheBudgetOnEachHandshake() {
        val session = Session()
        with(session) {
            policy.networkChanged(1L)
            for ((network, delay) in listOf(2L to 1_000L, 3L to 3_000L, 4L to 8_000L)) {
                policy.networkChanged(network)
                advance(delay)
                assertTrue(policy.handshakeReceived())
            }
            policy.networkChanged(5L)
            assertEquals(R.string.vpn_recovery_exhausted, error)
            assertEquals(3, starts)
        }
    }

    @Test
    fun stableConnectionRestoresTheBudgetAndManualStopCancelsWaitingRetry() {
        val session = Session()
        with(session) {
            policy.networkChanged(1L)
            policy.networkChanged(2L)
            advance(1_000L)
            assertTrue(policy.handshakeReceived())
            advance(VpnRecovery.STABLE_NETWORK_MS)
            policy.networkChanged(3L)
            advance(1_000L)
            assertEquals(2, starts)
            policy.retryFailed()
            policy.cancel()
            policy.networkChanged(4L)
            advance(VpnRecovery.RECOVERY_TIMEOUT_MS)
            assertEquals(2, starts)
            assertNull(error)
            assertFalse(policy.handshakeReceived())
        }
    }

    @Test
    fun offlineStartFailureWaitsForInternetWithinAFiniteWindow() {
        val session = Session()
        with(session) {
            policy.networkChanged(null)
            assertEquals(0, releases)
            assertTrue(policy.waitAfterOfflineFailure())
            advance(VpnRecovery.RECOVERY_TIMEOUT_MS)
            assertEquals(R.string.vpn_recovery_timeout, error)
            assertEquals(0, starts)
        }
    }

    private class Session {
        var releases = 0
        var starts = 0
        var state: Int? = null
        var error: Int? = null
        val main = Looper.getMainLooper()
        val policy = VpnRecovery(Handler(main), { state = it }, { releases++ }, { starts++ }, { error = it })

        fun advance(ms: Long) = shadowOf(main).idleFor(Duration.ofMillis(ms))
    }
}
