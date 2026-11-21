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
import java.util.concurrent.ScheduledThreadPoolExecutor
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
    private val observers = mutableSetOf<() -> Unit>()
    private var pending: AwgConfig? = null
    private var nextAttempt = 0L
    private var attempt = 0L
    private val main = Handler(Looper.getMainLooper())
    private var startupDeadline: Runnable? = null
    private val history = mutableListOf<Int>()
    val stages: List<Int> get() = history

    fun observe(observer: () -> Unit) {
        observers.add(observer)
    }

    fun removeObserver(observer: () -> Unit) {
        observers.remove(observer)
    }

    fun publish(phase: VpnPhase, message: Int) {
        this.phase = phase
        this.message = message
        observers.toList().forEach { it() }
    }

    fun start(context: Context, config: AwgConfig) {
        if (phase != VpnPhase.IDLE) return
        val id = ++nextAttempt
        attempt = id
        pending = config
        history.clear()
        history.add(R.string.vpn_permission_granted)
        progress(id, R.string.vpn_requesting_service)
        val service = serviceIntent(context).putExtra(EXTRA_ATTEMPT, id)
        val deadline = Runnable {
            val error = if (pending != null) R.string.vpn_service_timeout else R.string.vpn_setup_timeout
            abortStartup(context, id, error)
        }
        startupDeadline = deadline
        main.postDelayed(deadline, STARTUP_TIMEOUT_MS)
        try {
            if (context.startForegroundService(service) == null) {
                finish(id, R.string.vpn_service_rejected)
            }
        } catch (_: RuntimeException) {
            finish(id, R.string.vpn_service_rejected)
        }
    }

    fun takeProfile(id: Long): AwgConfig? =
        if (id == attempt) pending.also { pending = null } else null

    fun progress(id: Long, message: Int) {
        if (id != attempt || id == 0L) return
        history.add(message)
        publish(VpnPhase.STARTING, message)
    }

    fun tunnelReady(id: Long) {
        if (id != attempt || phase != VpnPhase.STARTING) return
        clearDeadline()
        history.add(R.string.vpn_waiting_handshake)
        publish(VpnPhase.WAITING, R.string.vpn_waiting_handshake)
    }

    fun handshake(id: Long) {
        if (id == attempt && phase == VpnPhase.WAITING) {
            publish(VpnPhase.HANDSHAKE, R.string.vpn_handshake_received)
        }
    }

    fun finish(id: Long, message: Int) {
        if (id != attempt || id == 0L) return
        clearDeadline()
        pending = null
        attempt = 0
        publish(VpnPhase.IDLE, message)
    }

    fun stop(context: Context) {
        if (phase == VpnPhase.STARTING) {
            abortStartup(context, attempt, R.string.vpn_start_cancelled)
            return
        }
        if (phase != VpnPhase.WAITING && phase != VpnPhase.HANDSHAKE) return
        val previous = phase
        publish(VpnPhase.STOPPING, R.string.vpn_stopping)
        try {
            if (!context.stopService(serviceIntent(context))) finish(attempt, R.string.vpn_stop_error)
        } catch (_: RuntimeException) {
            publish(previous, R.string.vpn_stop_error)
        }
    }

    private fun abortStartup(context: Context, id: Long, message: Int) {
        if (id != attempt || phase != VpnPhase.STARTING) return
        if (pending == null) {
            try {
                context.stopService(serviceIntent(context))
            } catch (_: RuntimeException) {
                // Service callbacks from this attempt are invalidated below.
            }
        }
        finish(id, message)
    }

    private fun clearDeadline() {
        startupDeadline?.let(main::removeCallbacks)
        startupDeadline = null
    }

    private fun serviceIntent(context: Context) = Intent(context, AwgVpnService::class.java)

    const val EXTRA_ATTEMPT = "vpn_attempt"
    const val STARTUP_TIMEOUT_MS = 30_000L
}

class AwgVpnService : VpnService() {
    private val main = Handler(Looper.getMainLooper())
    private val tunnel = object : Tunnel {
        override fun getName() = "KachurVPN"
        override fun onStateChange(state: Tunnel.State) = Unit
    }
    private var backend: GoBackend? = null
    private var setup: Future<*>? = null
    @Volatile private var closing = false
    private var attempt = 0L
    private var polling: Future<*>? = null
    private var finalMessage = R.string.vpn_disconnected

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP_ACTION) {
            if (attempt != 0L) stopSelf()
            return START_NOT_STICKY
        }
        if (attempt != 0L) return START_NOT_STICKY
        val id = intent?.getLongExtra(VpnConnection.EXTRA_ATTEMPT, 0L) ?: 0L
        val config = VpnConnection.takeProfile(id)
        if (config == null) {
            // A cancelled or expired start may still be delivered by Android.
            // Acknowledge startForegroundService before stopping this obsolete command.
            if (id != 0L) {
                try {
                    startForeground(NOTIFICATION_ID, notification())
                } catch (_: RuntimeException) {
                    // No profile was claimed and no tunnel can be created.
                }
            }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        attempt = id
        VpnConnection.progress(id, R.string.vpn_service_started)
        try {
            startForeground(NOTIFICATION_ID, notification())
            setup = worker.submit { establish(id, config) }
        } catch (_: RuntimeException) {
            finalMessage = R.string.vpn_foreground_error
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun establish(id: Long, profile: AwgConfig) {
        if (closing) return
        try {
            report(id, R.string.vpn_checking_profile)
            val config = profile.toBackendConfig()
            if (closing) return
            report(id, R.string.vpn_loading_engine)
            val engine = GoBackend(applicationContext, this)
            backend = engine
            if (closing) return
            report(id, R.string.vpn_starting)
            check(engine.setState(tunnel, Tunnel.State.UP, config) == Tunnel.State.UP)
            main.post {
                if (!closing) VpnConnection.tunnelReady(id)
            }
            if (!closing) {
                polling = worker.scheduleWithFixedDelay({ pollHandshake(id, engine) }, 1, 2, TimeUnit.SECONDS)
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

    private fun report(id: Long, message: Int) {
        main.post { if (!closing) VpnConnection.progress(id, message) }
    }

    private fun pollHandshake(id: Long, engine: GoBackend) {
        if (closing) return
        try {
            // This is evidence of a handshake, not a test of end-to-end traffic.
            when (engine.getLastHandshake(tunnel)) {
                in 1L..Long.MAX_VALUE -> main.post {
                    if (!closing) VpnConnection.handshake(id)
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
            polling?.cancel(false)
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
                    VpnConnection.finish(attempt, message)
                }
            }
        }
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
        // Serialize native setup and teardown across service instances after timeout or cancellation.
        val worker = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
        const val CHANNEL = "amneziawg_vpn"
        const val NOTIFICATION_ID = 1
        const val STOP_ACTION = "com.kachur.vpn.STOP_VPN"
    }
}
