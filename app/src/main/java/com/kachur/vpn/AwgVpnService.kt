package com.kachur.vpn

import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import org.amnezia.awg.backend.BackendException
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.BadConfigException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor

class AwgVpnService : VpnService() {
    private val main = Handler(Looper.getMainLooper())
    private val tunnel = object : Tunnel {
        override fun getName() = "KachurVPN"
        override fun onStateChange(state: Tunnel.State) = Unit
    }
    // Native engine state is owned by the serial backend worker.
    private var backend: GoBackend? = null
    private var cleanupFailed = false
    private var reportedStatsError = false

    private var setup: Future<*>? = null
    private var polling: VpnStatisticsPolling? = null
    private var attempt = 0L
    private var options: VpnStartOptions? = null
    private var country = ""
    private var cleanupResult: Boolean? = null
    @Volatile private var generation = 0L
    @Volatile private var closing = false
    @Volatile private var destroyed = false

    private val notifications by lazy { VpnNotifications(this) }
    private val visibility by lazy { (application as KachurApplication).visibility }
    private val visibilityObserver: () -> Unit = ::updateStatisticsCadence
    private val connectionObserver: () -> Unit = {
        updateStatisticsCadence()
        renderNotification()
    }
    private var foreground = false
    @Volatile private var notificationDismissed = false
    private var postedError = false
    private var lastNotice: VpnNotice? = null
    private var lastNoticeRevision = VpnConnection.notificationRevision
    private var connectedAt = 0L
    private var traffic: VpnTraffic? = null

