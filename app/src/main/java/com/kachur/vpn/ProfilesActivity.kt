package com.kachur.vpn

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider

class ProfilesActivity : KachurActivity() {
    private val session by lazy { ViewModelProvider(this)[ImportSession::class.java] }
    private val labels by lazy { VpnProfileLabels(applicationContext) }
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private lateinit var importButton: Button
    private var pendingRemoval: String? = null
    private var resumed = false
    private var renderedRows: RowsState? = null
    private data class RowsState(val catalog: ProfileCatalog, val working: Boolean, val phase: VpnPhase,
        val activeId: String?, val checkingRoutes: Boolean)
    private val observer: () -> Unit = {
        finishPendingRemoval()
        render()
    }
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        session.handlePickerResult(applicationContext, result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingRemoval = savedInstanceState?.getString(PENDING_REMOVAL)
        val content = uiColumn(18)
        content.uiAdd(uiHeader(R.string.profiles_title) { finish() })
        importButton = uiButton(R.string.profiles_import, primary = true, icon = R.drawable.ic_ui_import).apply {
            id = R.id.profiles_import
            setOnClickListener {
                session.startPicker()
                try {
                    picker.launch(profileDocumentPicker())
                } catch (_: RuntimeException) {
                    session.finish(R.string.picker_unavailable)
                }
            }
        }
        content.uiAdd(importButton, 16)
        status = uiLabel(15, R.color.ui_muted).apply {
            id = R.id.profiles_status
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.uiAdd(status, 10)
        rows = uiColumn().apply { id = R.id.profiles_list }
        content.uiAdd(rows)
        setContentView(uiScroll(content).apply { uiInsets() })
        render()
    }

    override fun onStart() {
        super.onStart()
        renderedRows = null
        VpnConnection.observe(observer)
        session.onChange = ::render
        session.syncSavedProfile(applicationContext)
        render()
    }

    override fun onPostResume() {
        super.onPostResume()
        resumed = true
        if (session.picking) session.finish(R.string.picker_no_callback)
        finishPendingRemoval()
        render()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onStop() {
        session.onChange = null
        VpnConnection.removeObserver(observer)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(PENDING_REMOVAL, pendingRemoval)
    }

    private fun render() {
        if (resumed) session.consumeConnection()?.let { pending ->
            setResult(Activity.RESULT_OK, Intent().putExtra(PROFILE_ID, pending.id)
                .putExtra(STOP_GENERATION, pending.stopGeneration))
            finish()
            return
        }
        val working = session.busy || pendingRemoval != null
        importButton.isEnabled = !working
        val message = when {
            pendingRemoval != null -> R.string.profiles_deleting_active
            session.reading -> session.stages.lastOrNull()
            session.busy -> R.string.profiles_loading
            session.feedback != null -> session.feedback
            session.catalog.entries.isEmpty() -> R.string.profiles_empty
            else -> null
        }
        status.text = message?.let {
            session.errorLine?.takeIf { !working }?.let { line -> getString(it, line) } ?: getString(it)
        }.orEmpty()
        status.visibility = if (message == null) View.GONE else View.VISIBLE
        val nextRows = RowsState(session.catalog, working, VpnConnection.phase, VpnConnection.activeProfileId, VpnConnection.checkingRoutes)
        if (renderedRows == nextRows) return
        renderedRows = nextRows
        rows.removeAllViews()
        session.catalog.entries.forEach { profile -> addRow(profile, working) }
    }

    private fun addRow(profile: ImportedProfile, working: Boolean) {
        val profileId = profile.documentKey
        val selected = session.catalog.selectedId == profileId
        val inUse = VpnConnection.activeProfileId == profileId
        val country = labels.country(profileId)
        val item = uiCard().apply {
            if (selected) background = uiPanel(border = R.color.ui_accent)
        }
        item.uiAdd(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@ProfilesActivity).apply {
                showServerBadge(country)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(46), dp(38)).apply { marginEnd = dp(12) })
            addView(uiColumn().apply {
                uiAdd(uiLabel(20, bold = true).apply { text = profile.name })
                uiAdd(uiLabel(14, when {
                    inUse -> R.color.ui_good
                    selected -> R.color.ui_accent
                    else -> R.color.ui_muted
                }).apply {
                    text = listOfNotNull(country, getString(R.string.profiles_selected).takeIf { selected },
                        getString(R.string.profiles_in_use).takeIf { inUse }).joinToString(" · ")
                }, 3)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })
        val connect = uiButton(
            if (VpnConnection.phase == VpnPhase.IDLE) R.string.profiles_connect else R.string.profiles_switch,
            primary = selected,
        ).apply {
            id = R.id.profiles_row_connect
            contentDescription = getString(R.string.ui_profile_action, text, profile.name)
            isEnabled = !working && !inUse && !VpnConnection.checkingRoutes &&
                VpnConnection.phase != VpnPhase.STARTING && VpnConnection.phase != VpnPhase.STOPPING
            setOnClickListener {
                val request = ProfileConnectionRequest(profileId, VpnConnection.manualStopGeneration)
                session.edit(applicationContext, request) { it.select(profileId) }
            }
        }
        val actions = uiButton(R.string.profiles_actions, icon = R.drawable.ic_ui_more).apply {
            id = R.id.profiles_row_actions
            contentDescription = getString(R.string.ui_profile_action, text, profile.name)
            isEnabled = !working
            setOnClickListener { showActions(it, profile, selected) }
        }
        item.uiAdd(uiPair(connect, actions), 12)
        rows.uiAdd(item, 12)
    }

    private fun showActions(anchor: View, profile: ImportedProfile, selected: Boolean) {
        PopupMenu(this, anchor).apply {
            if (!selected) menu.add(0, SELECT, 0, R.string.profiles_select)
            menu.add(0, RENAME, 1, R.string.profiles_rename)
            menu.add(0, DELETE, 2, R.string.ip_remove)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    SELECT -> confirmSelection(profile)
                    RENAME -> rename(profile)
                    DELETE -> confirmDeletion(profile)
                }
                true
            }
            show()
        }
    }

    private fun confirmSelection(profile: ImportedProfile) {
        AlertDialog.Builder(this).setTitle(R.string.profiles_select)
            .setMessage(getString(R.string.profiles_select_note, profile.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                session.edit(applicationContext) { it.select(profile.documentKey) }
            }.show()
    }

    private fun rename(profile: ImportedProfile) {
        val input = EditText(this).apply {
            uiField()
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(100))
            setText(profile.name)
            selectAll()
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.profiles_rename).setView(input)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.vpn_country_save, null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text.toString().trim()
            if (ProfileJson.validName(name)) {
                dialog.dismiss()
                session.edit(applicationContext) { it.rename(profile.documentKey, name) }
            } else input.error = getString(R.string.profiles_invalid_name)
        }
    }

    private fun confirmDeletion(profile: ImportedProfile) {
        val id = profile.documentKey
        val inUse = VpnConnection.activeProfileId == id
        val note = if (inUse) R.string.profiles_delete_active_note else R.string.profiles_delete_note
        AlertDialog.Builder(this).setTitle(R.string.ip_remove).setMessage(getString(note, profile.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ip_remove) { _, _ ->
                if (VpnConnection.activeProfileId == id) {
                    pendingRemoval = id
                    VpnConnection.stop(R.string.vpn_disconnected)
                    render()
                } else session.edit(applicationContext) { it.remove(id) }
            }.show()
    }

    private fun finishPendingRemoval() {
        val id = pendingRemoval ?: return
        if (!resumed || session.busy || VpnConnection.phase != VpnPhase.IDLE) return
        pendingRemoval = null
        if (VpnConnection.message == R.string.vpn_disconnected && VpnConnection.currentError == null) {
            session.edit(applicationContext) { it.remove(id) }
        } else session.showMessage(R.string.profiles_delete_stop_failed)
    }

    companion object {
        internal const val PROFILE_ID = "selected_vpn_profile_id"
        internal const val STOP_GENERATION = "profile_connect_stop_generation"
        private const val PENDING_REMOVAL = "pending_profile_removal"
        private const val SELECT = 1
        private const val RENAME = 2
        private const val DELETE = 3
    }
}
