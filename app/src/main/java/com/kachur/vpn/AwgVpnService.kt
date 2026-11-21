package com.kachur.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
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
import java.util.concurrent.TimeUnit

internal enum class VpnPhase {
    IDLE, STARTING, WAITING, HANDSHAKE, STOPPING,
}

// Main-thread state. The profile moves to the service via memory, never through an Intent.
internal object VpnConnection {
    var phase = VpnPhase.IDLE
        private set
    var message = R.string.vpn_disconnected
        private set
    var onChange: (() -> Unit)? = null
    private var pending: AwgConfig? = null

    fun publish(phase: VpnPhase, message: Int) {
        this.phase = phase
        this.message = message
        onChange?.invoke()
    }

    fun start(context: Context, config: AwgConfig) {
        if (phase != VpnPhase.IDLE) return
        pending = config
        publish(VpnPhase.STARTING, R.string.vpn_starting)
        try {
            context.startForegroundService(Intent(context, AwgVpnService::class.java))
        } catch (_: RuntimeException) {
            takeProfile()
            publish(VpnPhase.IDLE, R.string.vpn_start_error)
        }
    }

    fun takeProfile(): AwgConfig? = pending.also { pending = null }

    fun stop(context: Context) {
        if (phase != VpnPhase.WAITING && phase != VpnPhase.HANDSHAKE) return
        publish(VpnPhase.STOPPING, R.string.vpn_stopping)
        context.stopService(Intent(context, AwgVpnService::class.java))
    }
}

class AwgVpnService : VpnService() {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val tunnel = object : Tunnel {
        override fun getName() = "KachurVPN"
        override fun onStateChange(state: Tunnel.State) = Unit
    }
    private var backend: GoBackend? = null
    private var setup: Future<*>? = null
    @Volatile private var closing = false
    private var started = false
    private var finalMessage = R.string.vpn_disconnected

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP_ACTION) {
            if (started) stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        val config = VpnConnection.takeProfile()
        if (config == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        try {
            startForeground(NOTIFICATION_ID, notification())
            setup = worker.submit { establish(config) }
        } catch (_: RuntimeException) {
            finalMessage = R.string.vpn_start_error
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun establish(profile: AwgConfig) {
        if (closing) return
        try {
            val config = profile.toBackendConfig()
            if (closing) return
            val engine = GoBackend(applicationContext, this)
            backend = engine
            check(engine.setState(tunnel, Tunnel.State.UP, config) == Tunnel.State.UP)
            main.post {
                if (!closing) VpnConnection.publish(VpnPhase.WAITING, R.string.vpn_waiting_handshake)
            }
            if (!closing) {
                worker.scheduleWithFixedDelay({ pollHandshake(engine) }, 1, 2, TimeUnit.SECONDS)
            }
        } catch (_: BadConfigException) {
            fail(R.string.vpn_backend_invalid_profile)
        } catch (error: BackendException) {
            fail(when (error.reason) {
                BackendException.Reason.DNS_RESOLUTION_FAILURE -> R.string.vpn_dns_error
                BackendException.Reason.VPN_NOT_AUTHORIZED -> R.string.vpn_permission_denied
                else -> R.string.vpn_start_error
            })
        } catch (_: Exception) {
            fail(R.string.vpn_start_error)
        } catch (_: LinkageError) {
            fail(R.string.vpn_native_error)
        }
    }

    private fun pollHandshake(engine: GoBackend) {
        if (closing) return
        try {
            // This is evidence of a handshake, not a test of end-to-end traffic.
            when (engine.getLastHandshake(tunnel)) {
                in 1L..Long.MAX_VALUE -> main.post {
                    if (!closing && VpnConnection.phase != VpnPhase.HANDSHAKE) {
                        VpnConnection.publish(VpnPhase.HANDSHAKE, R.string.vpn_handshake_received)
                    }
                }
                -3L -> fail(R.string.vpn_start_error)
            }
        } catch (_: RuntimeException) {
            fail(R.string.vpn_start_error)
        } catch (_: LinkageError) {
            fail(R.string.vpn_native_error)
        }
    }

    private fun fail(message: Int) {
        main.post {
            if (!closing) {
                finalMessage = message
                stopSelf()
            }
        }
    }

    override fun onRevoke() {
        // Android may call this off the main thread.
        main.post {
            if (!closing) {
                finalMessage = R.string.vpn_permission_revoked
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        closing = true
        setup?.cancel(true)
        setup = null
        // Serialize teardown with setup and statistics so no JNI call can race with closing the TUN.
        worker.execute {
            var message = finalMessage
            try {
                backend?.setState(tunnel, Tunnel.State.DOWN, null)
            } catch (_: Exception) {
                message = R.string.vpn_stop_error
            } catch (_: LinkageError) {
                message = R.string.vpn_stop_error
            } finally {
                backend = null
                main.post {
                    if (started) VpnConnection.publish(VpnPhase.IDLE, message)
                }
            }
        }
        worker.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
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
        const val CHANNEL = "amneziawg_vpn"
        const val NOTIFICATION_ID = 1
        const val STOP_ACTION = "com.kachur.vpn.STOP_VPN"
    }
}
