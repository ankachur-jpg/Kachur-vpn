package com.kachur.vpn

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import java.util.UUID
import java.util.concurrent.Future

class MainActivity : ComponentActivity() {
    private lateinit var session: ImportSession
    private lateinit var profileName: TextView
    private lateinit var profileCountry: Button
    private lateinit var autoConnect: Switch
    private lateinit var notificationAccess: Button
    private lateinit var diagnostics: TextView
    private lateinit var feedback: TextView
    private lateinit var importButton: Button
    private lateinit var connectButton: Button
    private lateinit var reconnectButton: Button
    private lateinit var connectionStatus: TextView
    private lateinit var connectionDiagnostics: TextView
    private val vpnObserver: () -> Unit = ::renderConnection
    private val splitSettings by lazy { SplitTunnelingSettings(applicationContext) }
    private val downloadsObserver by lazy { splitSettings.onDownloadsChanged(::renderConnection) }
    private val main = Handler(Looper.getMainLooper())
    private var resumed = false
    private var permissionRequest: PermissionRequest? = null
    private val permissionPending: Boolean
        get() = permissionRequest?.let { it.dialogLaunched && !it.resultReceived } == true
    private var permissionMessage: Int? = null
    private var manualStopSeen = VpnConnection.manualStopGeneration
    private val launchSettings by lazy { VpnLaunchSettings(applicationContext) }
    private val labels by lazy { VpnProfileLabels(applicationContext) }
    private val notificationSettings by lazy { getSharedPreferences("vpn_notifications", MODE_PRIVATE) }

    private class PermissionRequest(
        val key: String = "vpn-permission-${UUID.randomUUID()}",
        val resetGeneration: Long = VpnConnection.resetGeneration,
        val automatic: Boolean = false,
    ) {
        var dialogLaunched = false
        var resultReceived = false
        var launcher: ActivityResultLauncher<Intent>? = null
        var check: Future<*>? = null
        var deadline: Runnable? = null
        var onResume: (() -> Unit)? = null
    }

