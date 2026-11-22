package com.kachur.vpn

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.widget.Button
import android.widget.TextView
import com.kachur.vpn.ConfigImportActivityTest.VpnConsentShadow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestKachurApplication::class, shadows = [VpnConsentShadow::class])
class VpnQuickControlsTest {
    private val app get() = RuntimeEnvironment.getApplication() as KachurApplication
    private val main get() = shadowOf(Looper.getMainLooper())
    private val notifications get() = app.getSystemService(NotificationManager::class.java)!!

    @Before
    fun setUp() {
        VpnConsentShadow.granted = false
        VpnLaunchSettings(app).setPaused(true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        VpnConnection.publish(VpnPhase.IDLE, R.string.vpn_disconnected)
    }

    @After
    fun finishAttempt() {
        VpnConnection.attemptId?.let { id ->
            VpnConnection.stop()
            if (VpnConnection.ownsAttempt(id)) VpnConnection.serviceClosed(id, false)
        }
        VpnConnection.reset()
        VpnConsentShadow.granted = false
    }

    @Test
    fun notificationStartsOnlyTheSelectedProfileAndManualStopCancelsRecovery() {
        val selected = saveSelectedProfile()
        val routing = SplitTunnelingSettings(app)
        routing.setExcluded("example.excluded.app", true)
        routing.addIpRoutes(setOf("192.0.2.0/24"))
        VpnConsentShadow.granted = true

        val action = VpnNotifications(app).buildControl(VpnNoticeStatus.OFF).actions.single().actionIntent
        val intent = shadowOf(action).savedIntent
        Robolectric.buildActivity(VpnQuickActivity::class.java, intent).setup().use { first ->
            await {
                VpnConnection.phase == VpnPhase.STARTING &&
                    VpnConnection.serviceLaunch == R.string.vpn_service_launch_accepted
            }
            val launched = shadowOf(first.get()).nextStartedService
            assertEquals(AwgVpnService::class.java.name, launched.component?.className)
            val id = launched.getLongExtra(VpnConnection.EXTRA_ATTEMPT, 0L)
            assertEquals(id, VpnConnection.attemptId)
            assertEquals(Tile.STATE_UNAVAILABLE, tileState())
            assertFalse(VpnLaunchSettings(app).paused)
            assertTrue(notifications.activeNotifications.none { it.id == VpnNotifications.CONTROL_ID })

            Robolectric.buildActivity(VpnQuickActivity::class.java, intent).setup().use { duplicate ->
                assertTrue(duplicate.get().isFinishing)
                assertEquals(id, VpnConnection.attemptId)
                assertNull(shadowOf(duplicate.get()).nextStartedService)
            }

            var stops = 0
            var retries = 0
            val recovery = VpnRecovery(Handler(Looper.getMainLooper()),
                state = { VpnConnection.recovering(id, it) }, releaseTunnel = {},
                startTunnel = { retries++ }, jitter = { 0L })
            val options = VpnConnection.claim(id) {
                stops++
                recovery.cancel()
            }
            assertEquals(selected.documentKey, options?.profileId)
            assertEquals(selected.name, options?.profileName)
            assertEquals(routing.snapshot(), options?.routing)
            assertNull(VpnConnection.claim(id) {})
            VpnConnection.tunnelReady(id)
            assertEquals(VpnNoticeStatus.CONNECTING, VpnConnection.noticeStatus)
            assertEquals(Tile.STATE_INACTIVE, tileState())

            recovery.networkChanged(1L)
            VpnConnection.handshake(id)
            assertEquals(Tile.STATE_ACTIVE, tileState())
            recovery.networkChanged(2L)
            assertEquals(VpnNoticeStatus.RECONNECTING, VpnConnection.noticeStatus)
            assertEquals(Tile.STATE_INACTIVE, tileState())

            val foreground = VpnNotifications(app).build(VpnNotice(VpnConnection.noticeStatus, "Германия"), id)
            foreground.actions.single().actionIntent.send()
            main.idle()
            assertEquals(1, stops)
            assertTrue(VpnLaunchSettings(app).paused)
            assertEquals(Tile.STATE_UNAVAILABLE, tileState())
            VpnConnection.serviceClosed(id, false)
            main.idleFor(Duration.ofMinutes(10))
            assertEquals(0, retries)
            assertEquals(Tile.STATE_INACTIVE, tileState())
            assertNull(shadowOf(first.get()).nextStartedService)
            assertEquals("Kachur VPN — Выключен", controlTitle())
        }
    }

    @Test
    fun pendingConsentBlocksBothStartsAndDenialReturnsToOff() {
        saveSelectedProfile()
        Robolectric.buildActivity(VpnQuickActivity::class.java, VpnQuickActivity.intent(app)).setup().use { quick ->
            val activity = quick.get()
            await { VpnConnection.awaitingConsent }
            assertEquals(VpnNoticeStatus.CONNECTING, VpnConnection.noticeStatus)
            assertEquals("Kachur VPN — Подключение…", controlTitle())
            val prompt = shadowOf(activity).nextStartedActivityForResult
            assertNotNull(prompt)

            Robolectric.buildActivity(MainActivity::class.java).setup().use { mainScreen ->
                val button = mainScreen.get().findViewById<Button>(R.id.connect_vpn_button)
                assertFalse(button.isEnabled)
                button.performClick()
                assertNull(shadowOf(mainScreen.get()).nextStartedActivityForResult)
                assertNull(VpnConnection.attemptId)
            }
            assertTrue(activity.activityResultRegistry.dispatchResult(prompt.requestCode, Activity.RESULT_CANCELED, null))
            await { !VpnConnection.preparing }
            assertEquals(app.getString(R.string.vpn_permission_denied),
                activity.findViewById<TextView>(R.id.vpn_quick_status).text.toString())
            assertEquals(Tile.STATE_INACTIVE, tileState())
            assertNull(shadowOf(activity).nextStartedService)
            assertEquals("Kachur VPN — Выключен", controlTitle())
        }
    }

    @Test
    fun restoredQuickWindowCannotReplayAnOldUserAction() {
        saveSelectedProfile()
        VpnConsentShadow.granted = true
        val saved = Bundle().apply {
            putString("quick_control_process", "previous-process")
            putBoolean("quick_control_requested", true)
        }
        Robolectric.buildActivity(VpnQuickActivity::class.java, VpnQuickActivity.intent(app))
            .create(saved).start().resume().postResume().use { controller ->
                main.idle()
                assertEquals(app.getString(R.string.vpn_quick_interrupted),
                    controller.get().findViewById<TextView>(R.id.vpn_quick_status).text.toString())
                assertEquals(VpnNoticeStatus.OFF, VpnConnection.noticeStatus)
                assertNull(shadowOf(controller.get()).nextStartedService)
            }
    }

    @Test
    fun settingAndNotificationPermissionControlOnlyTheOrdinaryNotification() {
        val control = app.controlNotification
        control.setEnabled(true)
        assertEquals("Kachur VPN — Выключен", controlTitle())
        control.setEnabled(false)
        assertNull(controlTitle())
        assertFalse(VpnControlNotification(app).enabled)
        control.setEnabled(true)
        shadowOf(notifications).setNotificationsEnabled(false)
        control.sync(force = true)
        assertNull(controlTitle())
        assertNull(VpnConnection.attemptId)
    }

    private fun controlTitle(): String? = notifications.activeNotifications
        .singleOrNull { it.id == VpnNotifications.CONTROL_ID }
        ?.notification?.extras?.getString(Notification.EXTRA_TITLE)

    private fun tileState() = VpnConnection.noticeStatus.tileState(VpnConnection.phase)

    private fun saveSelectedProfile(): ImportedProfile {
        fun key(byte: Byte) = Base64.getEncoder().encodeToString(ByteArray(32) { byte })
        val config = AwgConfigParser.parse("[Interface]\nPrivateKey=${key(1)}\n[Peer]\nPublicKey=${key(2)}")
        val primary = ImportedProfile("Тестовый основной", config, "a".repeat(64))
        val selected = ImportedProfile("Тестовый выбранный", config, "b".repeat(64))
        app.localProfiles().append(listOf(primary, selected))
        app.localProfiles().select(selected.documentKey)
        return selected
    }

    private fun await(ready: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            main.idle()
            if (ready()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        assertTrue("Expected VPN state did not arrive", ready())
    }
}
