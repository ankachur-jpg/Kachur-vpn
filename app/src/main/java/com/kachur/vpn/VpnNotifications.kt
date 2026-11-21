package com.kachur.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
import android.text.format.Formatter
import java.util.Date

internal enum class VpnNoticeStatus(val label: Int) {
    CONNECTING(R.string.vpn_notice_connecting),
    CONNECTED(R.string.vpn_notice_connected),
    RECONNECTING(R.string.vpn_notice_reconnecting),
    ERROR(R.string.vpn_notice_error),
}

internal data class VpnTraffic(val received: Long, val sent: Long)

internal data class VpnNotice(
    val status: VpnNoticeStatus,
    val country: String,
    val detail: String? = null,
    val connectedAt: Long = 0L,
    val traffic: VpnTraffic? = null,
)

internal class VpnNotifications(private val context: Context) {
    private val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))

    fun prepare() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.vpn_notification_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
        manager.cancel(ERROR_ID)
    }

    fun build(notice: VpnNotice, attempt: Long): Notification {
        val ongoing = notice.status != VpnNoticeStatus.ERROR
        val summary = context.getString(R.string.vpn_notice_summary, context.getString(notice.status.label), notice.country)
        val traffic = notice.traffic?.let {
            context.getString(R.string.vpn_notice_traffic,
                Formatter.formatShortFileSize(context, it.received), Formatter.formatShortFileSize(context, it.sent))
        }
        val started = notice.connectedAt.takeIf { it > 0L }?.let {
            context.getString(R.string.vpn_notice_connected_at, DateFormat.getTimeFormat(context).format(Date(it)))
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_vpn_status)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(summary)
            .setStyle(Notification.BigTextStyle().bigText(listOfNotNull(summary, notice.detail, started, traffic).joinToString("\n")))
            .setContentIntent(open)
            .setCategory(if (ongoing) Notification.CATEGORY_SERVICE else Notification.CATEGORY_ERROR)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(ongoing)
            .setShowWhen(notice.connectedAt > 0L)
        if (notice.connectedAt > 0L) builder.setWhen(notice.connectedAt).setUsesChronometer(true)
        if (ongoing) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }
            builder.setDeleteIntent(serviceAction(ACTION_DISMISS, attempt))
            builder.addAction(Notification.Action.Builder(null, context.getString(R.string.vpn_disconnect),
                serviceAction(ACTION_STOP, attempt)).build())
        } else {
            builder.setAutoCancel(true).setTimeoutAfter(60_000L)
        }
        return builder.build()
    }

    fun update(notice: VpnNotice, attempt: Long) {
        val id = if (notice.status == VpnNoticeStatus.ERROR) ERROR_ID else FOREGROUND_ID
        manager.notify(id, build(notice, attempt))
    }

    private fun serviceAction(action: String, attempt: Long): PendingIntent = PendingIntent.getService(
        context, attempt.toInt(), Intent(context, AwgVpnService::class.java).setAction(action)
            .putExtra(VpnConnection.EXTRA_ATTEMPT, attempt),
        PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CHANNEL = "amneziawg_vpn"
        const val FOREGROUND_ID = 1
        const val ACTION_STOP = "com.kachur.vpn.STOP_VPN"
        const val ACTION_DISMISS = "com.kachur.vpn.DISMISS_VPN_NOTICE"
        private const val ERROR_ID = 2
    }
}
