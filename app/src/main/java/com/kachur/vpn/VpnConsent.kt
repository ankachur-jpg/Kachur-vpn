package com.kachur.vpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.util.UUID
import java.util.concurrent.Future

/** The same Android consent flow serves the main screen and the system quick controls. */
internal class VpnConsent(
    private val activity: ComponentActivity,
    private val status: (message: Int, error: Boolean) -> Unit,
    private val granted: (automatic: Boolean, profileId: String?, startup: VpnStartupTrace?) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var request: Request? = null
    private var resumed = false

    val busy: Boolean get() = request != null
    val automatic: Boolean get() = request?.automatic == true
    val dialogLaunched: Boolean get() = request?.dialogLaunched == true

    private class Request(
        val key: String,
        val automatic: Boolean,
        val profileId: String?,
        val startup: VpnStartupTrace?,
    ) {
        val resetGeneration = VpnConnection.resetGeneration
        var dialogLaunched = false
        var resultReceived = false
        var launcher: ActivityResultLauncher<Intent>? = null
        var check: Future<*>? = null
        var deadline: Runnable? = null
        var onResume: (() -> Unit)? = null
        val pending: Boolean get() = dialogLaunched && !resultReceived
    }

    fun begin(
        automatic: Boolean,
        profileId: String? = null,
        key: String = UUID.randomUUID().toString(),
        startup: VpnStartupTrace? = null,
    ): Boolean {
        if (busy || !VpnConnection.reservePreparation(key)) return false
        val next = Request(key, automatic, profileId, startup)
        request = next
        armDeadline(next, CHECK_TIMEOUT_MS)
        checkPermission(next, allowDialog = true)
        return true
    }

    fun restore(state: Bundle?) {
        if (state == null) return
        val key = state.getString(REQUEST_KEY)
        if (key == null || state.getString(PROCESS) != processToken ||
            state.getLong(RESET_GENERATION) != VpnConnection.resetGeneration
        ) {
            if (key != null || state.getBoolean(LEGACY_PENDING)) status(R.string.vpn_permission_no_result, true)
            return
        }
        if (!VpnConnection.reservePreparation(key)) return
        val restored = Request(key, state.getBoolean(AUTOMATIC), state.getString(PROFILE),
            VpnStartupTiming.find(state.getString(STARTUP))).apply {
            dialogLaunched = true
            resultReceived = state.getBoolean(RESULT_RECEIVED)
        }
        request = restored
        armDeadline(restored, RESULT_TIMEOUT_MS)
        if (restored.pending) {
            report(restored, R.string.vpn_waiting_permission)
            registerResult(restored)
        } else {
            report(restored, R.string.vpn_permission_check_return)
        }
    }

    fun save(state: Bundle) {
        request?.takeIf { it.dialogLaunched }?.let {
            state.putString(PROCESS, processToken)
            state.putString(REQUEST_KEY, it.key)
            state.putBoolean(RESULT_RECEIVED, it.resultReceived)
            state.putLong(RESET_GENERATION, it.resetGeneration)
            state.putBoolean(AUTOMATIC, it.automatic)
            state.putString(PROFILE, it.profileId)
            state.putString(STARTUP, it.startup?.id)
        }
    }

    fun resume() {
        resumed = true
        request?.takeIf(::isCurrent)?.let {
            val action = it.onResume
            it.onResume = null
            if (action != null) action()
            else if (it.dialogLaunched && it.check == null) checkPermission(it, allowDialog = false)
        }
    }

    fun pause() { resumed = false }

    fun cancel(preserveTrace: Boolean = false) {
        request?.let {
            if (!preserveTrace) it.startup?.mark(VpnStartStage.ENDED)
            clear(it)
        }
    }

    fun cancelIfInvalid(): Boolean {
        val invalid = request?.takeUnless(::isCurrent) ?: return false
        invalid.startup?.mark(VpnStartStage.ENDED)
        clear(invalid)
        return true
    }

    private fun checkPermission(request: Request, allowDialog: Boolean) {
        request.startup?.mark(VpnStartStage.PERMISSION_CHECK)
        report(request, if (allowDialog) R.string.vpn_permission_check else R.string.vpn_permission_check_return)
        val context = activity.applicationContext
        request.check = VpnConnection.systemCalls.submit {
            try {
                if (request.automatic && allowDialog && existingSystemVpn(context)) {
                    main.post { fail(request, R.string.vpn_auto_other_vpn, error = false) }
                    return@submit
                }
                val consent = VpnService.prepare(context)
                request.startup?.mark(VpnStartStage.PERMISSION_CHECKED)
                main.post {
                    if (!isCurrent(request)) return@post
                    request.check = null
                    when {
                        consent == null -> runWhenResumed(request) { complete(request) }
                        allowDialog -> runWhenResumed(request) { launchDialog(request, consent) }
                        else -> fail(request, R.string.vpn_permission_no_result)
                    }
                }
            } catch (_: RuntimeException) {
                main.post { fail(request, R.string.vpn_permission_unavailable) }
            }
        }
    }

    private fun launchDialog(request: Request, consent: Intent) {
        request.dialogLaunched = true
        request.startup?.mark(VpnStartStage.CONSENT_DIALOG)
        armDeadline(request, RESULT_TIMEOUT_MS)
        report(request, R.string.vpn_waiting_permission)
        try {
            registerResult(request)?.launch(consent)
        } catch (_: RuntimeException) {
            fail(request, R.string.vpn_permission_unavailable)
        }
    }

    private fun registerResult(request: Request): ActivityResultLauncher<Intent>? {
        // The registry key prevents a delayed result from authorizing a later request.
        val launcher = activity.activityResultRegistry.register(request.key, ActivityResultContracts.StartActivityForResult()) { result ->
            if (!isCurrent(request) || request.resultReceived) return@register
            request.resultReceived = true
            request.startup?.mark(VpnStartStage.CONSENT_RESULT)
            when (result.resultCode) {
                Activity.RESULT_OK -> {
                    report(request, R.string.vpn_permission_result_ok)
                    if (request.check == null) runWhenResumed(request) { checkPermission(request, allowDialog = false) }
                }
                Activity.RESULT_CANCELED -> fail(request, R.string.vpn_permission_denied)
                else -> fail(request, R.string.vpn_permission_unexpected_result)
            }
        }
        if (!isCurrent(request)) {
            launcher.unregister()
            return null
        }
        request.launcher = launcher
        return launcher
    }

    private fun complete(request: Request) {
        detach(request)
        try {
            granted(request.automatic, request.profileId, request.startup)
        } finally {
            VpnConnection.releasePreparation(request.key)
        }
    }

    private fun runWhenResumed(request: Request, action: () -> Unit) {
        if (resumed) action() else request.onResume = action
    }

    private fun armDeadline(request: Request, timeout: Long) {
        request.deadline?.let(main::removeCallbacks)
        val deadline = Runnable {
            val message = if (request.pending) R.string.vpn_permission_result_timeout else R.string.vpn_permission_check_timeout
            fail(request, message)
        }
        request.deadline = deadline
        main.postDelayed(deadline, timeout)
    }

    private fun report(request: Request, message: Int) {
        VpnConnection.describePreparation(request.key, message, request.pending)
        status(message, false)
    }

    private fun fail(request: Request, message: Int, error: Boolean = true) {
        if (!isCurrent(request)) return
        request.startup?.mark(VpnStartStage.ENDED)
        clear(request)
        status(message, error)
    }

    private fun isCurrent(candidate: Request): Boolean =
        request === candidate && candidate.resetGeneration == VpnConnection.resetGeneration &&
            VpnConnection.ownsPreparation(candidate.key)

    private fun clear(request: Request) {
        detach(request)
        VpnConnection.releasePreparation(request.key)
    }

    private fun detach(request: Request) {
        this.request = null
        request.onResume = null
        request.check?.cancel(true)
        request.deadline?.let(main::removeCallbacks)
        request.launcher?.unregister()
    }

    private fun existingSystemVpn(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    private companion object {
        val processToken = UUID.randomUUID().toString()
        const val PROCESS = "vpn_permission_process"
        const val LEGACY_PENDING = "vpn_permission_pending"
        const val REQUEST_KEY = "vpn_permission_request_key"
        const val RESET_GENERATION = "vpn_permission_reset_generation"
        const val RESULT_RECEIVED = "vpn_permission_result_received"
        const val AUTOMATIC = "vpn_permission_automatic"
        const val PROFILE = "vpn_permission_profile"
        const val STARTUP = "vpn_permission_startup"
        const val CHECK_TIMEOUT_MS = 30_000L
        const val RESULT_TIMEOUT_MS = 60_000L
    }
}
