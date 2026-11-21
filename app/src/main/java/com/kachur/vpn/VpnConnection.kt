package com.kachur.vpn

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import org.amnezia.awg.config.BadConfigException
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal enum class VpnPhase(val label: Int) {
    IDLE(R.string.vpn_phase_idle),
    STARTING(R.string.vpn_phase_starting),
    WAITING(R.string.vpn_phase_waiting),
    HANDSHAKE(R.string.vpn_phase_handshake),
    RECOVERING(R.string.vpn_phase_recovering),
    STOPPING(R.string.vpn_phase_stopping),
}

internal class VpnStartOptions(
    val profile: AwgConfig,
    val routing: VpnRoutingSettings,
    val country: String,
    val routePlan: IpRoutePlan? = null,
) {
    val exclusions: Set<String> get() = routing.apps
}

// State is owned by the main thread. Only the attempt ID crosses the service Intent.
internal object VpnConnection {
    var phase = VpnPhase.IDLE
        private set
    var message = R.string.vpn_disconnected
        private set
    var eventNumber = 0L
        private set
    var lastEvent = R.string.vpn_diagnostic_idle
        private set
    var resetGeneration = 0L
        private set
    var lastError: VpnError? = null
        private set
    var currentError: VpnError? = null
        private set
    var serviceLaunch = R.string.vpn_service_not_requested
        private set
    var serviceResponse = R.string.vpn_service_no_response_yet
        private set

    private val main = Handler(Looper.getMainLooper())
    // A delayed Android Binder call must leave the UI and its watchdogs responsive.
    internal val systemCalls = Executors.newFixedThreadPool(2)
    private val observers = mutableSetOf<() -> Unit>()
    private var nextAttempt = 0L
    private var active: Attempt? = null
    val checkingRoutes: Boolean get() = active?.routingCheck != null

    private class RoutingCheck(var worker: Future<*>? = null)
    private class Restart(val context: Context, val options: VpnStartOptions)

    private class Attempt(val id: Long, var options: VpnStartOptions?) {
        var request: Future<*>? = null
        var deadline: Runnable? = null
        var cancelService: (() -> Unit)? = null
        var completionMessage = R.string.vpn_disconnected
        var routingCheck: RoutingCheck? = null
        var restart: Restart? = null
    }

    fun observe(observer: () -> Unit) {
        observers.add(observer)
    }

    fun removeObserver(observer: () -> Unit) {
        observers.remove(observer)
    }

    fun record(event: Int, error: VpnError? = null) {
        eventNumber++
        lastEvent = event
        if (error != null) lastError = error
        observers.toList().forEach { it() }
    }

    fun publish(phase: VpnPhase, message: Int, error: VpnError? = null) {
        this.phase = phase
        this.message = message
        currentError = error
        record(message, error)
    }

    fun start(context: Context, config: AwgConfig, country: String = context.getString(R.string.vpn_country_default)) {
        start(context, VpnStartOptions(config, SplitTunnelingSettings(context).snapshot(), country))
    }

    private fun start(context: Context, options: VpnStartOptions) {
        if (phase != VpnPhase.IDLE) return
        val attempt = Attempt(++nextAttempt, options)
        active = attempt
        serviceLaunch = R.string.vpn_requesting_service
        serviceResponse = R.string.vpn_service_awaiting_reply
        publish(VpnPhase.STARTING, R.string.vpn_requesting_service)
        setDeadline(attempt, STARTUP_TIMEOUT_MS) {
            val error = when {
                attempt.cancelService == null -> {
                    serviceResponse = R.string.vpn_service_no_reply
                    if (serviceLaunch == R.string.vpn_requesting_service) {
                        serviceLaunch = R.string.vpn_service_call_timeout
                        R.string.vpn_service_call_timeout
                    } else {
                        R.string.vpn_service_timeout
                    }
                }
                serviceResponse == R.string.vpn_service_started -> R.string.vpn_foreground_timeout
                else -> R.string.vpn_setup_timeout
            }
            stopAttempt(attempt, error, VpnError(error))
        }
        attempt.request = systemCalls.submit {
            val accepted = try {
                val service = Intent(context, AwgVpnService::class.java).putExtra(EXTRA_ATTEMPT, attempt.id)
                context.startForegroundService(service) != null
            } catch (_: RuntimeException) {
                false
            }
            main.post {
                if (active !== attempt || phase == VpnPhase.STOPPING) return@post
                attempt.request = null
                serviceLaunch = if (accepted) R.string.vpn_service_launch_accepted else R.string.vpn_service_rejected
                if (accepted) {
                    record(serviceLaunch)
                } else {
                    if (attempt.cancelService == null) serviceResponse = R.string.vpn_service_no_response_yet
                    stopAttempt(attempt, R.string.vpn_service_rejected, error = VpnError(R.string.vpn_service_rejected))
                }
            }
        }
    }

