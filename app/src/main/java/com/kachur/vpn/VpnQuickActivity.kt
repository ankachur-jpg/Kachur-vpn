package com.kachur.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.ViewModelProvider
import java.util.UUID

/** A user-invoked window for starting the selected profile, including Android's VPN consent. */
class VpnQuickActivity : ComponentActivity() {
    private val consent by lazy { VpnConsent(this, ::showConsentStatus) { _, id -> startSelected(id) } }
    private lateinit var session: ImportSession
    private lateinit var status: TextView
    private lateinit var close: Button
    private val main = Handler(Looper.getMainLooper())
    private val observer: () -> Unit = ::renderConnection
    private var resumed = false
    private var requested = false
    private var loading = false
    private var failed = false
    private var message = R.string.vpn_quick_preparing
    private var key = UUID.randomUUID().toString()
    private var resetGeneration = VpnConnection.resetGeneration
    private var attempt: Long? = null
    private val profileDeadline = Runnable { if (requested) showError(R.string.profile_restore_error) }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppAppearance.read(newBase).applyTo(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = ViewModelProvider(this)[ImportSession::class.java]
        setContentView(uiColumn(20).apply {
            setBackgroundColor(getColor(R.color.ui_surface))
            uiAdd(uiHeading(R.string.app_name))
            status = uiLabel(16).apply {
                id = R.id.vpn_quick_status
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            uiAdd(status, 12)
            close = uiButton(android.R.string.cancel).apply {
                id = R.id.vpn_quick_close
                setOnClickListener { cancel() }
            }
            uiAdd(close, 16)
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = cancel()
        })

        when {
            intent.action != ACTION_ENABLE -> finish()
            savedInstanceState == null -> requested = true
            savedInstanceState.getString(PROCESS) == processToken -> {
                key = savedInstanceState.getString(PREPARATION) ?: key
                resetGeneration = savedInstanceState.getLong(RESET)
                message = savedInstanceState.getInt(MESSAGE, R.string.vpn_quick_preparing)
                failed = savedInstanceState.getBoolean(FAILED)
                requested = savedInstanceState.getBoolean(REQUESTED)
                attempt = savedInstanceState.getLong(ATTEMPT).takeIf { it != 0L }
                consent.restore(savedInstanceState)
                if (!requested && !consent.busy && attempt == null && !failed) showError(R.string.vpn_quick_interrupted)
            }
            else -> showError(R.string.vpn_quick_interrupted)
        }
        renderStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action != ACTION_ENABLE || !failed || isFinishing) return
        key = UUID.randomUUID().toString()
        resetGeneration = VpnConnection.resetGeneration
        failed = false
        requested = true
        message = R.string.vpn_quick_preparing
        renderStatus()
        advance()
    }

    override fun onStart() {
        super.onStart()
        VpnConnection.observe(observer)
        session.onChange = ::advance
        renderConnection()
    }

    override fun onPostResume() {
        super.onPostResume()
        resumed = true
        consent.resume()
        advance()
    }

    override fun onPause() {
        resumed = false
        consent.pause()
        super.onPause()
    }

    override fun onStop() {
        session.onChange = null
        VpnConnection.removeObserver(observer)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(PROCESS, processToken)
        outState.putString(PREPARATION, key)
        outState.putLong(RESET, resetGeneration)
        outState.putInt(MESSAGE, message)
        outState.putBoolean(FAILED, failed)
        outState.putBoolean(REQUESTED, requested || (consent.busy && !consent.dialogLaunched))
        outState.putLong(ATTEMPT, attempt ?: 0L)
        consent.save(outState)
    }

    override fun onDestroy() {
        main.removeCallbacks(profileDeadline)
        consent.cancel()
        VpnConnection.releasePreparation(key)
        super.onDestroy()
    }