    private var monitor: UnderlyingNetworkMonitor? = null
    private val recovery = VpnRecovery(
        main,
        state = { VpnConnection.recovering(attempt, it) },
        releaseTunnel = ::releaseForRecovery,
        startTunnel = { startTunnel(reconnecting = true) },
    )
    private val launchSettings by lazy { VpnLaunchSettings(this) }
    private val preferences by lazy { SplitTunnelingSettings(this).preferences }
    private val recoveryObserver = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == VpnLaunchSettings.RECOVERY_ENABLED && !closing) applyRecoverySetting()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(VpnConnection.EXTRA_ATTEMPT, 0L) ?: 0L
        val action = intent?.action
        if (action == VpnNotifications.ACTION_STOP || action == VpnNotifications.ACTION_DISMISS) {
            if (id != 0L && id == attempt && VpnConnection.ownsAttempt(id)) {
                if (action == VpnNotifications.ACTION_STOP) VpnConnection.stopFromNotification(id) else notificationDismissed = true
            } else if (attempt == 0L) {
                serviceCalls.execute { stopPlatformService(0L, startId) }
            }
            return START_NOT_STICKY
        }
        if (attempt != 0L) {
            if (id != attempt) VpnConnection.serviceFailed(id, R.string.vpn_service_busy)
            return START_NOT_STICKY
        }
        val claimed = VpnConnection.claim(id, ::requestStop)
        if (claimed == null) {
            // Complete the platform foreground request even if reset invalidated the attempt.
            serviceCalls.execute {
                if (destroyed) return@execute
                try {
                    if (id != 0L) {
                        notifications.prepare()
                        val notice = VpnNotice(VpnNoticeStatus.CONNECTING, getString(R.string.vpn_country_default))
                        startForeground(VpnNotifications.FOREGROUND_ID, notifications.build(notice, id))
                    }
                } catch (_: RuntimeException) {
                    // No current attempt owns this command.
                } finally {
                    stopPlatformService(0L, startId)
                }
            }
            return START_NOT_STICKY
        }

        attempt = id
        VpnConnection.attachDiagnosticService(id, this)
        options = claimed
        country = claimed.country
        preferences.registerOnSharedPreferenceChangeListener(recoveryObserver)
        applyRecoverySetting()
        VpnConnection.observe(connectionObserver)
        val initialNotice = VpnNotice(VpnNoticeStatus.CONNECTING, country)
        lastNotice = initialNotice
        serviceCalls.execute {
            if (destroyed) return@execute
            try {
                notifications.prepare()
                startForeground(VpnNotifications.FOREGROUND_ID, notifications.build(initialNotice, id))
            } catch (_: RuntimeException) {
                main.post { if (!closing) VpnConnection.serviceFailed(id, R.string.vpn_foreground_error) }
                return@execute
            }
            main.post {
                if (!closing) {
                    foreground = true
                    visibility.observe(visibilityObserver)
                    VpnConnection.foregroundReady(id)
                    watchNetworks()
                    startTunnel(reconnecting = false)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun watchNetworks() {
        try {
            monitor = UnderlyingNetworkMonitor(this, main, recovery::networkChanged).also { it.start() }
        } catch (_: RuntimeException) {
            VpnConnection.record(R.string.vpn_network_monitor_error, VpnError(R.string.vpn_network_monitor_error))
        }
    }

    private fun applyRecoverySetting() {
        if (recovery.setEnabled(launchSettings.recoveryEnabled)) VpnConnection.stop()
    }

    private fun startTunnel(reconnecting: Boolean) {
        val session = options ?: return
        val id = attempt
        val token = ++generation
        setup = backendWorker.submit { establish(id, token, session, reconnecting) }
    }

    private fun establish(id: Long, token: Long, options: VpnStartOptions, reconnecting: Boolean) {
        if (!current(token)) return
        if (cleanupFailed) {
            fail(id, token, R.string.vpn_stop_error)
            return
        }
        reportedStatsError = false
        try {
            report(id, token, R.string.vpn_checking_profile)
            val config = options.profile.toBackendConfig(options.exclusions)
            val routePlan = options.routePlan ?: config.ipRoutePlan(options.routing.ipRoutes)
            if (!current(token)) return
            report(id, token, R.string.vpn_loading_engine)
            val engine = if (routePlan == null) GoBackend(applicationContext, this)
                else GoBackend(applicationContext, this, routePlan::configure)
            backend = engine
            if (!current(token)) return
            report(id, token, R.string.vpn_starting)
            check(engine.setState(tunnel, Tunnel.State.UP, config) == Tunnel.State.UP)
            if (!current(token)) return
            // The normal empty AWG packet starts negotiation, even with PersistentKeepalive disabled.
            val provingRecovery = reconnecting && engine.requestHandshake(tunnel)
            main.post {
                if (current(token)) {
                    // A profile without a peer endpoint waits for the peer to initiate.
                    if (reconnecting && !provingRecovery) recovery.finishRecovery()
                    VpnConnection.tunnelReady(id, provingRecovery)
                    startStatistics(id, token, engine)
                }
            }
        } catch (_: TooManyVpnRoutesException) {
            fail(id, token, R.string.ip_vpn_route_limit)
        } catch (_: NoIncludedVpnAppsException) {
            fail(id, token, R.string.split_no_included_apps)
        } catch (error: BadConfigException) {
            fail(id, token, R.string.vpn_backend_invalid_profile, error.toSafeVpnError())
        } catch (error: BackendException) {
            val reason = error.reason
            val message = when (reason) {
                BackendException.Reason.DNS_RESOLUTION_FAILURE -> R.string.vpn_dns_error
                BackendException.Reason.VPN_NOT_AUTHORIZED -> R.string.vpn_permission_denied
                BackendException.Reason.TUN_CREATION_ERROR -> R.string.vpn_tun_error
                BackendException.Reason.GO_ACTIVATION_ERROR_CODE -> R.string.vpn_engine_activation_error
                else -> R.string.vpn_start_error
            }
            fail(id, token, message, retryable = reason == BackendException.Reason.DNS_RESOLUTION_FAILURE ||
                reason == BackendException.Reason.TUN_CREATION_ERROR || reason == BackendException.Reason.GO_ACTIVATION_ERROR_CODE)
        } catch (_: Exception) {
            fail(id, token, R.string.vpn_start_error)
        } catch (_: LinkageError) {
            fail(id, token, R.string.vpn_native_error)
        }
    }

    private fun current(token: Long): Boolean = !closing && token == generation

    private fun report(id: Long, token: Long, message: Int) {
        main.post { if (current(token)) VpnConnection.progress(id, message) }
    }

    private fun startStatistics(id: Long, token: Long, engine: GoBackend) {
        if (!current(token)) return
        polling = VpnStatisticsPolling(main, needsFastStatistics()) { completed ->
            backendWorker.execute {
                try {
                    pollTunnel(id, token, engine)
                } finally {
                    main.post { completed() }
                }
            }
        }.also { it.start() }
    }

    private fun needsFastStatistics(): Boolean =
        VpnConnection.phase == VpnPhase.RECOVERING || visibility.interactiveUi

    private fun updateStatisticsCadence() {
        polling?.setFast(needsFastStatistics())
    }

    private fun pollTunnel(id: Long, token: Long, engine: GoBackend) {
        if (!current(token)) return
        try {
            if (engine.getState(tunnel) != Tunnel.State.UP) {
                fail(id, token, R.string.vpn_tunnel_lost, retryable = true)
                return
            }
            val stats = engine.getStatistics(tunnel)
            val peers = stats.peers()
            if (peers.isEmpty()) {
                if (!reportedStatsError) main.post {
                    if (current(token)) VpnConnection.handshakeReadFailed(id)
                }
                reportedStatsError = true
                return
            }
            reportedStatsError = false
            val handshake = peers.maxOf { stats.peer(it)?.latestHandshakeEpochMillis() ?: 0L }
            val rx = stats.totalRx()
            val tx = stats.totalTx()
            main.post {
                if (!current(token)) return@post
                traffic = if (rx >= 0L && tx >= 0L) VpnTraffic(rx, tx) else null
                VpnTrafficUi.publish(id, traffic)
                if (connectedAt == 0L && handshake > 0L && recovery.handshakeReceived()) {
                    connectedAt = handshake.coerceAtMost(System.currentTimeMillis())
                    VpnConnection.handshake(id)
                } else {
                    renderNotification()
                }
            }
        } catch (_: RuntimeException) {
            fail(id, token, R.string.vpn_handshake_read_error)
        } catch (_: LinkageError) {
            fail(id, token, R.string.vpn_native_error)
        }
    }

    private fun fail(id: Long, token: Long, message: Int, error: VpnError = VpnError(message), retryable: Boolean = false) {
        main.post {
            if (!current(token)) return@post
            if (retryable && monitor != null && recovery.recoverAfterFailure()) {
                VpnConnection.record(message, error)
            } else {
                VpnConnection.serviceFailed(id, message, error)
            }
        }
    }

    private fun renderNotification() {
        val id = attempt
        if (closing || !VpnConnection.ownsAttempt(id)) return
        val notice = when (VpnConnection.phase) {
            VpnPhase.STARTING, VpnPhase.WAITING ->
                VpnNotice(VpnNoticeStatus.CONNECTING, country, getString(VpnConnection.message))
            VpnPhase.HANDSHAKE ->
                VpnNotice(VpnNoticeStatus.CONNECTED, country, connectedAt = connectedAt, traffic = traffic)
            VpnPhase.RECOVERING ->
                VpnNotice(VpnNoticeStatus.RECONNECTING, country, getString(VpnConnection.message))
            VpnPhase.STOPPING -> {
                val error = VpnConnection.currentError ?: return
                if (postedError) return
                postedError = true
                queueNotice(VpnNotice(VpnNoticeStatus.ERROR, country, error.display(this)), id)
                return
            }
            VpnPhase.IDLE -> return
        }
        val revision = VpnConnection.notificationRevision
        if (!foreground || notificationDismissed || (lastNotice == notice && lastNoticeRevision == revision)) return
        lastNotice = notice
        lastNoticeRevision = revision
        queueNotice(notice, id)
    }

    private fun queueNotice(notice: VpnNotice, id: Long) {
        serviceCalls.execute {
            if (notice.status != VpnNoticeStatus.ERROR && (closing || notificationDismissed)) return@execute
            try {
                notifications.update(notice, id)
            } catch (_: RuntimeException) {
                // Notification settings and OEM restrictions do not stop a working tunnel.
            }
        }
    }

    override fun onRevoke() {
        main.post {
            if (attempt != 0L) VpnConnection.serviceFailed(attempt, R.string.vpn_permission_revoked)
        }
    }

    private fun requestStop() {
        closeBackend()
        val id = attempt
        serviceCalls.execute { stopPlatformService(id) }
    }

    private fun stopPlatformService(id: Long, startId: Int? = null) {
        try {
            if (startId == null) stopSelf() else stopSelf(startId)
        } catch (_: RuntimeException) {
            main.post { VpnConnection.serviceStopRejected(id) }
        }
    }

    override fun onDestroy() {
        destroyed = true
        closeBackend()
        reportClosed()
        super.onDestroy()
    }

    private fun releaseForRecovery() {
        connectedAt = 0L
        traffic = null
        VpnTrafficUi.clear(attempt)
        invalidateTunnel()
        // Leave ordinary Android routing in place while waiting for a usable network.
        backendWorker.execute {
            releaseEngine()
            if (cleanupFailed) main.post {
                if (!closing) VpnConnection.serviceFailed(attempt, R.string.vpn_stop_error)
            }
        }
    }

    private fun invalidateTunnel() {
        generation++
        polling?.stop()
        polling = null
        setup?.cancel(true)
        setup = null
    }

    private fun closeBackend() {
        if (closing) return
        closing = true
        options = null
        VpnTrafficUi.clear(attempt)
        recovery.cancel()
        if (attempt != 0L) preferences.unregisterOnSharedPreferenceChangeListener(recoveryObserver)
        monitor?.close()
        monitor = null
        if (foreground) visibility.removeObserver(visibilityObserver)
        VpnConnection.removeObserver(connectionObserver)
        invalidateTunnel()
        backendWorker.execute {
            releaseEngine()
            val failed = cleanupFailed
            main.post {
                cleanupResult = failed
                reportClosed()
            }
        }
    }

    private fun releaseEngine() {
        try {
            backend?.setState(tunnel, Tunnel.State.DOWN, null)
        } catch (_: Exception) {
            cleanupFailed = true
        } catch (_: LinkageError) {
            cleanupFailed = true
        } finally {
            backend = null
        }
    }

    private fun reportClosed() {
        val failed = cleanupResult ?: return
        if (!destroyed || attempt == 0L) return
        val id = attempt
        attempt = 0L
        VpnConnection.serviceClosed(id, failed)
    }

    private companion object {
        val serviceCalls = Executors.newSingleThreadExecutor()
        val backendWorker = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    }
}
