package com.kachur.vpn

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class VpnNotificationsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun ongoingChannelRendersRealStatsChronometerAndAnAttemptScopedStopAction() {
        val notifications = VpnNotifications(context)
        notifications.prepare()
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(VpnNotifications.CHANNEL).importance)
        val connected = notifications.build(
            VpnNotice(VpnNoticeStatus.CONNECTED, "Германия", connectedAt = 1_700_000_000_000L,
                traffic = VpnTraffic(1024L, 4096L), profileName = "Тестовый сервер"),
            attempt = 113L,
        )
        assertEquals("Kachur VPN — Подключён", connected.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(connected.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Тестовый сервер"))
        assertEquals(R.drawable.ic_vpn_status, connected.smallIcon.resId)
        assertTrue(connected.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(connected.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        val bigText = connected.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(bigText.contains("Германия"))
        assertTrue(bigText.contains("Время подключения"))
        assertTrue(bigText.contains("Входящий трафик"))
        assertTrue(bigText.contains("Исходящий"))
        val action = shadowOf(connected.actions.single().actionIntent).savedIntent
        assertEquals(VpnNotifications.ACTION_STOP, action.action)
        assertEquals(113L, action.getLongExtra(VpnConnection.EXTRA_ATTEMPT, 0L))
        assertNotNull(connected.deleteIntent)
    }

    @Test
    fun reconnectAndErrorNeverShowInventedTrafficOrConnectedDuration() {
        val notifications = VpnNotifications(context)
        for (state in listOf(VpnNoticeStatus.CONNECTING, VpnNoticeStatus.RECONNECTING, VpnNoticeStatus.ERROR)) {
            val notice = notifications.build(VpnNotice(state, "Германия", "Безопасная диагностика"), 44L)
            assertTrue(notice.extras.getString(Notification.EXTRA_TITLE).orEmpty().contains(context.getString(state.label)))
            assertFalse(notice.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            assertFalse(notice.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Исходящий"))
            assertEquals(state != VpnNoticeStatus.ERROR, notice.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(state == VpnNoticeStatus.ERROR, notice.actions.isNullOrEmpty())
        }
    }

    @Test
    fun ordinaryControlUsesItsOwnChannelAndAConsentActivityWithoutStartingTheService() {
        val notification = VpnNotifications(context).buildControl(VpnNoticeStatus.OFF)
        assertEquals("Kachur VPN — Выключен", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(VpnNotifications.CONTROL_CHANNEL, notification.channelId)
        assertFalse(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        val action = notification.actions.single()
        assertEquals("Включить VPN", action.title)
        val pending = shadowOf(action.actionIntent)
        assertTrue(pending.isActivity)
        assertEquals(VpnQuickActivity::class.java.name, pending.savedIntent.component?.className)
        assertTrue(pending.savedIntent.extras == null)
        assertTrue(VpnNotifications(context).buildControl(VpnNoticeStatus.CONNECTING).actions.isNullOrEmpty())
    }

    @Test
    fun countryLabelIsLocalPerDocumentAndRejectsAddressesAndOtherFields() {
        val a = VpnProfileLabels.documentKey(Uri.parse("content://example.test/document/a"))
        val b = VpnProfileLabels.documentKey(Uri.parse("content://example.test/document/b"))
        val labels = VpnProfileLabels(context)
        assertEquals("Германия", labels.country(a))
        assertTrue(labels.saveCountry(a, " Франция "))
        assertFalse(labels.saveCountry(a, "192.0.2.1"))
        assertFalse(labels.saveCountry(a, "Primary\nname"))
        assertEquals("Франция", VpnProfileLabels(context).country(a))
        assertEquals("Германия", VpnProfileLabels(context).country(b))
    }
}
