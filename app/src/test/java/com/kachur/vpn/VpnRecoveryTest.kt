package com.kachur.vpn

import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun hoursOfflineQueueNoRetriesAndRestoredInternetRequiresANewHandshake() {
        val session = Session()
        with(session) {
            policy.networkChanged(1L)
            policy.networkChanged(1L)
            assertEquals(0, releases)
            policy.networkChanged(null)
            assertEquals(R.string.vpn_recovery_waiting_network, state)
            advance(Duration.ofHours(6).toMillis())
            assertEquals(0, starts)
            assertFalse(policy.handshakeReceived())
            policy.networkChanged(2L)
            policy.networkChanged(2L)
            advance(1_000L)
            assertEquals(1, starts)
            assertEquals(1, releases)
            assertTrue(policy.recovering)
            assertTrue(policy.handshakeReceived())
            advance(VpnRecovery.ATTEMPT_TIMEOUT_MS)
            assertEquals(1, starts)
            assertFalse(policy.recovering)
        }
    }

    @Test
    fun keepsRetryingForHoursWithExponentialBackoffCappedAtFiveMinutes() {
        val session = Session()
        with(session) {
            policy.networkChanged(1L)
            policy.networkChanged(2L)
            val delays = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 64_000L, 128_000L, 256_000L) +
                List(36) { 300_000L }
            delays.forEachIndexed { index, delay ->
                advance(delay - 1)
                assertEquals(index, starts)
                advance(1)
                assertEquals(index + 1, starts)
                advance(VpnRecovery.ATTEMPT_TIMEOUT_MS)
                assertEquals(R.string.vpn_recovery_scheduled, state)
            }
            assertTrue(policy.recovering)
        }
    }

    @Test
    fun successfulHandshakeResetsTheDelayImmediately() {
        val session = Session()
        with(session) {
            policy.networkChanged(1L)
            policy.networkChanged(2L)
            advance(1_000L)
            assertTrue(policy.recoverAfterFailure())
            advance(1_999L)
            assertEquals(1, starts)
            advance(1)
            assertEquals(2, starts)
            assertTrue(policy.handshakeReceived())

            policy.networkChanged(3L)
            advance(1_000L)
            assertEquals(3, starts)
        }
    }

    @Test
    fun lossOfInternetCancelsScheduledRetryAndPreservesBackoffWhenNetworkReturns() {
        val session = Session()
        with(session) {
            policy.networkChanged(1L)
            policy.networkChanged(2L)
            advance(1_000L)
            assertTrue(policy.recoverAfterFailure())
            advance(1_000L)
            policy.networkChanged(null)
            assertEquals(R.string.vpn_recovery_waiting_network, state)
            advance(Duration.ofHours(4).toMillis())
            assertEquals(1, starts)

            policy.networkChanged(3L)
            advance(1_999L)
            assertEquals(1, starts)
            advance(1)
            assertEquals(2, starts)
        }
    }

    @Test
    fun cancellationWhileAnAttemptIsInFlightRemovesTimeoutAndFutureRetries() {
        val session = Session()
        with(session) {
            policy.networkChanged(1L)
            policy.networkChanged(2L)
            advance(1_000L)
            policy.cancel()
            policy.networkChanged(null)
            policy.networkChanged(3L)
            assertFalse(policy.recoverAfterFailure())
            assertFalse(policy.handshakeReceived())
            advance(Duration.ofHours(4).toMillis())
            assertEquals(1, starts)
            assertFalse(policy.recovering)
        }
    }

    @Test
    fun losingTheNetworkDuringAnAttemptInvalidatesTheOldHandshakeAndWaits() {
        with(Session()) {
            policy.networkChanged(1L)
            policy.networkChanged(2L)
            advance(1_000L)
            assertEquals(1, starts)
            policy.networkChanged(null)
            assertEquals(2, releases)
            assertFalse(policy.handshakeReceived())
            advance(VpnRecovery.ATTEMPT_TIMEOUT_MS + VpnRecovery.MAX_DELAY_MS)
            assertEquals(1, starts)

            policy.networkChanged(3L)
            advance(1_000L)
            assertEquals(2, starts)
        }
    }

    @Test
    fun disabledRecoveryIgnoresNetworkChangesAndCanCancelAPendingRetry() {
        with(Session()) {
            assertFalse(policy.setEnabled(false))
            policy.networkChanged(1L)
            policy.networkChanged(null)
            policy.networkChanged(2L)
            assertFalse(policy.recoverAfterFailure())
            assertEquals(0, releases)
            advance(60_000L)
            assertEquals(0, starts)

            assertFalse(policy.setEnabled(true))
            policy.networkChanged(3L)
            assertTrue(policy.setEnabled(false))
            advance(60_000L)
            assertEquals(0, starts)
            assertFalse(policy.recovering)
        }
    }

    @Test
    fun startupFailureBeforeConnectivityCallbackWaitsForInternetWithoutExtraTeardown() {
        with(Session()) {
            assertTrue(policy.recoverAfterFailure())
            policy.networkChanged(null)
            advance(Duration.ofHours(4).toMillis())
            assertEquals(0, starts)
            assertEquals(1, releases)
            policy.networkChanged(1L)
            advance(1_000L)
            assertEquals(1, starts)
            assertEquals(1, releases)
        }
    }

    @Test
    fun jitterKeepsEachDelayBetweenNinetyPercentAndItsCeiling() {
        with(Session(jitter = { it })) {
            policy.networkChanged(1L)
            policy.networkChanged(2L)
            advance(899L)
            assertEquals(0, starts)
            advance(1)
            assertEquals(1, starts)
        }
    }

    private class Session(jitter: (Long) -> Long = { 0L }) {
        var releases = 0
        var starts = 0
        var state: Int? = null
        val main = Looper.getMainLooper()
        val policy = VpnRecovery(Handler(main), { state = it }, { releases++ }, { starts++ }, jitter)

        fun advance(ms: Long) = shadowOf(main).idleFor(Duration.ofMillis(ms))
    }
}