    fun claim(id: Long, cancel: () -> Unit): VpnStartOptions? {
        val attempt = active?.takeIf { it.id == id && phase == VpnPhase.STARTING } ?: return null
        if (attempt.cancelService != null) return null
        val options = attempt.options ?: return null
        attempt.cancelService = cancel
        serviceResponse = R.string.vpn_service_started
        publish(VpnPhase.STARTING, R.string.vpn_service_started)
        return options
    }

    fun foregroundReady(id: Long) {
        if (active?.id != id || phase != VpnPhase.STARTING) return
        serviceResponse = R.string.vpn_service_foreground
        publish(VpnPhase.STARTING, R.string.vpn_service_foreground)
    }

    fun progress(id: Long, message: Int) {
        if (active?.id == id && phase == VpnPhase.STARTING) publish(VpnPhase.STARTING, message)
    }

    fun tunnelReady(id: Long, provingRecovery: Boolean = true) {
        val attempt = active?.takeIf { it.id == id } ?: return
        when (phase) {
            VpnPhase.STARTING -> {
                clearDeadline(attempt)
                publish(VpnPhase.WAITING, R.string.vpn_waiting_handshake)
            }
            VpnPhase.RECOVERING -> if (provingRecovery) {
                publish(VpnPhase.RECOVERING, R.string.vpn_recovery_waiting_handshake)
            } else {
                publish(VpnPhase.WAITING, R.string.vpn_waiting_handshake)
            }
            else -> Unit
        }
    }

    fun ownsAttempt(id: Long) = active?.id == id

    fun recovering(id: Long, message: Int) {
        val attempt = active?.takeIf { it.id == id && it.cancelService != null } ?: return
        if (phase == VpnPhase.STOPPING) return
        cancelRoutingCheck(attempt)
        clearDeadline(attempt)
        if (phase != VpnPhase.RECOVERING || this.message != message) publish(VpnPhase.RECOVERING, message)
    }

    fun handshake(id: Long) {
        if (ownsAttempt(id) && (phase == VpnPhase.WAITING || phase == VpnPhase.RECOVERING)) {
            publish(VpnPhase.HANDSHAKE, R.string.vpn_handshake_received)
        }
    }

    fun handshakeReadFailed(id: Long) {
        if (ownsAttempt(id) && (phase == VpnPhase.WAITING || phase == VpnPhase.HANDSHAKE || phase == VpnPhase.RECOVERING)) {
            record(R.string.vpn_handshake_read_error, error = VpnError(R.string.vpn_handshake_read_error))
        }
    }

    fun serviceFailed(id: Long, message: Int, error: VpnError = VpnError(message)) {
        val attempt = active?.takeIf { it.id == id } ?: return
        if (phase != VpnPhase.STOPPING) {
            stopAttempt(attempt, message, error)
        } else if (attempt.restart != null && message == R.string.vpn_permission_revoked) {
            cancelReconnect(attempt, message)
            publish(VpnPhase.STOPPING, message, error)
        }
    }

    fun serviceStopRejected(id: Long) {
        val attempt = active?.takeIf { it.id == id } ?: return
        cancelReconnect(attempt, R.string.vpn_stop_error)
        record(R.string.vpn_stop_error, error = VpnError(R.string.vpn_stop_error))
    }

    fun serviceClosed(id: Long, cleanupFailed: Boolean) {
        val attempt = active?.takeIf { it.id == id }
        if (attempt == null) {
            if (cleanupFailed) record(R.string.vpn_stop_error, error = VpnError(R.string.vpn_stop_error))
            return
        }
        serviceResponse = R.string.vpn_service_closed
        val error = when {
            cleanupFailed -> R.string.vpn_stop_error
            phase == VpnPhase.STARTING -> R.string.vpn_service_ended_during_start
            else -> null
        }
        val restart = attempt.restart.takeIf { error == null }
        finish(attempt, error ?: attempt.completionMessage, error?.let { VpnError(it) })
        if (restart != null) start(restart.context, restart.options)
    }

    fun routingNeedsReconnect(context: Context): Boolean {
        val options = active?.options ?: return false
        return (phase == VpnPhase.WAITING || phase == VpnPhase.HANDSHAKE) &&
            options.routing != SplitTunnelingSettings(context).snapshot()
    }

