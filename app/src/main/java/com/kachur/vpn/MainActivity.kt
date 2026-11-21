package com.kachur.vpn

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    private lateinit var session: ImportSession
    private lateinit var profileName: TextView
    private lateinit var diagnostics: TextView
    private lateinit var feedback: TextView
    private lateinit var importButton: Button
    private lateinit var connectButton: Button
    private lateinit var connectionStatus: TextView
    private var permissionPending = false
    private var permissionMessage: Int? = null

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        permissionPending = false
        val profile = session.profile
        when {
            result.resultCode != Activity.RESULT_OK -> permissionMessage = R.string.vpn_permission_denied
            profile == null -> permissionMessage = R.string.vpn_profile_required
            else -> VpnConnection.start(this, profile.config)
        }
        render()
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
                    else -> session.load(applicationContext.contentResolver, uri, getString(R.string.unnamed_profile))
                }
            }
            else -> session.finish(R.string.unexpected_picker_result)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = ViewModelProvider(this)[ImportSession::class.java]
        permissionPending = savedInstanceState?.getBoolean(PERMISSION_PENDING) == true
        permissionMessage = savedInstanceState?.getInt(PERMISSION_MESSAGE, 0)?.takeIf { it != 0 }

        if (!session.busy && savedInstanceState != null) {
            when {
                savedInstanceState.getBoolean(IMPORT_IN_PROGRESS) -> session.finish(R.string.import_interrupted)
                savedInstanceState.getBoolean(PICKER_IN_PROGRESS) -> session.startPicker()
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(18, 24, 35))
        }
        layout.addView(label(getString(R.string.app_name), 30f, Color.WHITE))
        profileName = label("", 22f, Color.WHITE).apply { id = R.id.profile_name }
        layout.addView(profileName)
        connectionStatus = label("", 18f, Color.WHITE).apply {
            id = R.id.connection_status
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        layout.addView(connectionStatus)
        connectButton = Button(this).apply {
            id = R.id.connect_vpn_button
            setText(R.string.connect_vpn)
            setOnClickListener { toggleVpn() }
        }
        layout.addView(connectButton)
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
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(18, 24, 35))
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
                )
                insets
            }
            addView(layout)
        })
        session.onChange = ::render
        render()
    }

    override fun onStart() {
        super.onStart()
        VpnConnection.onChange = ::render
        render()
    }

    override fun onStop() {
        VpnConnection.onChange = null
        super.onStop()
    }

    override fun onPostResume() {
        super.onPostResume()
        if (session.picking) {
            // Registry results are delivered by this point when Android returns from the picker.
            session.finish(R.string.picker_no_callback)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // The picker callback is restored by ActivityResultRegistry. A background read cannot be restored.
        outState.putBoolean(IMPORT_IN_PROGRESS, session.reading)
        outState.putBoolean(PICKER_IN_PROGRESS, session.picking)
        outState.putBoolean(PERMISSION_PENDING, permissionPending)
        permissionMessage?.let { outState.putInt(PERMISSION_MESSAGE, it) }
    }

    override fun onDestroy() {
        session.onChange = null
        super.onDestroy()
    }

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
        profileName.text = session.profile?.name ?: getString(R.string.no_profile)
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
        val phase = VpnConnection.phase
        val canStop = phase == VpnPhase.WAITING || phase == VpnPhase.HANDSHAKE
        importButton.isEnabled = !session.busy && !permissionPending && phase == VpnPhase.IDLE
        connectButton.isEnabled = !session.busy && !permissionPending && (canStop || phase == VpnPhase.IDLE)
        connectButton.setText(if (canStop) R.string.vpn_disconnect else R.string.connect_vpn)
        val connectionMessage = when {
            phase != VpnPhase.IDLE -> VpnConnection.message
            permissionPending -> R.string.vpn_waiting_permission
            else -> permissionMessage ?: VpnConnection.message
        }
        connectionStatus.setText(connectionMessage)
    }

    private fun toggleVpn() {
        if (VpnConnection.phase != VpnPhase.IDLE) {
            VpnConnection.stop(this)
            return
        }
        val profile = session.profile
        permissionMessage = null
        if (profile == null) {
            permissionMessage = R.string.vpn_profile_required
        } else {
            try {
                val consent = VpnService.prepare(this)
                if (consent == null) {
                    VpnConnection.start(this, profile.config)
                } else {
                    permissionPending = true
                    vpnPermission.launch(consent)
                }
            } catch (_: RuntimeException) {
                permissionPending = false
                permissionMessage = R.string.vpn_permission_unavailable
            }
        }
        render()
    }

    private companion object {
        const val PERMISSION_PENDING = "vpn_permission_pending"
        const val PERMISSION_MESSAGE = "vpn_permission_message"
        const val PICKER_IN_PROGRESS = "config_picker_in_progress"
        const val IMPORT_IN_PROGRESS = "config_import_in_progress"
    }
}
