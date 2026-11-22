package com.kachur.vpn

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class QuickControlsUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))

    @Test
    fun systemTileAndNotificationShareTheSameForegroundSessionAcrossSleepAndNetworkLoss() {
        shell("pm grant com.kachur.vpn android.permission.POST_NOTIFICATIONS")
        shell("appops set com.kachur.vpn ACTIVATE_VPN allow")
        VpnLaunchSettings(context).setEnabled(false)
        seedProfile()
        instrumentation.runOnMainSync { (context.applicationContext as KachurApplication).controlNotification.setEnabled(true) }
        await("ordinary notification before any tunnel") { notice(VpnNotifications.CONTROL_ID) != null }

        val wifiWasOn = shell("settings get global wifi_on") == "1"
        val component = "com.kachur.vpn/.VpnTileService"
        try {
            assertTrue(shell("cmd statusbar add-tile $component").isBlank())
            shell("cmd statusbar expand-settings")
            instrumentation.uiAutomation.waitForIdle(500, 8_000)
            assertTrue(shell("cmd statusbar click-tile $component").isBlank())
            await("AWG service started by the system tile") {
                VpnConnection.phase == VpnPhase.WAITING && VpnConnection.serviceLaunch == R.string.vpn_service_launch_accepted &&
                    notice(VpnNotifications.FOREGROUND_ID) != null
            }
            var active: Long? = null
            instrumentation.runOnMainSync {
                active = VpnConnection.attemptId
                val timing = checkNotNull(VpnStartupTiming.last(VpnStartSource.TILE))
                val stages = timing.snapshot().map { it.stage }
                assertTrue(stages.containsAll(listOf(VpnStartStage.PROFILE_CACHED, VpnStartStage.PERMISSION_CHECKED,
                    VpnStartStage.SERVICE_ACCEPTED, VpnStartStage.FOREGROUND_READY, VpnStartStage.TUNNEL_UP)))
                assertFalse(stages.contains(VpnStartStage.WINDOW_REQUESTED))
                assertFalse(stages.contains(VpnStartStage.WINDOW_RESUMED))
                assertFalse(stages.contains(VpnStartStage.HANDSHAKE_CONFIRMED))
            }
            assertNotNull(active)
            assertStartingNotice(VpnNoticeStatus.CONNECTING)

            val power = checkNotNull(context.getSystemService(PowerManager::class.java))
            shell("input keyevent KEYCODE_SLEEP")
            await("screen turned off") { !power.isInteractive }
            val asleep = checkNotNull(notice(VpnNotifications.FOREGROUND_ID))
            assertTrue(asleep.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
            assertFalse(asleep.extras.getString(Notification.EXTRA_TITLE).orEmpty()
                .contains(context.getString(R.string.vpn_control_connected)))
            wake()

            shell("svc wifi disable")
            shell("cmd connectivity airplane-mode enable")
            await("physical networks unavailable") { !hasValidatedPhysicalNetwork(context) }
            await("VPN still has the original attempt while offline") {
                VpnConnection.attemptId == active && notice(VpnNotifications.FOREGROUND_ID) != null
            }
            assertFalse(notice(VpnNotifications.FOREGROUND_ID)?.extras?.getString(Notification.EXTRA_TITLE)
                .orEmpty().contains(context.getString(R.string.vpn_control_connected)))
            assertTrue(notice(VpnNotifications.CONTROL_ID) == null)

            restoreNetwork(wifiWasOn)
            val action = checkNotNull(notice(VpnNotifications.FOREGROUND_ID)).actions.single()
            assertEquals(context.getString(R.string.vpn_disconnect), action.title.toString())
            action.actionIntent.send()
            await("ordinary control restored after Stop") {
                VpnConnection.phase == VpnPhase.IDLE && notice(VpnNotifications.CONTROL_ID) != null &&
                    notice(VpnNotifications.FOREGROUND_ID) == null
            }
            assertEquals("Kachur VPN — Выключен", notice(VpnNotifications.CONTROL_ID)?.extras?.getString(Notification.EXTRA_TITLE))
            assertTrue(VpnLaunchSettings(context).paused)
        } finally {
            wake()
            restoreNetwork(wifiWasOn)
            instrumentation.runOnMainSync { if (VpnConnection.attemptId != null) VpnConnection.stop() }
            shell("cmd statusbar remove-tile $component")
            shell("cmd statusbar collapse")
        }
    }

    private fun assertStartingNotice(state: VpnNoticeStatus) {
        val foreground = checkNotNull(notice(VpnNotifications.FOREGROUND_ID))
        assertEquals(context.getString(R.string.vpn_control_title, context.getString(R.string.app_name),
            context.getString(state.label)), foreground.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(foreground.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Тестовый сервер CI"))
        assertTrue(foreground.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        assertTrue(notice(VpnNotifications.CONTROL_ID) == null)
    }

    private fun restoreNetwork(wifiWasOn: Boolean) {
        shell("cmd connectivity airplane-mode disable")
        if (wifiWasOn) shell("svc wifi enable")
    }

    private fun wake() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    private fun hasValidatedPhysicalNetwork(context: Context): Boolean {
        val connectivity = checkNotNull(context.getSystemService(ConnectivityManager::class.java))
        return connectivity.allNetworks.any { network ->
            val capabilities = connectivity.getNetworkCapabilities(network)
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }

    private fun seedProfile() {
        fun key(byte: Byte) = Base64.getEncoder().encodeToString(ByteArray(32) { byte })
        // A documentation-only route and a peer without an endpoint keep the native test local.
        val profile = ImportedProfile("Тестовый сервер CI", AwgConfigParser.parse(
            "[Interface]\nPrivateKey=${key(1)}\nAddress=198.51.100.200/32\n" +
                "[Peer]\nPublicKey=${key(2)}\nAllowedIPs=198.51.100.201/32",
        ), "c".repeat(64))
        val profiles = context.localProfiles()
        profiles.append(listOf(profile))
        profiles.select(profile.documentKey)
    }

    private fun notice(id: Int): Notification? = manager.activeNotifications
        .singleOrNull { it.id == id }?.notification

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        do {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(80)
        } while (SystemClock.elapsedRealtime() < deadline)
        var state = VpnPhase.IDLE
        var message = R.string.vpn_disconnected
        instrumentation.runOnMainSync {
            state = VpnConnection.phase
            message = VpnConnection.message
        }
        error("Did not reach $description (phase $state, status ${context.getString(message)})")
    }
}
