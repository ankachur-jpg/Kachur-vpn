package com.kachur.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
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
import java.util.concurrent.TimeUnit

class AwgVpnService : VpnService() {
    private val main = Handler(Looper.getMainLooper())
    private val tunnel = object : Tunnel {
        override fun getName() = "KachurVPN"
        override fun onStateChange(state: Tunnel.State) = Unit
    }
    // Only the backend worker accesses these three fields.
    private var backend: GoBackend? = null
    private var polling: Future<*>? = null
    private var reportedHandshakeError = false

    private var setup: Future<*>? = null
    private var attempt = 0L
    private var cleanupResult: Boolean? = null
    @Volatile private var closing = false
    @Volatile private var destroyed = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP_ACTION) {
            if (attempt != 0L) VpnConnection.stop()
            return START_NOT_STICKY
        }
        val id = intent?.getLongExtra(VpnConnection.EXTRA_ATTEMPT, 0L) ?: 0L
        if (attempt != 0L) {
            if (id != attempt) VpnConnection.serviceFailed(id, R.string.vpn_service_busy)
            return START_NOT_STICKY
        }
        val options = VpnConnection.claim(id, ::requestStop)
        if (options == null) {
            // A queued foreground request can arrive after reset. It owns no profile.
            serviceCalls.execute {
                if (destroyed) return@execute
                try {
                    if (id != 0L) startForeground(NOTIFICATION_ID, notification())
                } catch (_: RuntimeException) {
                    // No current attempt owns this command.
                } finally {
                    stopPlatformService(id = 0L, startId = startId)
                }
            }
            return START_NOT_STICKY
        }
        attempt = id
        serviceCalls.execute {
            if (destroyed) return@execute
            try {
                startForeground(NOTIFICATION_ID, notification())
            } catch (_: RuntimeException) {
                fail(id, R.string.vpn_foreground_error)
                return@execute
            }

            main.post {
                if (!closing) {
                    VpnConnection.foregroundReady(id)
                    setup = backendWorker.submit { establish(id, options) }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun establish(id: Long, options: VpnStartOptions) {
        if (closing) return
        try {
            report(id, R.string.vpn_checking_profile)
            val config = options.profile.toBackendConfig(options.exclusions)
            val routePlan = options.routePlan ?: config.ipRoutePlan(options.routing.ipRoutes)
            if (closing) return
            report(id, R.string.vpn_loading_engine)
            val engine = if (routePlan == null) GoBackend(applicationContext, this)
                else GoBackend(applicationContext, this, routePlan::configure)
            backend = engine
            if (closing) return
            report(id, R.string.vpn_starting)
            check(engine.setState(tunnel, Tunnel.State.UP, config) == Tunnel.State.UP)
            main.post {
                if (!closing) VpnConnection.tunnelReady(id)
            }
            if (!closing) {
                polling = backendWorker.scheduleWithFixedDelay({ pollHandshake(id, engine) }, 1, 2, TimeUnit.SECONDS)
            }
        } catch (_: TooManyVpnRoutesException) {
            fail(id, R.string.ip_vpn_route_limit)
        } catch (_: NoIncludedVpnAppsException) {
            fail(id, R.string.split_no_included_apps)
        } catch (error: BadConfigException) {
            fail(id, R.string.vpn_backend_invalid_profile, error.toSafeVpnError())
        } catch (error: BackendException) {
            fail(id, when (error.reason) {
                BackendException.Reason.DNS_RESOLUTION_FAILURE -> R.string.vpn_dns_error
                BackendException.Reason.VPN_NOT_AUTHORIZED -> R.string.vpn_permission_denied
                BackendException.Reason.TUN_CREATION_ERROR -> R.string.vpn_tun_error
                BackendException.Reason.GO_ACTIVATION_ERROR_CODE -> R.string.vpn_engine_activation_error
                else -> R.string.vpn_start_error
            })
        } catch (_: Exception) {
            fail(id, R.string.vpn_start_error)
        } catch (_: LinkageError) {
            fail(id, R.string.vpn_native_error)
        }
    }

    private fun report(id: Long, message: Int) {
        main.post { if (!closing) VpnConnection.progress(id, message) }
    }

    private fun pollHandshake(id: Long, engine: GoBackend) {
        if (closing) return
        try {
            val timestamp = engine.getLastHandshake(tunnel)
            when {
                timestamp > 0L -> main.post {
                    if (!closing) VpnConnection.handshake(id)
                }
                timestamp == -3L -> fail(id, R.string.vpn_tunnel_lost)
                timestamp == -2L && !reportedHandshakeError -> main.post {
                    if (!closing) VpnConnection.handshakeReadFailed(id)
                }
            }
            reportedHandshakeError = timestamp == -2L
        } catch (_: RuntimeException) {
            fail(id, R.string.vpn_handshake_read_error)
        } catch (_: LinkageError) {
            fail(id, R.string.vpn_native_error)
        }
    }

    private fun fail(id: Long, message: Int, error: VpnError = VpnError(message)) {
        main.post {
            if (!closing) VpnConnection.serviceFailed(id, message, error)
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

    private fun closeBackend() {
        if (closing) return
        closing = true
        setup?.cancel(true)
        setup = null
        // A retry cannot enter GoBackend before this teardown on the same worker.
        backendWorker.execute {
            polling?.cancel(false)
            val failed = try {
                backend?.setState(tunnel, Tunnel.State.DOWN, null)
                false
            } catch (_: Exception) {
                true
            } catch (_: LinkageError) {
                true
            } finally {
                backend = null
            }
            main.post {
                cleanupResult = failed
                reportClosed()
            }
        }
    }

    private fun reportClosed() {
        val failed = cleanupResult ?: return
        if (!destroyed || attempt == 0L) return
        val id = attempt
        attempt = 0L
        VpnConnection.serviceClosed(id, failed)
    }

    private fun notification(): Notification {
        val manager = checkNotNull(getSystemService(NotificationManager::class.java))
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AwgVpnService::class.java).setAction(STOP_ACTION), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.vpn_notification))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.vpn_disconnect), stop).build())
            .build()
    }

    private companion object {
        val serviceCalls = Executors.newSingleThreadExecutor()
        val backendWorker = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
        const val CHANNEL = "amneziawg_vpn"
        const val NOTIFICATION_ID = 1
        const val STOP_ACTION = "com.kachur.vpn.STOP_VPN"
    }
}
