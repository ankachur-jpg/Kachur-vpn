package com.kachur.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.util.UUID
import java.util.concurrent.Future

internal class QuickVpnProfile : ViewModel() {
    var selection: SelectedVpnProfile? = null
}

/** A user-invoked window for starting the selected profile, including Android's VPN consent. */
class VpnQuickActivity : ComponentActivity() {
    private val consent by lazy { VpnConsent(this, ::showConsentStatus) { _, id, trace -> startSelected(id, trace) } }
    private lateinit var profile: QuickVpnProfile
    private lateinit var status: TextView
    private lateinit var close: Button
    private val main = Handler(Looper.getMainLooper())
    private val observer: () -> Unit = ::renderConnection
    private var resumed = false
    private var requested = false
    private var load: Future<*>? = null
    private var failed = false
    private var message = R.string.vpn_quick_preparing
    private var key = ""
    private var resetGeneration = VpnConnection.resetGeneration
    private var attempt: Long? = null
    private var startup: VpnStartupTrace? = null
    private var recordedWindow = false
    private val profileDeadline = Runnable { if (requested) showError(R.string.profile_restore_error) }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppAppearance.read(newBase).applyTo(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profile = ViewModelProvider(this)[QuickVpnProfile::class.java]
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
            savedInstanceState == null -> accept(intent)
            savedInstanceState.getString(PROCESS) == processToken -> {
                key = savedInstanceState.getString(PREPARATION).orEmpty()
                resetGeneration = savedInstanceState.getLong(RESET)
                message = savedInstanceState.getInt(MESSAGE, R.string.vpn_quick_preparing)
                failed = savedInstanceState.getBoolean(FAILED)
                requested = savedInstanceState.getBoolean(REQUESTED)
                attempt = savedInstanceState.getLong(ATTEMPT).takeIf { it != 0L }
                startup = VpnStartupTiming.find(savedInstanceState.getString(STARTUP))
                consent.restore(savedInstanceState)
                if (!requested && !consent.busy && attempt == null && !failed) showError(R.string.vpn_quick_interrupted)
            }
            else -> showError(R.string.vpn_quick_interrupted)
        }
        renderStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!failed || isFinishing) return
        accept(intent)
        recordWindowIfResumed()
        renderStatus()
        advance()
    }

    private fun accept(intent: Intent) {
        val window = if (intent.action == ACTION_TILE_WINDOW) VpnTileStart.takeWindow(intent.getStringExtra(STARTUP)) else null
        if (intent.action != ACTION_ENABLE && window == null) {
            finish()
            return
        }
        if (window == null && (VpnConnection.phase != VpnPhase.IDLE || VpnConnection.preparing)) {
            finish()
            return
        }
        startup = window?.startup ?: VpnStartupTiming.begin(VpnStartSource.NOTIFICATION)
        key = checkNotNull(startup).id
        recordedWindow = false
        resetGeneration = VpnConnection.resetGeneration
        failed = false
        requested = true
        message = R.string.vpn_quick_preparing
        profile.selection = null
        window?.error?.let(::showError)
    }

    override fun onStart() {
        super.onStart()
        VpnConnection.observe(observer)
        renderConnection()
    }

    override fun onPostResume() {
        super.onPostResume()
        resumed = true
        recordWindowIfResumed()
        consent.resume()
        advance()
    }

    private fun recordWindowIfResumed() {
        if (resumed && !recordedWindow) {
            recordedWindow = true
            startup?.mark(VpnStartStage.WINDOW_RESUMED)
        }
    }

    override fun onPause() {
        resumed = false
        consent.pause()
        super.onPause()
    }

    override fun onStop() {
        VpnConnection.removeObserver(observer)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(PROCESS, processToken)
        outState.putString(PREPARATION, key)
        outState.putString(STARTUP, startup?.id)
        outState.putLong(RESET, resetGeneration)
        outState.putInt(MESSAGE, message)
        outState.putBoolean(FAILED, failed)
        outState.putBoolean(REQUESTED, requested || (consent.busy && !consent.dialogLaunched))
        outState.putLong(ATTEMPT, attempt ?: 0L)
        consent.save(outState)
    }

    override fun onDestroy() {
        cancelLoad()
        if (!isChangingConfigurations && requested) startup?.mark(VpnStartStage.ENDED)
        consent.cancel(preserveTrace = isChangingConfigurations)
        VpnConnection.releasePreparation(key)
        super.onDestroy()
    }

    private fun advance() {
        if (!resumed || !requested || isFinishing) return
        if (resetGeneration != VpnConnection.resetGeneration) {
            showError(R.string.vpn_quick_interrupted)
            return
        }
        if (VpnConnection.phase != VpnPhase.IDLE || !VpnConnection.reservePreparation(key)) {
            requested = false
            startup?.mark(VpnStartStage.ENDED)
            finish()
            return
        }

        val selection = profile.selection
        if (selection == null) {
            if (load == null) readProfile()
            return
        }
        val chosen = selection.profile
        if (chosen == null) {
            showError(R.string.vpn_profile_required)
            return
        }
        requested = false
        if (!consent.begin(automatic = false, profileId = chosen.documentKey, key = key, startup = startup)) {
            startup?.mark(VpnStartStage.ENDED)
            finish()
        }
    }

    private fun readProfile() {
        val context = applicationContext
        val owner = key
        val trace = startup
        VpnConnection.record(R.string.vpn_quick_preparing)
        main.postDelayed(profileDeadline, PROFILE_TIMEOUT_MS)
        load = VpnConnection.systemCalls.submit {
            trace?.mark(VpnStartStage.PROFILE_LOOKUP)
            val read = try {
                context.localProfiles().readSelected()
            } catch (_: Exception) {
                null
            }
            if (read != null) trace?.mark(if (read.fromCache) VpnStartStage.PROFILE_CACHED else VpnStartStage.PROFILE_READ)
            main.post {
                if (isDestroyed || isFinishing || !requested || key != owner || !VpnConnection.ownsPreparation(owner)) return@post
                cancelLoad()
                if (read == null) {
                    showError(R.string.profile_restore_error)
                } else {
                    profile.selection = read.selected
                    advance()
                }
            }
        }
    }

    private fun startSelected(id: String?, trace: VpnStartupTrace?) {
        if (VpnConnection.phase != VpnPhase.IDLE) {
            trace?.mark(VpnStartStage.ENDED)
            finish()
            return
        }
        val selected = profile.selection
        val chosen = selected?.profile
        val context = applicationContext
        if (selected == null || chosen == null || chosen.documentKey != id || selected.revision != context.localProfiles().revision()) {
            showError(R.string.profiles_selection_changed)
            return
        }
        VpnConnection.record(R.string.vpn_permission_granted)
        VpnLaunchSettings(context).setPaused(false)
        VpnConnection.start(context, chosen.config, VpnProfileLabels(context).country(chosen.documentKey),
            chosen.documentKey, chosen.name, startup = trace)
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
        failed = true
        this.message = message
        startup?.mark(VpnStartStage.ENDED)
        cancelLoad()
        consent.cancel()
        VpnConnection.releasePreparation(key)
        VpnConnection.record(message, VpnError(message))
        renderStatus()
    }

    private fun renderStatus() {
        status.setText(message)
        close.setText(if (failed) android.R.string.ok else android.R.string.cancel)
    }

    private fun cancelLoad() {
        load?.cancel(true)
        load = null
        main.removeCallbacks(profileDeadline)
    }

    private fun cancel() {
        if (requested) startup?.mark(VpnStartStage.ENDED)
        requested = false
        cancelLoad()
        consent.cancel()
        VpnConnection.releasePreparation(key)
        attempt?.let(VpnConnection::stopFromNotification)
        finish()
    }

    internal companion object {
        private const val ACTION_ENABLE = "com.kachur.vpn.ENABLE_VPN"
        private const val ACTION_TILE_WINDOW = "com.kachur.vpn.VPN_TILE_WINDOW"
        private const val PROCESS = "quick_control_process"
        private const val PREPARATION = "quick_control_preparation"
        private const val STARTUP = "quick_control_startup"
        private const val RESET = "quick_control_reset"
        private const val MESSAGE = "quick_control_message"
        private const val FAILED = "quick_control_failed"
        private const val REQUESTED = "quick_control_requested"
        private const val ATTEMPT = "quick_control_attempt"
        private const val PROFILE_TIMEOUT_MS = 30_000L
        private val processToken = UUID.randomUUID().toString()

        fun intent(context: Context): Intent = Intent(context, VpnQuickActivity::class.java)
            .setAction(ACTION_ENABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun tileIntent(context: Context, key: String): Intent = intent(context).setAction(ACTION_TILE_WINDOW)
            .setData(Uri.fromParts("kachur-vpn", key, null)).putExtra(STARTUP, key)

        fun tilePendingIntent(context: Context, key: String): PendingIntent =
            PendingIntent.getActivity(context, 0, tileIntent(context, key),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)

        fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(context, 0, intent(context), PendingIntent.FLAG_IMMUTABLE)
    }
}