    private fun advance() {
        if (!resumed || !requested || isFinishing) return
        if (resetGeneration != VpnConnection.resetGeneration) {
            showError(R.string.vpn_quick_interrupted)
            return
        }
        if (!loading) {
            if (VpnConnection.phase != VpnPhase.IDLE || !VpnConnection.reservePreparation(key)) {
                finish()
                return
            }
            loading = true
            VpnConnection.record(R.string.vpn_quick_preparing)
            main.postDelayed(profileDeadline, PROFILE_TIMEOUT_MS)
            session.syncSavedProfile(applicationContext)
        }
        if (session.busy) return
        main.removeCallbacks(profileDeadline)
        if (!VpnConnection.ownsPreparation(key)) {
            showError(R.string.vpn_quick_interrupted)
            return
        }
        val profile = session.profile
        if (profile == null) {
            showError(session.feedback ?: R.string.vpn_profile_required)
            return
        }
        loading = false
        requested = false
        if (!consent.begin(automatic = false, profileId = profile.documentKey, key = key)) finish()
    }

    private fun startSelected(id: String?) {
        if (VpnConnection.phase != VpnPhase.IDLE) {
            finish()
            return
        }
        val profile = session.profile
        val context = applicationContext
        if (profile == null || profile.documentKey != id || session.savedRevision != context.localProfiles().revision()) {
            showError(R.string.profiles_selection_changed)
            return
        }
        VpnConnection.record(R.string.vpn_permission_granted)
        VpnLaunchSettings(context).setPaused(false)
        VpnConnection.start(context, profile.config, VpnProfileLabels(context).country(profile.documentKey),
            profile.documentKey, profile.name)
        attempt = VpnConnection.attemptId
        message = R.string.vpn_requesting_service
        renderConnection()
    }

    private fun renderConnection() {
        consent.cancelIfInvalid()
        if (isFinishing || failed) return
        val id = attempt
        if (id != null) {
            when {
                !VpnConnection.ownsAttempt(id) -> {
                    attempt = null
                    VpnConnection.currentError?.let { showError(VpnConnection.message) } ?: finish()
                }
                VpnConnection.serviceResponse == R.string.vpn_service_foreground -> finish()
            }
        } else if (resetGeneration != VpnConnection.resetGeneration) {
            showError(R.string.vpn_quick_interrupted)
        }
        renderStatus()
    }

    private fun showConsentStatus(message: Int, error: Boolean) {
        if (error) {
            showError(message)
        } else {
            this.message = message
            VpnConnection.record(message)
            renderStatus()
        }
    }

    private fun showError(message: Int) {
        requested = false
        loading = false
        failed = true
        this.message = message
        main.removeCallbacks(profileDeadline)
        consent.cancel()
        VpnConnection.releasePreparation(key)
        VpnConnection.record(message, VpnError(message))
        renderStatus()
    }

    private fun renderStatus() {
        status.setText(message)
        close.setText(if (failed) android.R.string.ok else android.R.string.cancel)
    }

    private fun cancel() {
        requested = false
        consent.cancel()
        VpnConnection.releasePreparation(key)
        attempt?.let(VpnConnection::stopFromNotification)
        finish()
    }

    internal companion object {
        private const val ACTION_ENABLE = "com.kachur.vpn.ENABLE_VPN"
        private const val PROCESS = "quick_control_process"
        private const val PREPARATION = "quick_control_preparation"
        private const val RESET = "quick_control_reset"
        private const val MESSAGE = "quick_control_message"
        private const val FAILED = "quick_control_failed"
        private const val REQUESTED = "quick_control_requested"
        private const val ATTEMPT = "quick_control_attempt"
        private const val PROFILE_TIMEOUT_MS = 30_000L
        private val processToken = UUID.randomUUID().toString()

        fun intent(context: Context): Intent = Intent(context, VpnQuickActivity::class.java)
            .setAction(ACTION_ENABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(context, 0, intent(context), PendingIntent.FLAG_IMMUTABLE)
    }
}
