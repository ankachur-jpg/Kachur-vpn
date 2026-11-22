package com.kachur.vpn

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestKachurApplication::class)
class VpnStatisticsPollingTest {
    @Test
    fun backgroundHandshakeReadsAreFastOnlyForTheProbeWindow() {
        val main = Looper.getMainLooper()
        var reads = 0
        val polling = VpnStatisticsPolling(Handler(main), fast = false) { finished ->
            reads++
            finished()
        }
        try {
            polling.start(probingHandshake = true)
            shadowOf(main).idleFor(Duration.ofMillis(249))
            assertEquals(0, reads)
            shadowOf(main).idleFor(Duration.ofMillis(1))
            assertEquals(1, reads)
            shadowOf(main).idleFor(Duration.ofMillis(5_750))
            assertEquals(24, reads)
            shadowOf(main).idleFor(Duration.ofMillis(59_999))
            assertEquals(24, reads)
            shadowOf(main).idleFor(Duration.ofMillis(1))
            assertEquals(25, reads)
        } finally {
            polling.stop()
        }
    }

    @Test
    fun handshakeConfirmationReturnsToBackgroundCadenceImmediately() {
        val main = Looper.getMainLooper()
        var reads = 0
        val polling = VpnStatisticsPolling(Handler(main), fast = false) { finished ->
            reads++
            finished()
        }
        try {
            polling.start(probingHandshake = true)
            polling.handshakeObserved()
            shadowOf(main).idleFor(Duration.ofMillis(250))
            assertEquals(1, reads)
            shadowOf(main).idleFor(Duration.ofSeconds(10))
            assertEquals(1, reads)
        } finally {
            polling.stop()
        }
    }

    @Test
    fun screenOffOrHiddenUiSlowsPollingAndReturningRefreshesImmediately() {
        val application = RuntimeEnvironment.getApplication() as KachurApplication
        val power = shadowOf(application.getSystemService(PowerManager::class.java))
        val main = Looper.getMainLooper()
        val visibility = application.visibility
        var reads = 0
        val polling = VpnStatisticsPolling(Handler(main), fast = false) { finished ->
            reads++
            finished()
        }
        val observer: () -> Unit = { polling.setFast(visibility.interactiveUi) }
        power.setIsInteractive(true)
        visibility.observe(observer)
        try {
            Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
                assertTrue(visibility.interactiveUi)
                polling.start()
                shadowOf(main).idleFor(Duration.ofSeconds(3))
                assertEquals(2, reads)

                power.setIsInteractive(false)
                application.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
                shadowOf(main).idle()
                assertFalse(visibility.interactiveUi)
                shadowOf(main).idleFor(Duration.ofMillis(59_999))
                assertEquals(2, reads)
                shadowOf(main).idleFor(Duration.ofMillis(1))
                assertEquals(3, reads)

                power.setIsInteractive(true)
                application.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
                shadowOf(main).idle()
                assertEquals(4, reads)
                controller.pause().stop()
                shadowOf(main).idleFor(Duration.ofSeconds(59))
                assertFalse(visibility.interactiveUi)
                assertEquals(4, reads)
                shadowOf(main).idleFor(Duration.ofSeconds(1))
                assertEquals(5, reads)
            }
        } finally {
            polling.stop()
            visibility.removeObserver(observer)
        }
        assertTrue(shadowOf(application).registeredReceivers.none { it.intentFilter.hasAction(Intent.ACTION_SCREEN_OFF) })
    }

    @Test
    fun changingCadenceDoesNotOverlapAReadAndStopDiscardsItsCompletion() {
        val main = Looper.getMainLooper()
        val finishes = mutableListOf<() -> Unit>()
        val polling = VpnStatisticsPolling(Handler(main), fast = false) { finished -> finishes.add(finished) }
        polling.start()
        shadowOf(main).idleFor(Duration.ofSeconds(1))
        assertEquals(1, finishes.size)
        polling.setFast(true)
        polling.setFast(false)
        polling.setFast(true)
        shadowOf(main).idleFor(Duration.ofMinutes(2))
        assertEquals(1, finishes.size)

        finishes.single()()
        shadowOf(main).idleFor(Duration.ofSeconds(2))
        assertEquals(2, finishes.size)
        polling.stop()
        finishes.last()()
        shadowOf(main).idleFor(Duration.ofMinutes(2))
        assertEquals(2, finishes.size)
    }
}
