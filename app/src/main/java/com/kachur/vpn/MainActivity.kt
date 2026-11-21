package com.kachur.vpn

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.lifecycle.ViewModelProvider
import java.util.UUID
import java.util.concurrent.Future

class MainActivity : KachurActivity() {
    private lateinit var session: ImportSession
    private lateinit var profileName: TextView
    private lateinit var activeProfile: TextView
    private lateinit var profileCountry: Button
    private lateinit var autoConnect: Switch
    private lateinit var autoRecovery: Switch
    private lateinit var notificationAccess: Button
    private lateinit var diagnostics: TextView
    private lateinit var feedback: TextView
    private lateinit var importButton: Button
    private lateinit var connectButton: Button
    private lateinit var reconnectButton: Button
    private lateinit var connectionStatus: TextView
    private lateinit var connectionDiagnostics: TextView
    private lateinit var received: TextView
    private lateinit var sent: TextView
    private lateinit var home: ScrollView
    private lateinit var settingsPage: ScrollView
    private lateinit var navigation: LinearLayout
    private var detailsOpen = false
    private var statusColor = 0
    private val backFromSettings = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showSettings(false)
    }
    private val trafficObserver: () -> Unit = ::renderTraffic
    private val vpnObserver: () -> Unit = ::renderConnection
    private val splitSettings by lazy { SplitTunnelingSettings(applicationContext) }
    private val downloadsObserver by lazy { splitSettings.onDownloadsChanged(::renderConnection) }
    private val main = Handler(Looper.getMainLooper())
    private var resumed = false
    private var permissionRequest: PermissionRequest? = null
    private var pendingProfileConnection: ProfileConnectionRequest? = null
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
        val profileId: String? = null,
    ) {
        var dialogLaunched = false
        var resultReceived = false
        var launcher: ActivityResultLauncher<Intent>? = null
        var check: Future<*>? = null
        var deadline: Runnable? = null
        var onResume: (() -> Unit)? = null
    }

    private val openConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        session.handlePickerResult(applicationContext, result.resultCode, result.data)
    }

    private val openProfiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val id = data.getStringExtra(ProfilesActivity.PROFILE_ID) ?: return@registerForActivityResult
            val generation = data.getLongExtra(ProfilesActivity.STOP_GENERATION, -1L)
            pendingProfileConnection = ProfileConnectionRequest(id, generation)
            session.openChecked = true
            session.syncSavedProfile(applicationContext)
            connectSelectedProfile()
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        renderNotificationAccess()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = ViewModelProvider(this)[ImportSession::class.java]
        pendingProfileConnection = savedInstanceState?.let { state ->
            state.getString(ProfilesActivity.PROFILE_ID)?.let {
                ProfileConnectionRequest(it, state.getLong(ProfilesActivity.STOP_GENERATION, -1L))
            }
        }
        permissionMessage = savedInstanceState?.getInt(PERMISSION_MESSAGE, 0)?.takeIf { it != 0 }
        val savedPermissionKey = savedInstanceState?.getString(PERMISSION_REQUEST_KEY)
        if (savedInstanceState != null && savedPermissionKey != null &&
            savedInstanceState.getLong(PERMISSION_RESET_GENERATION) == VpnConnection.resetGeneration
        ) {
            permissionRequest = PermissionRequest(savedPermissionKey, automatic = savedInstanceState.getBoolean(PERMISSION_AUTOMATIC),
                profileId = savedInstanceState.getString(PERMISSION_PROFILE)).apply {
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

        buildInterface(savedInstanceState)


        session.onChange = {
            render()
            connectSelectedProfile()
            maybeAutoConnect()
        }
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
        VpnTrafficUi.observe(trafficObserver)
        session.syncSavedProfile(applicationContext)
        render()
    }

    override fun onStop() {
        splitSettings.preferences.unregisterOnSharedPreferenceChangeListener(downloadsObserver)
        VpnConnection.removeObserver(vpnObserver)
        VpnTrafficUi.removeObserver(trafficObserver)
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
        connectSelectedProfile()
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
        outState.putBoolean("settings_open", backFromSettings.isEnabled)
        outState.putBoolean("connection_details_open", detailsOpen)
        // The picker callback is restored by ActivityResultRegistry. A background read cannot be restored.
        outState.putBoolean(IMPORT_IN_PROGRESS, session.reading)
        outState.putBoolean(PICKER_IN_PROGRESS, session.picking)
        permissionRequest?.takeIf { it.dialogLaunched }?.let {
            outState.putString(PERMISSION_REQUEST_KEY, it.key)
            outState.putBoolean(PERMISSION_RESULT_RECEIVED, it.resultReceived)
            outState.putLong(PERMISSION_RESET_GENERATION, it.resetGeneration)
            outState.putBoolean(PERMISSION_AUTOMATIC, it.automatic)
            outState.putString(PERMISSION_PROFILE, it.profileId)
        }
        pendingProfileConnection?.let {
            outState.putString(ProfilesActivity.PROFILE_ID, it.id)
            outState.putLong(ProfilesActivity.STOP_GENERATION, it.stopGeneration)
        }
        if (permissionRequest == null) permissionMessage?.let { outState.putInt(PERMISSION_MESSAGE, it) }
    }

    override fun onDestroy() {
        permissionRequest?.let(::clearPermissionRequest)
        session.onChange = null
        super.onDestroy()
    }

    private fun buildInterface(savedState: Bundle?) {
        detailsOpen = savedState?.getBoolean("connection_details_open") == true
        home = uiScroll(buildHome()).apply { ViewCompat.setAccessibilityPaneTitle(this, getString(R.string.app_name)) }
        settingsPage = uiScroll(buildSettings()).apply {
            id = R.id.ui_settings_page
            ViewCompat.setAccessibilityPaneTitle(this, getString(R.string.ui_settings))
        }
        navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = uiShape(R.color.ui_surface, 24, R.color.ui_border)
            addNavigation(R.string.ui_servers, R.drawable.ic_ui_servers, R.id.profiles_open) {
                if (!session.busy && permissionRequest == null) {
                    openProfiles.launch(Intent(this@MainActivity, ProfilesActivity::class.java))
                }
            }
            addNavigation(R.string.ui_diagnostics, R.drawable.ic_ui_chart, R.id.diag_open_screen) {
                startActivity(Intent(this@MainActivity, DiagnosticsActivity::class.java))
            }
            addNavigation(R.string.ui_settings, R.drawable.ic_ui_settings, R.id.ui_open_settings) { showSettings(true) }
        }
        val pages = FrameLayout(this).apply {
            addView(home, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(settingsPage, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        val root = uiColumn().apply {
            setBackgroundColor(getColor(R.color.ui_background))
            addView(pages, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(navigation, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
                bottomMargin = dp(8)
            })
            uiInsets()
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, backFromSettings)
        showSettings(savedState?.getBoolean("settings_open") == true)
    }

    private fun buildHome(): View = uiColumn(18).apply {
        uiAdd(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.mipmap.ic_launcher)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(uiHeading(R.string.app_name, 29), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            })
        })
        uiAdd(uiColumn(20).apply {
            background = uiGradient(R.color.ui_hero_start, R.color.ui_hero_end, 28)
            gravity = Gravity.CENTER_HORIZONTAL
            connectionStatus = uiLabel(22, bold = true).apply {
                id = R.id.connection_status
                gravity = Gravity.CENTER
                setPaddingRelative(dp(14), dp(7), dp(14), dp(7))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            addView(connectionStatus, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            connectButton = uiButton(R.string.connect_vpn, primary = true, icon = R.drawable.ic_ui_power).apply {
                id = R.id.connect_vpn_button
                minHeight = dp(76)
                textSize = 19f
                setOnClickListener { toggleVpn() }
            }
            uiAdd(connectButton, 20)
        }, 16)
        uiAdd(uiCard().apply {
            uiAdd(uiCaption(R.string.ui_selected_server))
            profileName = uiLabel(22, bold = true).apply { id = R.id.profile_name }
            uiAdd(profileName, 3)
            activeProfile = uiLabel(15, R.color.ui_muted)
            uiAdd(activeProfile)
            profileCountry = uiButton(0).apply {
                id = R.id.vpn_profile_country
                setOnClickListener { editCountry() }
            }
            uiAdd(profileCountry, 8)
            importButton = uiButton(R.string.import_profile).apply {
                id = R.id.import_profile_button
                setOnClickListener { selectConfig() }
            }
            uiAdd(importButton, 8)
            feedback = uiLabel(16).apply {
                id = R.id.import_feedback
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            uiAdd(feedback, 8)
            diagnostics = uiLabel(14, R.color.ui_muted).apply { id = R.id.import_diagnostics }
            uiAdd(diagnostics, 6)
        }, 14)
        uiAdd(uiHeading(R.string.ui_traffic, 19), 18)
        received = uiLabel(20, bold = true).apply { id = R.id.ui_traffic_received }
        sent = uiLabel(20, bold = true).apply { id = R.id.ui_traffic_sent }
        uiAdd(uiPair(trafficTile(R.string.ui_received, received), trafficTile(R.string.ui_sent, sent)), 8)
        reconnectButton = uiButton(R.string.split_apply_reconnect).apply {
            id = R.id.main_split_reconnect_button
            setOnClickListener { VpnConnection.reconnectWithSavedExclusions(applicationContext) }
        }
        uiAdd(reconnectButton, 14)
        val details = uiCard().apply {
            visibility = if (detailsOpen) View.VISIBLE else View.GONE
            connectionDiagnostics = uiLabel(14).apply {
                id = R.id.connection_diagnostics
                setTextIsSelectable(true)
            }
            uiAdd(connectionDiagnostics)
            uiAdd(uiButton(R.string.vpn_reset_connection).apply {
                id = R.id.reset_vpn_button
                setOnClickListener {
                    permissionRequest?.let(::clearPermissionRequest)
                    pendingProfileConnection = null
                    permissionMessage = null
                    launchSettings.setPaused(true)
                    VpnConnection.reset()
                    renderConnection()
                }
            }, 8)
        }
        uiAdd(uiButton(if (detailsOpen) R.string.ui_hide_details else R.string.ui_details).apply {
            id = R.id.ui_connection_details
            setOnClickListener {
                detailsOpen = !detailsOpen
                details.visibility = if (detailsOpen) View.VISIBLE else View.GONE
                setText(if (detailsOpen) R.string.ui_hide_details else R.string.ui_details)
            }
        }, 14)
        uiAdd(details, 8)
    }

    private fun buildSettings(): View = uiColumn(18).apply {
        uiAdd(uiHeader(R.string.ui_settings) { showSettings(false) })
        uiAdd(uiCard().apply {
            uiAdd(uiHeading(R.string.ui_appearance))
            uiAdd(uiButton(0).apply {
                id = R.id.ui_theme_choice
                text = getString(R.string.ui_theme_value, getString(AppAppearance.read(context).label))
                setOnClickListener { chooseTheme() }
            }, 10)
        }, 16)
        uiAdd(uiCard().apply {
            uiAdd(uiHeading(R.string.ui_connection))
            autoConnect = Switch(this@MainActivity).apply {
                id = R.id.vpn_auto_connect
                uiSwitch()
                isSaveEnabled = false
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
            uiAdd(autoConnect, 8)
            autoRecovery = Switch(this@MainActivity).apply {
                id = R.id.vpn_auto_recovery
                uiSwitch()
                setText(R.string.vpn_auto_recovery)
                isSaveEnabled = false
                setOnCheckedChangeListener { _, checked ->
                    if (launchSettings.recoveryEnabled != checked) launchSettings.setRecoveryEnabled(checked)
                }
            }
            uiAdd(autoRecovery)
            notificationAccess = uiButton(R.string.vpn_notification_access).apply {
                id = R.id.vpn_notification_access
                setOnClickListener { openNotificationAccess() }
            }
            uiAdd(notificationAccess, 8)
        }, 14)
        uiAdd(uiCard().apply {
            uiAdd(uiHeading(R.string.ui_routing_and_backup))
            uiAdd(uiButton(R.string.split_title).apply {
                id = R.id.split_open_button
                setOnClickListener { startActivity(Intent(this@MainActivity, SplitTunnelingActivity::class.java)) }
            }, 10)
            uiAdd(uiButton(R.string.ip_title).apply {
                id = R.id.ui_open_ip_routes
                setOnClickListener { startActivity(Intent(this@MainActivity, IpRoutesActivity::class.java)) }
            }, 8)
            uiAdd(uiButton(R.string.backup_title).apply {
                id = R.id.backup_open_screen
                setOnClickListener { startActivity(Intent(this@MainActivity, BackupActivity::class.java)) }
            }, 8)
        }, 14)
    }

    private fun chooseTheme() {
        val current = AppAppearance.read(this)
        val options = AppAppearance.entries.map { getString(it.label) }.toTypedArray()
        AlertDialog.Builder(this).setTitle(R.string.ui_theme)
            .setSingleChoiceItems(options, current.ordinal) { dialog, which ->
                dialog.dismiss()
                val selected = AppAppearance.entries[which]
                if (selected != current) {
                    selected.save(this)
                    recreate()
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showSettings(open: Boolean) {
        home.visibility = if (open) View.GONE else View.VISIBLE
        settingsPage.visibility = if (open) View.VISIBLE else View.GONE
        navigation.visibility = if (open) View.GONE else View.VISIBLE
        backFromSettings.isEnabled = open
    }

    private fun LinearLayout.addNavigation(label: Int, glyph: Int, viewId: Int, open: () -> Unit) {
        addView(uiButton(label).apply {
            id = viewId
            textSize = 13f
            minHeight = dp(58)
            setPaddingRelative(dp(2), dp(7), dp(2), dp(7))
            compoundDrawablePadding = dp(4)
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, uiGlyph(glyph), null, null)
            setOnClickListener { open() }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            marginStart = dp(3)
            marginEnd = dp(3)
        })
    }

    private fun trafficTile(label: Int, amount: TextView): View = uiCard(13).apply {
        uiAdd(uiCaption(label))
        uiAdd(amount, 4)
    }

    private fun renderTraffic() {
        val traffic = VpnTrafficUi.latest.takeIf { VpnConnection.phase == VpnPhase.HANDSHAKE }
        val receiveText = traffic?.let { Formatter.formatShortFileSize(this, it.received) }
            ?: getString(R.string.ui_traffic_unavailable)
        val sendText = traffic?.let { Formatter.formatShortFileSize(this, it.sent) }
            ?: getString(R.string.ui_traffic_unavailable)
        if (received.text != receiveText) received.text = receiveText
        if (sent.text != sendText) sent.text = sendText
    }

    private fun selectConfig() {
        if (session.busy) return
        permissionMessage = null
        session.startPicker()
        try {
            openConfig.launch(profileDocumentPicker())
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
            if (pendingProfileConnection?.stopGeneration != manualStopSeen) pendingProfileConnection = null
            permissionMessage = null
        }
        permissionRequest?.takeIf { it.resetGeneration != VpnConnection.resetGeneration }?.let {
            clearPermissionRequest(it)
            permissionMessage = null
        }
        val phase = VpnConnection.phase
        val runningId = VpnConnection.activeProfileId
        val tunnelName = if (runningId != null && runningId != session.profile?.documentKey) {
            session.catalog.entries.firstOrNull { it.documentKey == runningId }?.name ?: VpnConnection.activeProfileName
        } else null
        activeProfile.visibility = if (tunnelName == null) View.GONE else View.VISIBLE
        if (tunnelName != null) activeProfile.text = getString(R.string.profiles_active_tunnel, tunnelName)
        autoConnect.isChecked = launchSettings.enabled
        autoConnect.setText(if (launchSettings.enabled && launchSettings.paused) R.string.vpn_auto_paused else R.string.vpn_auto_connect)
        autoRecovery.isChecked = launchSettings.recoveryEnabled
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
        val color = when {
            VpnConnection.currentError != null -> R.color.ui_error
            phase == VpnPhase.HANDSHAKE -> R.color.ui_good
            else -> R.color.ui_accent
        }
        if (statusColor != color) {
            statusColor = color
            connectionStatus.setTextColor(getColor(color))
            val surface = when (color) {
                R.color.ui_good -> R.color.ui_good_surface
                R.color.ui_error -> R.color.ui_error_surface
                else -> R.color.ui_accent_soft
            }
            connectionStatus.background = uiShape(surface, 20)
        }
        renderTraffic()
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
            uiField()
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

    private fun connectSelectedProfile() {
        val pending = pendingProfileConnection ?: return
        if (!resumed || session.busy || permissionRequest != null) return
        pendingProfileConnection = null
        if (pending.stopGeneration != VpnConnection.manualStopGeneration) return
        if (pending.id != session.profile?.documentKey) {
            showPermissionMessage(R.string.profiles_selection_changed, error = true)
            return
        }
        if (VpnConnection.activeProfileId == pending.id) return
        requestConnection(automatic = false, profileId = pending.id)
    }

    private fun requestConnection(automatic: Boolean, profileId: String? = null) {
        val request = PermissionRequest(automatic = automatic, profileId = profileId)
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
        if ((request.profileId == null && VpnConnection.phase != VpnPhase.IDLE) ||
            (request.automatic && (!launchSettings.enabled || launchSettings.paused))
        ) {
            renderConnection()
            return
        }
        VpnConnection.record(R.string.vpn_permission_granted)
        val profile = session.profile
        if (profile == null) {
            showPermissionMessage(R.string.vpn_profile_required, error = true)
        } else if ((request.profileId != null && request.profileId != profile.documentKey) ||
            session.savedRevision != applicationContext.localProfiles().revision()
        ) {
            showPermissionMessage(R.string.profiles_selection_changed, error = true)
        } else if (VpnConnection.phase == VpnPhase.IDLE) {
            if (!request.automatic) launchSettings.setPaused(false)
            VpnConnection.start(applicationContext, profile.config, labels.country(profile.documentKey), profile.documentKey, profile.name)
        } else {
            VpnConnection.switchProfile(applicationContext, profile, labels.country(profile.documentKey), session.savedRevision)
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
        const val PERMISSION_PROFILE = "vpn_permission_profile"
        const val PERMISSION_MESSAGE = "vpn_permission_message"
        const val PERMISSION_CHECK_TIMEOUT_MS = 30_000L
        const val PERMISSION_RESULT_TIMEOUT_MS = 60_000L
        const val PICKER_IN_PROGRESS = "config_picker_in_progress"
        const val IMPORT_IN_PROGRESS = "config_import_in_progress"
        const val NOTIFICATION_ASKED = "post_notifications_asked"
    }
}
