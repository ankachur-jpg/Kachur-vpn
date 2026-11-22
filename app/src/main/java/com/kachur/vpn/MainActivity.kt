package com.kachur.vpn

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.SpannableString
import android.text.Spanned
import android.text.format.Formatter
import android.text.style.RelativeSizeSpan
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
    private lateinit var serverCard: View
    private lateinit var serverCountry: TextView
    private lateinit var serverBadge: ImageView
    private lateinit var profileCountry: Button
    private lateinit var profileSettings: View
    private lateinit var autoConnect: Switch
    private lateinit var autoRecovery: Switch
    private lateinit var notificationAccess: Button
    private lateinit var diagnostics: TextView
    private lateinit var feedback: TextView
    private lateinit var importPanel: View
    private lateinit var importButton: Button
    private lateinit var connectButton: Button
    private var powerHalo: View? = null
    private var powerTone: PowerTone? = null
    private lateinit var reconnectButton: Button
    private lateinit var protectionCard: View
    private lateinit var protectionTitle: TextView
    private lateinit var protectionHint: TextView
    private lateinit var protectionBadge: ImageView
    private lateinit var connectionStatus: TextView
    private lateinit var connectionDiagnostics: TextView
    private lateinit var received: TextView
    private lateinit var sent: TextView
    private lateinit var duration: TextView
    private lateinit var home: ScrollView
    private lateinit var settingsPage: ScrollView
    private var detailsOpen = false
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
        settingsPage = uiScroll(buildSettings()).apply {
            id = R.id.ui_settings_page
            ViewCompat.setAccessibilityPaneTitle(this, getString(R.string.ui_settings))
        }
        home = uiScroll(buildHome()).apply {
            id = R.id.ui_home_scroll
            ViewCompat.setAccessibilityPaneTitle(this, getString(R.string.app_name))
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.ui_background))
            addView(home, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(settingsPage, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            uiInsets()
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, backFromSettings)
        showSettings(savedState?.getBoolean("settings_open") == true)
    }

    private fun buildHome(): LinearLayout = uiColumn(16).apply {
        uiAdd(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_vpn_launcher_foreground)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(58), dp(54)))
            addView(uiHeading(R.string.app_name, 26), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(uiIconButton(R.string.ui_settings, R.drawable.ic_ui_settings, quiet = true).apply {
                id = R.id.ui_open_settings
                setOnClickListener { showSettings(true) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        })
        uiAdd(buildPowerControl(), 4)
        uiAdd(buildServerCard(), 4)
        uiAdd(buildTraffic(), 14)
        uiAdd(buildNavigation(), 18)
        val details = buildConnectionDetails()
        uiAdd(buildProtectionCard(details), 18)
        uiAdd(details, 8)
        reconnectButton = uiButton(R.string.split_apply_reconnect).apply {
            id = R.id.main_split_reconnect_button
            setOnClickListener { VpnConnection.reconnectWithSavedExclusions(applicationContext) }
        }
        uiAdd(reconnectButton, 14)
        importPanel = uiCard().apply {
            importButton = uiButton(R.string.import_profile, icon = R.drawable.ic_ui_import).apply {
                id = R.id.import_profile_button
                setOnClickListener { selectConfig() }
            }
            uiAdd(importButton)
            feedback = uiLabel(16).apply {
                id = R.id.import_feedback
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            uiAdd(feedback, 8)
            diagnostics = uiLabel(14, R.color.ui_muted).apply { id = R.id.import_diagnostics }
            uiAdd(diagnostics, 6)
        }
        uiAdd(importPanel, 14)
    }

    private fun buildPowerControl(): View {
        connectButton = uiButton(0, primary = true).apply {
            id = R.id.connect_vpn_button
            minHeight = dp(72)
            textSize = 21f
            includeFontPadding = false
            compoundDrawablePadding = dp(10)
            setPaddingRelative(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { toggleVpn() }
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        if (uiStackControls()) return connectButton
        return FrameLayout(this).apply {
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ui_world_map)
                scaleType = ImageView.ScaleType.FIT_XY
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(MATCH_PARENT, dp(214), Gravity.TOP))
            val halo = FrameLayout(this@MainActivity).apply {
                addView(connectButton, FrameLayout.LayoutParams(dp(192), dp(192), Gravity.CENTER))
            }
            powerHalo = halo
            addView(halo, FrameLayout.LayoutParams(dp(250), dp(250), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(26)
            })
        }
    }

    private fun buildServerCard(): View = uiActionRow().apply {
        id = R.id.ui_server_card
        serverCard = this
        serverBadge = ImageView(this@MainActivity).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(serverBadge, LinearLayout.LayoutParams(dp(48), dp(38)))
        val title = uiColumn().apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            serverCountry = uiLabel(20, bold = true)
            profileName = uiLabel(14, R.color.ui_muted).apply { id = R.id.profile_name }
            activeProfile = uiLabel(15, R.color.ui_muted)
            uiAdd(serverCountry)
            uiAdd(profileName, 1)
            uiAdd(activeProfile)
        }
        addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            marginStart = dp(14)
            marginEnd = dp(8)
        })
        addView(uiDecorativeIcon(R.drawable.ic_ui_chevron), LinearLayout.LayoutParams(dp(20), dp(20)))
        setOnClickListener { showProfiles() }
    }

    private fun buildTraffic(): View = LinearLayout(this).apply {
        orientation = if (uiStackControls()) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        received = uiLabel(15, bold = true).apply { id = R.id.ui_traffic_received }
        sent = uiLabel(15, bold = true).apply { id = R.id.ui_traffic_sent }
        duration = uiLabel(15, bold = true).apply { id = R.id.ui_session_time }
        for ((index, tile) in listOf(
            trafficTile(R.string.ui_received, received, R.drawable.ic_ui_receive, R.color.ui_good),
            trafficTile(R.string.ui_sent, sent, R.drawable.ic_ui_send, R.color.ui_accent),
            trafficTile(R.string.ui_session_time, duration, R.drawable.ic_ui_clock, R.color.ui_accent),
        ).withIndex()) {
            val gap = if (index == 0) 0 else 8
            if (orientation == LinearLayout.VERTICAL) {
                uiAdd(tile, gap)
            } else {
                addView(tile, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = dp(gap) })
            }
        }
    }

    private fun buildNavigation(): View = LinearLayout(this).apply {
        orientation = if (uiStackControls()) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        setPadding(dp(5), dp(5), dp(5), dp(5))
        background = uiShape(R.color.ui_surface, 22, R.color.ui_border)
        addNavigation(R.string.ui_home_vpn, R.drawable.ic_ui_home, R.id.ui_home_tab, selected = true) {
            home.scrollTo(0, 0)
        }
        addNavigation(R.string.ui_diagnostics, R.drawable.ic_ui_chart, R.id.diag_open_screen) {
            startActivity(Intent(this@MainActivity, DiagnosticsActivity::class.java))
        }
        addNavigation(R.string.ui_servers, R.drawable.ic_ui_servers, R.id.profiles_open) { showProfiles() }
    }

    private fun buildProtectionCard(details: View): View = uiActionRow().apply {
        id = R.id.ui_connection_details
        protectionCard = this
        protectionBadge = uiDecorativeIcon(R.drawable.ic_ui_shield)
        addView(protectionBadge, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(4) })
        addView(uiColumn().apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            protectionTitle = uiLabel(17, bold = true).apply { id = R.id.ui_protection_title }
            protectionHint = uiLabel(14, R.color.ui_muted)
            connectionStatus = uiLabel(14, R.color.ui_muted).apply { id = R.id.connection_status }
            uiAdd(protectionTitle)
            uiAdd(protectionHint, 2)
            uiAdd(connectionStatus, 2)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            marginStart = dp(16)
            marginEnd = dp(8)
        })
        val chevron = uiDecorativeIcon(R.drawable.ic_ui_chevron).apply {
            rotation = if (detailsOpen) 90f else 0f
        }
        addView(chevron, LinearLayout.LayoutParams(dp(20), dp(20)))
        ViewCompat.setStateDescription(this, getString(if (detailsOpen) R.string.ui_hide_details else R.string.ui_details))
        setOnClickListener {
            detailsOpen = !detailsOpen
            details.visibility = if (detailsOpen) View.VISIBLE else View.GONE
            chevron.rotation = if (detailsOpen) 90f else 0f
            ViewCompat.setStateDescription(this, getString(if (detailsOpen) R.string.ui_hide_details else R.string.ui_details))
        }
    }

    private fun buildConnectionDetails(): View = uiCard().apply {
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

    private fun buildSettings(): View = uiColumn(18).apply {
        uiAdd(uiHeader(R.string.ui_settings) { showSettings(false) })
        uiAdd(uiCard().apply {
            uiAdd(uiHeading(R.string.ui_appearance))
            uiAdd(uiSettingsLink(0, R.drawable.ic_ui_palette).apply {
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
        profileSettings = uiCard().apply {
            uiAdd(uiHeading(R.string.ui_selected_server))
            profileCountry = uiSettingsLink(0, R.drawable.ic_ui_servers).apply {
                id = R.id.vpn_profile_country
                setOnClickListener { editCountry() }
            }
            uiAdd(profileCountry, 10)
        }
        uiAdd(profileSettings, 14)
        uiAdd(uiCard().apply {
            uiAdd(uiHeading(R.string.ui_routing_and_backup))
            uiAdd(uiSettingsLink(R.string.split_title, R.drawable.ic_ui_routes).apply {
                id = R.id.split_open_button
                setOnClickListener { startActivity(Intent(this@MainActivity, SplitTunnelingActivity::class.java)) }
            }, 10)
            uiAdd(uiSettingsLink(R.string.ip_title, R.drawable.ic_ui_shield).apply {
                id = R.id.ui_open_ip_routes
                setOnClickListener { startActivity(Intent(this@MainActivity, IpRoutesActivity::class.java)) }
            }, 8)
            uiAdd(uiSettingsLink(R.string.backup_title, R.drawable.ic_ui_backup).apply {
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
        backFromSettings.isEnabled = open
    }

    private fun showProfiles() {
        if (!session.busy && permissionRequest == null) {
            openProfiles.launch(Intent(this, ProfilesActivity::class.java))
        }
    }

    private fun LinearLayout.addNavigation(
        label: Int, glyph: Int, viewId: Int, selected: Boolean = false, open: () -> Unit,
    ) {
        val stacked = orientation == LinearLayout.VERTICAL
        val tab = uiButton(label, primary = selected).apply {
            id = viewId
            isSelected = selected
            textSize = if (stacked) 16f else 13f
            minHeight = dp(if (stacked) 48 else 62)
            setPaddingRelative(dp(6), dp(7), dp(6), dp(7))
            compoundDrawablePadding = dp(6)
            val icon = uiGlyph(glyph, if (selected) R.color.ui_on_primary else R.color.ui_accent)
            if (stacked) setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
            else setCompoundDrawablesRelativeWithIntrinsicBounds(null, icon, null, null)
            if (!selected) {
                background = RippleDrawable(ColorStateList.valueOf(getColor(R.color.ui_ripple)),
                    null, uiShape(android.R.color.white, 16))
                setTextColor(getColor(R.color.ui_text))
            }
            setOnClickListener { open() }
        }
        if (stacked) uiAdd(tab, if (childCount == 0) 0 else 4)
        else addView(tab, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
    }

    private fun trafficTile(label: Int, amount: TextView, glyph: Int, tint: Int): View = uiCard(9).apply {
        background = uiPanel(14)
        minimumHeight = dp(62)
        gravity = Gravity.CENTER
        ViewCompat.setScreenReaderFocusable(this, true)
        uiAdd(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER
            addView(uiDecorativeIcon(glyph, tint), LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(5) })
            addView(amount, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        })
        uiAdd(uiLabel(13, R.color.ui_muted).apply {
            setText(label)
            gravity = Gravity.CENTER
        }, 3)
    }

    private fun renderTraffic() {
        val reading = VpnTrafficUi.latest.takeIf { VpnConnection.phase == VpnPhase.HANDSHAKE }
        val missing = getString(R.string.ui_traffic_unavailable)
        val receiveText = reading?.traffic?.let { Formatter.formatShortFileSize(this, it.received) } ?: missing
        val sendText = reading?.traffic?.let { Formatter.formatShortFileSize(this, it.sent) } ?: missing
        val durationText = reading?.elapsedSeconds?.let {
            getString(R.string.ui_session_duration, it / 3600, it / 60 % 60, it % 60)
        } ?: missing
        if (received.text != receiveText) received.text = receiveText
        if (sent.text != sendText) sent.text = sendText
        if (duration.text != durationText) duration.text = durationText
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
        val country = profile?.let { labels.country(it.documentKey) }
        profileName.text = profile?.name ?: getString(R.string.no_profile)
        serverCountry.text = country ?: getString(R.string.ui_selected_server)
        serverBadge.showServerBadge(country)
        profileSettings.visibility = if (profile == null) View.GONE else View.VISIBLE
        if (country != null) profileCountry.text = getString(R.string.vpn_country_label, country)
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
        importPanel.visibility = if (profile == null || session.busy || message != null) View.VISIBLE else View.GONE
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
        serverCard.contentDescription = listOfNotNull(serverCountry.text, profileName.text,
            activeProfile.text.takeIf { tunnelName != null }).joinToString(". ")
        serverCard.isEnabled = !session.busy && permissionRequest == null
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
        val error = VpnConnection.currentError != null ||
            (phase == VpnPhase.IDLE && permissionMessage != null && VpnConnection.lastError?.category == permissionMessage)
        renderPower(phase, blocked, error)
        renderProtection(phase, error)
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

    private fun renderPower(phase: VpnPhase, blocked: Int?, error: Boolean) {
        val state = when {
            error -> R.string.vpn_notice_error
            permissionRequest != null || phase == VpnPhase.STARTING -> R.string.vpn_notice_connecting
            phase == VpnPhase.WAITING -> R.string.ui_power_waiting
            phase == VpnPhase.HANDSHAKE -> R.string.vpn_notice_connected
            phase == VpnPhase.RECOVERING -> R.string.ui_power_recovering
            phase == VpnPhase.STOPPING -> R.string.vpn_phase_stopping
            else -> R.string.vpn_disconnected
        }
        val action = getString(blocked ?: when (phase) {
            VpnPhase.STARTING -> R.string.vpn_cancel_start
            VpnPhase.WAITING, VpnPhase.HANDSHAKE, VpnPhase.RECOVERING -> R.string.vpn_disconnect
            else -> R.string.connect_vpn
        })
        val label = getString(state)
        val showAction = phase == VpnPhase.IDLE && blocked == null || uiStackControls() && blocked == null
        val text = if (showAction) "$label\n$action" else label
        if (connectButton.text.toString() != text) {
            connectButton.text = SpannableString(text).apply {
                if (showAction) setSpan(RelativeSizeSpan(0.72f), label.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val description = getString(R.string.ui_power_accessible, label, action)
        if (connectButton.contentDescription != description) connectButton.contentDescription = description
        val tone = when {
            error -> PowerTone.ERROR
            phase == VpnPhase.HANDSHAKE -> PowerTone.ACTIVE
            else -> PowerTone.READY
        }
        if (powerTone == tone) return
        powerTone = tone
        val compact = powerHalo == null
        connectButton.setTextColor(tone.ink)
        connectButton.background = powerDisc(tone, compact)
        val glyph = checkNotNull(getDrawable(R.drawable.ic_ui_power)).mutate().apply {
            setTint(tone.ink)
            val size = dp(if (compact) 30 else 48)
            setBounds(0, 0, size, size)
        }
        connectButton.setCompoundDrawablesRelative(null, glyph, null, null)
        powerHalo?.background = PowerHaloDrawable(tone, getColor(R.color.ui_horizon))
    }

    private fun renderProtection(phase: VpnPhase, error: Boolean) {
        val protected = phase == VpnPhase.HANDSHAKE && !error
        val status = if (phase == VpnPhase.IDLE) permissionMessage ?: VpnConnection.message else VpnConnection.message
        val title = when {
            protected -> R.string.ui_protection_active
            error -> R.string.vpn_notice_error
            phase == VpnPhase.IDLE && permissionRequest == null -> R.string.ui_protection_inactive
            else -> R.string.ui_protection_progress
        }
        val hint = when {
            protected -> R.string.ui_protection_summary
            status == R.string.vpn_disconnected -> R.string.ui_protection_hint
            else -> null
        }
        protectionTitle.setText(title)
        connectionStatus.setText(status)
        connectionStatus.visibility = if (hint == null) View.VISIBLE else View.GONE
        protectionHint.visibility = if (hint != null) View.VISIBLE else View.GONE
        if (hint != null) protectionHint.setText(hint)
        protectionCard.contentDescription = getString(R.string.ui_power_accessible, getString(title), getString(hint ?: status))
        if (protectionBadge.tag != title) {
            protectionBadge.tag = title
            protectionBadge.setImageDrawable(uiGlyph(
                if (protected) R.drawable.ic_ui_protected else R.drawable.ic_ui_shield,
                when {
                    protected -> R.color.ui_good
                    error -> R.color.ui_error
                    else -> R.color.ui_accent
                },
            ))
        }
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