    fun reconnectWithSavedExclusions(context: Context) {
        if (checkingRoutes || !routingNeedsReconnect(context)) return
        val attempt = checkNotNull(active)
        val options = checkNotNull(attempt.options)
        val routing = SplitTunnelingSettings(context).snapshot()
        if (options.profile.includedAppsAfterExclusions(routing.apps)?.isEmpty() == true) {
            publish(phase, R.string.split_no_included_apps, VpnError(R.string.split_no_included_apps))
            return
        }
        val appContext = context.applicationContext
        if (routing.ipRoutes.isEmpty()) {
            attempt.restart = Restart(appContext, VpnStartOptions(options.profile, routing, options.country))
            stopAttempt(attempt, R.string.vpn_disconnected)
            return
        }

        // Validate the replacement before stopping the live tunnel. Only this snapshot is applied.
        val check = RoutingCheck()
        attempt.routingCheck = check
        setDeadline(attempt, ROUTE_CHECK_TIMEOUT_MS) {
            cancelRoutingCheck(attempt)
            publish(phase, R.string.ip_vpn_route_timeout, VpnError(R.string.ip_vpn_route_timeout))
        }
        check.worker = systemCalls.submit {
            var error: VpnError? = null
            val plan = try {
                options.profile.toBackendConfig(routing.apps).ipRoutePlan(routing.ipRoutes)
            } catch (_: TooManyVpnRoutesException) {
                error = VpnError(R.string.ip_vpn_route_limit)
                null
            } catch (invalid: BadConfigException) {
                error = invalid.toSafeVpnError()
                null
            } catch (_: Exception) {
                error = VpnError(R.string.ip_vpn_route_error)
                null
            }
            main.post {
                if (active !== attempt || attempt.routingCheck !== check) return@post
                attempt.routingCheck = null
                clearDeadline(attempt)
                if (error != null) {
                    publish(phase, R.string.ip_vpn_route_error, error)
                } else if (routing != SplitTunnelingSettings(appContext).snapshot()) {
                    publish(phase, R.string.ip_vpn_route_changed)
                } else {
                    attempt.restart = Restart(appContext, VpnStartOptions(options.profile, routing, options.country, plan))
                    stopAttempt(attempt, R.string.vpn_disconnected)
                }
            }
        }
        publish(phase, R.string.ip_vpn_route_checking)
    }

    fun stop() {
        val attempt = active ?: return
        when (phase) {
            VpnPhase.STARTING -> stopAttempt(attempt, R.string.vpn_start_cancelled)
            VpnPhase.WAITING, VpnPhase.HANDSHAKE, VpnPhase.RECOVERING -> stopAttempt(attempt, R.string.vpn_disconnected)
            VpnPhase.STOPPING -> if (attempt.restart != null) {
                cancelReconnect(attempt, R.string.vpn_disconnected)
                publish(VpnPhase.STOPPING, R.string.vpn_stopping)
            }
            else -> Unit
        }
    }

    fun reset() {
        resetGeneration++
        val attempt = active
        when {
            attempt == null -> publish(VpnPhase.IDLE, R.string.vpn_reset_done)
            phase == VpnPhase.STOPPING -> {
                cancelReconnect(attempt, R.string.vpn_reset_done)
                record(R.string.vpn_reset_stopping)
            }
            else -> stopAttempt(attempt, R.string.vpn_reset_done)
        }
    }

    private fun stopAttempt(attempt: Attempt, message: Int, error: VpnError? = null) {
        cancelRoutingCheck(attempt)
        attempt.options = null
        attempt.request?.cancel(true)
        attempt.request = null
        val cancel = attempt.cancelService
        if (cancel == null) {
            if (error == null) {
                serviceLaunch = R.string.vpn_service_request_cancelled
                serviceResponse = R.string.vpn_service_no_reply
            }
            finish(attempt, message, error)
            return
        }

        attempt.completionMessage = message
        val status = when {
            error != null -> message
            message == R.string.vpn_reset_done -> R.string.vpn_reset_stopping
            attempt.restart != null -> R.string.split_reconnecting
            else -> R.string.vpn_stopping
        }
        publish(VpnPhase.STOPPING, status, error)
        setDeadline(attempt, STOP_TIMEOUT_MS) {
            serviceResponse = R.string.vpn_service_no_stop_reply
            finish(attempt, R.string.vpn_stop_timeout, error = VpnError(R.string.vpn_stop_timeout))
        }
        cancel()
    }

    private fun finish(attempt: Attempt, message: Int, error: VpnError? = null) {
        clearDeadline(attempt)
        cancelRoutingCheck(attempt)
        attempt.options = null
        attempt.restart = null
        attempt.cancelService = null
        active = null
        publish(VpnPhase.IDLE, message, error)
    }

    private fun cancelReconnect(attempt: Attempt, message: Int) {
        attempt.restart = null
        attempt.completionMessage = message
    }

    private fun cancelRoutingCheck(attempt: Attempt) {
        attempt.routingCheck?.worker?.cancel(true)
        attempt.routingCheck = null
    }

    private fun setDeadline(attempt: Attempt, timeoutMs: Long, action: () -> Unit) {
        clearDeadline(attempt)
        val deadline = Runnable { if (active === attempt) action() }
        attempt.deadline = deadline
        main.postDelayed(deadline, timeoutMs)
    }

    private fun clearDeadline(attempt: Attempt) {
        attempt.deadline?.let(main::removeCallbacks)
        attempt.deadline = null
    }

    const val EXTRA_ATTEMPT = "vpn_attempt"
    const val STARTUP_TIMEOUT_MS = 30_000L
    const val STOP_TIMEOUT_MS = 15_000L
    private const val ROUTE_CHECK_TIMEOUT_MS = 10_000L
}
