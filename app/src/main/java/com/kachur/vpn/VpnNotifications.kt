package com.kachur.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
import android.text.format.Formatter
import java.util.Date

internal enum class VpnNoticeStatus(val label: Int) {
    OFF(R.string.vpn_control_off),
    CONNECTING(R.string.vpn_control_connecting),
    CONNECTED(R.string.vpn_control_connected),
    RECONNECTING(R.string.vpn_notice_reconnecting),
    STOPPING(R.string.vpn_phase_stopping),
    ERROR(R.string.vpn_notice_error),
}

internal val VpnConnection.noticeStatus: VpnNoticeStatus
    get() = when (phase) {
        VpnPhase.IDLE -> if (preparing) VpnNoticeStatus.CONNECTING else VpnNoticeStatus.OFF
        VpnPhase.STARTING, VpnPhase.WAITING -> VpnNoticeStatus.CONNECTING
        VpnPhase.HANDSHAKE -> VpnNoticeStatus.CONNECTED
        VpnPhase.RECOVERING -> VpnNoticeStatus.RECONNECTING
        VpnPhase.STOPPING -> VpnNoticeStatus.STOPPING
    }

internal data class VpnTraffic(val received: Long, val sent: Long)

internal data class VpnNotice(
    val status: VpnNoticeStatus,
    val country: String,
    val detail: String? = null,
    val connectedAt: Long = 0L,
    val traffic: VpnTraffic? = null,
    val profileName: String? = null,
)

internal class VpnNotifications(private val context: Context) {
    private val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))

    fun prepare() = createChannel(CHANNEL, R.string.vpn_notification_channel)

    fun canShow(channel: String): Boolean = manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE

    fun build(notice: VpnNotice, attempt: Long): Notification {
        val ongoing = notice.status != VpnNoticeStatus.ERROR
        val server = notice.profileName?.let { context.getString(R.string.vpn_notice_summary, it, notice.country) }
            ?: notice.country
        val traffic = notice.traffic?.takeIf { notice.status == VpnNoticeStatus.CONNECTED }?.let {
            context.getString(R.string.vpn_notice_traffic,
                Formatter.formatShortFileSize(context, it.received), Formatter.formatShortFileSize(context, it.sent))
        }
        val connectedAt = notice.connectedAt.takeIf { notice.status == VpnNoticeStatus.CONNECTED } ?: 0L
        val started = connectedAt.takeIf { it > 0L }?.let {
            context.getString(R.string.vpn_notice_connected_at, DateFormat.getTimeFormat(context).format(Date(it)))
        }
        val builder = builder(CHANNEL, notice.status)
            .setContentText(server)
            .setStyle(Notification.BigTextStyle().bigText(listOfNotNull(server, notice.detail, started, traffic).joinToString("\n")))
            .setCategory(if (ongoing) Notification.CATEGORY_SERVICE else Notification.CATEGORY_ERROR)
            .setOngoing(ongoing)
            .setShowWhen(connectedAt > 0L)
        if (connectedAt > 0L) builder.setWhen(connectedAt).setUsesChronometer(true)
        if (ongoing) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }
            if (notice.status != VpnNoticeStatus.STOPPING) {
                builder.setDeleteIntent(dismissAction(attempt))
                val stop = PendingIntent.getBroadcast(
                    context, attempt.toInt(), Intent(context, VpnNotificationReceiver::class.java)
                        .setAction(ACTION_STOP).putExtra(VpnConnection.EXTRA_ATTEMPT, attempt),
                    PendingIntent.FLAG_IMMUTABLE,
                )
                builder.addAction(action(R.string.vpn_disconnect, stop))
            }
        } else {
            builder.setAutoCancel(true).setTimeoutAfter(ERROR_TIMEOUT_MS)
        }
        return builder.build()
    }

    fun update(notice: VpnNotice, attempt: Long) = manager.notify(FOREGROUND_ID, build(notice, attempt))

    fun buildControl(status: VpnNoticeStatus, detail: String? = null): Notification =
        builder(if (status == VpnNoticeStatus.ERROR) CHANNEL else CONTROL_CHANNEL, status)
            .setCategory(if (status == VpnNoticeStatus.ERROR) Notification.CATEGORY_ERROR else Notification.CATEGORY_STATUS)
            .setShowWhen(false)
            .apply {
                if (detail != null) {
                    setContentText(detail)
                    setStyle(Notification.BigTextStyle().bigText(detail))
                }
                if (status == VpnNoticeStatus.OFF) addAction(action(R.string.vpn_control_enable, VpnQuickActivity.pendingIntent(context)))
                if (status == VpnNoticeStatus.ERROR) setAutoCancel(true).setTimeoutAfter(ERROR_TIMEOUT_MS)
            }
            .build()

    fun updateControl(status: VpnNoticeStatus, detail: String?) {
        if (status == VpnNoticeStatus.ERROR) prepare()
        else createChannel(CONTROL_CHANNEL, R.string.vpn_control_channel)
        manager.notify(CONTROL_ID, buildControl(status, detail))
    }

    fun clearControl() = manager.cancel(CONTROL_ID)

    private fun createChannel(id: String, name: Int) = manager.createNotificationChannel(
        NotificationChannel(id, context.getString(name), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
    )

    private fun builder(channel: String, status: VpnNoticeStatus): Notification.Builder {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_vpn_status)
            .setContentTitle(context.getString(R.string.vpn_control_title, context.getString(R.string.app_name),
                context.getString(status.label)))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
    }

    private fun action(label: Int, intent: PendingIntent): Notification.Action =
        Notification.Action.Builder(null, context.getString(label), intent).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAuthenticationRequired(true)
        }.build()

    private fun dismissAction(attempt: Long): PendingIntent = PendingIntent.getBroadcast(
        context, attempt.toInt(), Intent(context, VpnNotificationReceiver::class.java).setAction(ACTION_DISMISS)
            .putExtra(VpnConnection.EXTRA_ATTEMPT, attempt),
        PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CHANNEL = "amneziawg_vpn"
        const val CONTROL_CHANNEL = "vpn_idle_controls"
        const val FOREGROUND_ID = 1
        const val CONTROL_ID = 2
        const val ACTION_STOP = "com.kachur.vpn.STOP_VPN"
        const val ACTION_DISMISS = "com.kachur.vpn.DISMISS_VPN_NOTICE"
        private const val ERROR_TIMEOUT_MS = 60_000L
    }
}

class VpnNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val attempt = intent.getLongExtra(VpnConnection.EXTRA_ATTEMPT, 0L)
        when (intent.action) {
            VpnNotifications.ACTION_STOP -> VpnConnection.stopFromNotification(attempt)
            VpnNotifications.ACTION_DISMISS -> {
                if (VpnConnection.ownsAttempt(attempt) && VpnConnection.phase != VpnPhase.STOPPING) {
                    VpnConnection.refreshNotification()
                }
            }
        }
    }
}