    private val openConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        session.record(R.string.import_result_received)
        when (result.resultCode) {
            Activity.RESULT_CANCELED -> session.finish(R.string.import_cancelled)
            Activity.RESULT_OK -> {
                val data = result.data
                val uri = data?.data ?: data?.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
                when {
                    uri == null -> session.finish(R.string.no_document_returned)
                    uri.scheme != ContentResolver.SCHEME_CONTENT ->
                        session.finish(R.string.unsupported_document_uri)
                    else -> session.load(applicationContext, uri, getString(R.string.unnamed_profile))
                }
            }
            else -> session.finish(R.string.unexpected_picker_result)
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        renderNotificationAccess()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = ViewModelProvider(this)[ImportSession::class.java]
        permissionMessage = savedInstanceState?.getInt(PERMISSION_MESSAGE, 0)?.takeIf { it != 0 }
        val savedPermissionKey = savedInstanceState?.getString(PERMISSION_REQUEST_KEY)
        if (savedInstanceState != null && savedPermissionKey != null &&
            savedInstanceState.getLong(PERMISSION_RESET_GENERATION) == VpnConnection.resetGeneration
        ) {
            permissionRequest = PermissionRequest(savedPermissionKey, automatic = savedInstanceState.getBoolean(PERMISSION_AUTOMATIC)).apply {
                dialogLaunched = true
                resultReceived = savedInstanceState.getBoolean(PERMISSION_RESULT_RECEIVED)
            }
        } else if (savedInstanceState?.getBoolean(PERMISSION_PENDING) == true) {
            // An instance saved by an older build has no per-request registration to restore.
            permissionMessage = R.string.vpn_permission_no_result
            VpnConnection.record(R.string.vpn_permission_no_result, VpnError(R.string.vpn_permission_no_result))
        }

        if (!session.busy && savedInstanceState != null) {
            when {
                savedInstanceState.getBoolean(IMPORT_IN_PROGRESS) -> session.finish(R.string.import_interrupted)
                savedInstanceState.getBoolean(PICKER_IN_PROGRESS) -> session.startPicker()
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 24, 35))
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(
                    insets.systemWindowInsetLeft + dp(12), insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight + dp(12), insets.systemWindowInsetBottom,
                )
                insets
            }
        }
        connectionStatus = label("", 18f, Color.WHITE).apply {
            id = R.id.connection_status
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(connectionStatus)
        connectButton = Button(this).apply {
            id = R.id.connect_vpn_button
            setOnClickListener { toggleVpn() }
        }
        root.addView(connectButton)
        root.addView(Button(this).apply {
            id = R.id.reset_vpn_button
            setText(R.string.vpn_reset_connection)
            setOnClickListener {
                permissionRequest?.let(::clearPermissionRequest)
                permissionMessage = null
                launchSettings.setPaused(true)
                VpnConnection.reset()
                renderConnection()
            }
        })
        connectionDiagnostics = TextView(this).apply {
            id = R.id.connection_diagnostics
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(31, 41, 55))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            maxHeight = (resources.displayMetrics.heightPixels * 0.42f).toInt()
            movementMethod = ScrollingMovementMethod.getInstance()
        }
        // The VPN controls and diagnostics stay on screen when the import history scrolls.
        root.addView(connectionDiagnostics)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(12))
        }
        layout.addView(label(getString(R.string.app_name), 30f, Color.WHITE))
        profileName = label("", 22f, Color.WHITE).apply { id = R.id.profile_name }
        layout.addView(profileName)
        profileCountry = Button(this).apply {
            id = R.id.vpn_profile_country
            setOnClickListener { editCountry() }
        }
        layout.addView(profileCountry)
        autoConnect = Switch(this).apply {
            id = R.id.vpn_auto_connect
            setOnCheckedChangeListener { _, checked ->
                if (launchSettings.enabled != checked) {
                    launchSettings.setEnabled(checked)
                    if (checked) {
                        session.openChecked = false
                        maybeAutoConnect()
                    } else if (permissionRequest?.automatic == true) {
                        permissionRequest?.let(::clearPermissionRequest)
                        permissionMessage = null
                    }
                    renderConnection()
                }
            }
        }
        layout.addView(autoConnect)
        notificationAccess = Button(this).apply {
            id = R.id.vpn_notification_access
            setText(R.string.vpn_notification_access)
            setOnClickListener { openNotificationAccess() }
        }
        layout.addView(notificationAccess)
        layout.addView(Button(this).apply {
            id = R.id.split_open_button
            setText(R.string.split_title)
            setOnClickListener { startActivity(Intent(this@MainActivity, SplitTunnelingActivity::class.java)) }
        })
        layout.addView(Button(this).apply {
            id = R.id.backup_open_screen
            setText(R.string.backup_title)
            setOnClickListener { startActivity(Intent(this@MainActivity, BackupActivity::class.java)) }
        })
        layout.addView(Button(this).apply {
            id = R.id.diag_open_screen
            setText(R.string.diag_title)
            setOnClickListener { startActivity(Intent(this@MainActivity, DiagnosticsActivity::class.java)) }
        })
        reconnectButton = Button(this).apply {
            id = R.id.main_split_reconnect_button
            setText(R.string.split_apply_reconnect)
            setOnClickListener { VpnConnection.reconnectWithSavedExclusions(applicationContext) }
        }
        layout.addView(reconnectButton)
        diagnostics = label("", 15f, Color.LTGRAY).apply { id = R.id.import_diagnostics }
        layout.addView(diagnostics)
        feedback = label("", 16f, Color.LTGRAY).apply {
            id = R.id.import_feedback
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        layout.addView(feedback)
        importButton = Button(this).apply {
            id = R.id.import_profile_button
            setText(R.string.import_profile)
            setOnClickListener { selectConfig() }
        }
        layout.addView(importButton)
        root.addView(ScrollView(this).apply {
            addView(layout)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        session.onChange = { render(); maybeAutoConnect() }
        render()
        renderNotificationAccess()
        permissionRequest?.let { request ->
            armPermissionDeadline(request, PERMISSION_RESULT_TIMEOUT_MS)
            if (permissionPending) {
                showPermissionMessage(R.string.vpn_waiting_permission)
                registerPermissionResult(request)
            } else {
                showPermissionMessage(R.string.vpn_permission_check_return)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        splitSettings.preferences.registerOnSharedPreferenceChangeListener(downloadsObserver)
        VpnConnection.observe(vpnObserver)
        session.syncSavedProfile(applicationContext)
        render()
    }

    override fun onStop() {
        splitSettings.preferences.unregisterOnSharedPreferenceChangeListener(downloadsObserver)
        VpnConnection.removeObserver(vpnObserver)
        super.onStop()
    }

    override fun onPostResume() {
        super.onPostResume()
        resumed = true
        permissionRequest?.takeIf(::isCurrentRequest)?.let { request ->
            val action = request.onResume
            request.onResume = null
            if (action != null) {
                action()
            } else if (request.dialogLaunched && request.check == null) {
                checkPermission(request, allowDialog = false)
            }
        }
        if (session.picking) {
            // Registry results are delivered by this point when Android returns from the picker.
            session.finish(R.string.picker_no_callback)
        }
        renderConnection()
        renderNotificationAccess()
        maybeAutoConnect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) {
            session.openChecked = false
            maybeAutoConnect()
        }
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // The picker callback is restored by ActivityResultRegistry. A background read cannot be restored.
        outState.putBoolean(IMPORT_IN_PROGRESS, session.reading)
        outState.putBoolean(PICKER_IN_PROGRESS, session.picking)
        permissionRequest?.takeIf { it.dialogLaunched }?.let {
            outState.putString(PERMISSION_REQUEST_KEY, it.key)
            outState.putBoolean(PERMISSION_RESULT_RECEIVED, it.resultReceived)
            outState.putLong(PERMISSION_RESET_GENERATION, it.resetGeneration)
            outState.putBoolean(PERMISSION_AUTOMATIC, it.automatic)
        }
        if (permissionRequest == null) permissionMessage?.let { outState.putInt(PERMISSION_MESSAGE, it) }
    }

    override fun onDestroy() {
        permissionRequest?.let(::clearPermissionRequest)
        session.onChange = null
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(0, 12, 0, 12)
    }

    private fun selectConfig() {
        if (session.busy) return
        permissionMessage = null
        session.startPicker()
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // Providers use different MIME types for .conf. Validate the contents after selection.
            type = "*/*"
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            openConfig.launch(picker)
        } catch (_: ActivityNotFoundException) {
            session.finish(R.string.picker_unavailable)
        } catch (_: SecurityException) {
            session.finish(R.string.picker_unavailable)
        }
    }

    private fun render() {
        val profile = session.profile
        profileName.text = profile?.name ?: getString(R.string.no_profile)
        profileCountry.visibility = if (profile == null) View.GONE else View.VISIBLE
        if (profile != null) profileCountry.text = getString(R.string.vpn_country_label, labels.country(profile.documentKey))
        diagnostics.text = session.stages.joinToString("\n") { getString(it) }
        diagnostics.visibility = if (session.stages.isEmpty()) View.GONE else View.VISIBLE
        val message = session.feedback
        val errorLine = session.errorLine
        feedback.text = when {
            message == null -> ""
            errorLine != null -> getString(message, errorLine)
            else -> getString(message)
        }
        feedback.visibility = if (feedback.text.isEmpty()) View.GONE else View.VISIBLE
        renderConnection()
    }

    private fun renderConnection() {
        if (manualStopSeen != VpnConnection.manualStopGeneration) {
            manualStopSeen = VpnConnection.manualStopGeneration
            permissionRequest?.let(::clearPermissionRequest)
            permissionMessage = null
        }
        permissionRequest?.takeIf { it.resetGeneration != VpnConnection.resetGeneration }?.let {
            clearPermissionRequest(it)
            permissionMessage = null
        }
        val phase = VpnConnection.phase
        autoConnect.isChecked = launchSettings.enabled
        autoConnect.setText(if (launchSettings.enabled && launchSettings.paused) R.string.vpn_auto_paused else R.string.vpn_auto_connect)
        val blocked = connectionButtonBlockReason()
        importButton.isEnabled = !session.busy && permissionRequest == null && phase == VpnPhase.IDLE
        profileCountry.isEnabled = importButton.isEnabled
        connectButton.isEnabled = blocked == null
        reconnectButton.isEnabled = !VpnConnection.checkingRoutes
        reconnectButton.visibility = if (VpnConnection.routingNeedsReconnect(applicationContext)) {
            View.VISIBLE
        } else {
            View.GONE
        }
        connectButton.setText(when (phase) {
            VpnPhase.STARTING -> R.string.vpn_cancel_start
            VpnPhase.WAITING, VpnPhase.HANDSHAKE, VpnPhase.RECOVERING -> R.string.vpn_disconnect
            else -> R.string.connect_vpn
        })
        connectionStatus.setText(if (phase == VpnPhase.IDLE) permissionMessage ?: VpnConnection.message else VpnConnection.message)
        connectionDiagnostics.text = getString(
            R.string.vpn_diagnostic_summary,
            VpnConnection.eventNumber,
            getString(VpnConnection.lastEvent),
            getString(phase.label),
            permissionPending.toString(),
            getString(blocked ?: R.string.vpn_button_available),
            getString(VpnConnection.serviceLaunch),
            getString(VpnConnection.serviceResponse),
            VpnConnection.lastError?.display(this) ?: getString(R.string.vpn_no_error),
        )
    }

    private fun editCountry() {
        val profile = session.profile ?: return
        val input = EditText(this).apply {
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(VpnProfileLabels.MAX_LENGTH))
            setText(labels.country(profile.documentKey))
            selectAll()
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.vpn_country_title).setView(input)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.vpn_country_save, null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (labels.saveCountry(profile.documentKey, input.text.toString())) {
                dialog.dismiss()
                render()
            } else {
                input.error = getString(R.string.vpn_country_invalid)
            }
        }
    }

    private fun renderNotificationAccess() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channelBlocked = manager.getNotificationChannel(VpnNotifications.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE
        val enabled = manager.areNotificationsEnabled() && !channelBlocked
        val becameEnabled = enabled && notificationAccess.visibility == View.VISIBLE
        notificationAccess.visibility = if (enabled) View.GONE else View.VISIBLE
        if (becameEnabled) VpnConnection.refreshNotification()
    }

    private fun openNotificationAccess() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                (!notificationSettings.getBoolean(NOTIFICATION_ASKED, false) ||
                    shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
            ) {
                notificationSettings.edit().putBoolean(NOTIFICATION_ASKED, true).apply()
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
            val manager = getSystemService(NotificationManager::class.java)
            val channelBlocked = manager?.areNotificationsEnabled() == true &&
                manager.getNotificationChannel(VpnNotifications.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE
            val intent = if (channelBlocked) Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_CHANNEL_ID, VpnNotifications.CHANNEL) else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            startActivity(intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } catch (_: RuntimeException) {
            Toast.makeText(this, R.string.vpn_notification_settings_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun connectionButtonBlockReason(): Int? = when {
        session.picking -> R.string.vpn_blocked_import_picker
        session.reading -> R.string.vpn_blocked_import_read
        session.restoring -> R.string.vpn_blocked_profile_restore
        permissionPending -> R.string.vpn_blocked_permission_result
        permissionRequest != null -> R.string.vpn_blocked_permission_check
        VpnConnection.phase == VpnPhase.STOPPING -> R.string.vpn_blocked_stopping
        else -> null
    }

    private fun toggleVpn() {
        if (connectionButtonBlockReason() != null) return
        permissionMessage = null
        VpnConnection.record(R.string.vpn_connect_pressed)
        if (VpnConnection.phase != VpnPhase.IDLE) {
            VpnConnection.stop()
            return
        }
        if (session.profile == null) {
            showPermissionMessage(R.string.vpn_profile_required, error = true)
            return
        }
        session.openChecked = true
        requestConnection(automatic = false)
    }

    private fun maybeAutoConnect() {
        if (!resumed || session.openChecked || session.busy) return
        session.openChecked = true
        if (!launchSettings.enabled || launchSettings.paused || permissionRequest != null ||
            session.profile == null || VpnConnection.phase != VpnPhase.IDLE
        ) return
        requestConnection(automatic = true)
    }

    private fun requestConnection(automatic: Boolean) {
        val request = PermissionRequest(automatic = automatic)
        permissionRequest = request
        armPermissionDeadline(request, PERMISSION_CHECK_TIMEOUT_MS)
        checkPermission(request, allowDialog = true)
    }

    private fun checkPermission(request: PermissionRequest, allowDialog: Boolean) {
        showPermissionMessage(if (allowDialog) R.string.vpn_permission_check else R.string.vpn_permission_check_return)
        val context = applicationContext
        request.check = VpnConnection.systemCalls.submit {
            try {
                if (request.automatic && allowDialog && existingSystemVpn(context)) {
                    main.post { finishPermissionRequest(request, R.string.vpn_auto_other_vpn, error = false) }
                    return@submit
                }
                val consent = VpnService.prepare(context)
                main.post {
                    if (!isCurrentRequest(request)) return@post
                    request.check = null
                    when {
                        consent == null -> runWhenResumed(request) { startWithPermission(request) }
                        allowDialog -> runWhenResumed(request) { launchPermissionDialog(request, consent) }
                        else -> finishPermissionRequest(request, R.string.vpn_permission_no_result)
                    }
                }
            } catch (_: RuntimeException) {
                main.post { finishPermissionRequest(request, R.string.vpn_permission_unavailable) }
            }
        }
    }

    private fun launchPermissionDialog(request: PermissionRequest, consent: Intent) {
        request.dialogLaunched = true
        armPermissionDeadline(request, PERMISSION_RESULT_TIMEOUT_MS)
        showPermissionMessage(R.string.vpn_waiting_permission)
        try {
            registerPermissionResult(request)?.launch(consent)
        } catch (_: RuntimeException) {
            finishPermissionRequest(request, R.string.vpn_permission_unavailable)
        }
    }

    private fun registerPermissionResult(request: PermissionRequest): ActivityResultLauncher<Intent>? {
        // A fresh registry key keeps a result from a cancelled dialog out of a later attempt.
        val launcher = activityResultRegistry.register(request.key, ActivityResultContracts.StartActivityForResult()) { result ->
            if (!isCurrentRequest(request) || request.resultReceived) return@register
            request.resultReceived = true
            when (result.resultCode) {
                Activity.RESULT_OK -> {
                    showPermissionMessage(R.string.vpn_permission_result_ok)
                    if (request.check == null) {
                        runWhenResumed(request) { checkPermission(request, allowDialog = false) }
                    }
                }
                Activity.RESULT_CANCELED -> finishPermissionRequest(request, R.string.vpn_permission_denied)
                else -> finishPermissionRequest(request, R.string.vpn_permission_unexpected_result)
            }
        }
        // register() can immediately deliver a result restored by the registry.
        if (!isCurrentRequest(request)) {
            launcher.unregister()
            return null
        }
        request.launcher = launcher
        return launcher
    }

    private fun startWithPermission(request: PermissionRequest) {
        clearPermissionRequest(request)
        permissionMessage = null
        if (VpnConnection.phase != VpnPhase.IDLE || (request.automatic && (!launchSettings.enabled || launchSettings.paused))) {
            renderConnection()
            return
        }
        VpnConnection.record(R.string.vpn_permission_granted)
        val profile = session.profile
        if (profile == null) {
            showPermissionMessage(R.string.vpn_profile_required, error = true)
        } else {
            if (!request.automatic) launchSettings.setPaused(false)
            VpnConnection.start(applicationContext, profile.config, labels.country(profile.documentKey))
        }
        renderConnection()
    }

    private fun runWhenResumed(request: PermissionRequest, action: () -> Unit) {
        if (resumed) action() else request.onResume = action
    }

    private fun armPermissionDeadline(request: PermissionRequest, timeout: Long) {
        request.deadline?.let(main::removeCallbacks)
        val deadline = Runnable {
            val error = if (permissionPending) R.string.vpn_permission_result_timeout else R.string.vpn_permission_check_timeout
            finishPermissionRequest(request, error)
        }
        request.deadline = deadline
        main.postDelayed(deadline, timeout)
    }

    private fun finishPermissionRequest(request: PermissionRequest, message: Int, error: Boolean = true) {
        if (!isCurrentRequest(request)) return
        clearPermissionRequest(request)
        showPermissionMessage(message, error)
    }

    private fun isCurrentRequest(request: PermissionRequest): Boolean =
        permissionRequest === request && request.resetGeneration == VpnConnection.resetGeneration

    private fun clearPermissionRequest(request: PermissionRequest) {
        permissionRequest = null
        request.onResume = null
        request.check?.cancel(true)
        request.deadline?.let(main::removeCallbacks)
        request.launcher?.unregister()
    }

    private fun showPermissionMessage(message: Int, error: Boolean = false) {
        permissionMessage = message
        VpnConnection.record(message, if (error) VpnError(message) else null)
        renderConnection()
    }

    private fun existingSystemVpn(context: android.content.Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    private companion object {
        const val PERMISSION_PENDING = "vpn_permission_pending"
        const val PERMISSION_REQUEST_KEY = "vpn_permission_request_key"
        const val PERMISSION_RESET_GENERATION = "vpn_permission_reset_generation"
        const val PERMISSION_RESULT_RECEIVED = "vpn_permission_result_received"
        const val PERMISSION_AUTOMATIC = "vpn_permission_automatic"
        const val PERMISSION_MESSAGE = "vpn_permission_message"
        const val PERMISSION_CHECK_TIMEOUT_MS = 30_000L
        const val PERMISSION_RESULT_TIMEOUT_MS = 60_000L
        const val PICKER_IN_PROGRESS = "config_picker_in_progress"
        const val IMPORT_IN_PROGRESS = "config_import_in_progress"
        const val NOTIFICATION_ASKED = "post_notifications_asked"
    }
}
