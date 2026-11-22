package com.kachur.vpn

import android.content.Context

/** The ordinary notification follows the in-process state without keeping a service alive. */
internal class VpnControlNotification(private val context: Context) {
    private val prefs = context.getSharedPreferences("vpn_notifications", Context.MODE_PRIVATE)
    private val notifications = VpnNotifications(context)
    val enabled: Boolean get() = prefs.getBoolean(SHOW_WHEN_OFF, true)

    private data class Notice(val status: VpnNoticeStatus, val error: VpnError?)
    private var previous: Notice? = null
    private var synced = false
    private var closingError: VpnError? = null

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(SHOW_WHEN_OFF, enabled).apply()
        sync(force = true)
    }

    fun sync(force: Boolean = false) {
        val state = VpnConnection.noticeStatus
        if (state == VpnNoticeStatus.STOPPING) closingError = VpnConnection.currentError
        else if (state != VpnNoticeStatus.OFF) closingError = null
        val error = if (state == VpnNoticeStatus.OFF) VpnConnection.currentError ?: closingError else null

        try {
            val notice = when {
                VpnConnection.phase != VpnPhase.IDLE -> null
                enabled && notifications.canShow(VpnNotifications.CONTROL_CHANNEL) -> Notice(state, error)
                error != null && notifications.canShow(VpnNotifications.CHANNEL) -> Notice(VpnNoticeStatus.ERROR, error)
                else -> null
            }
            if (!force && synced && notice == previous) return
            if (notice == null) notifications.clearControl()
            else notifications.updateControl(notice.status, notice.error?.display(context))
            previous = notice
            synced = true
        } catch (_: RuntimeException) {
            // Permission or channel changes can race the request to post.
        }
    }

    private companion object {
        const val SHOW_WHEN_OFF = "show_when_off"
    }
}
