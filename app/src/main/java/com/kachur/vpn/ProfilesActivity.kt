package com.kachur.vpn

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider

class ProfilesActivity : ComponentActivity() {
    private val session by lazy { ViewModelProvider(this)[ImportSession::class.java] }
    private val labels by lazy { VpnProfileLabels(applicationContext) }
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private lateinit var importButton: Button
    private var pendingRemoval: String? = null
    private var resumed = false
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
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(20))
        }
        content.addView(Button(this).apply {
            setText(R.string.split_back)
            setOnClickListener { finish() }
        })
        content.addView(TextView(this).apply {
            setText(R.string.profiles_title)
            textSize = 24f
        })
        importButton = Button(this).apply {
            id = R.id.profiles_import
            setText(R.string.profiles_import)
            setOnClickListener {
                session.startPicker()
                try {
                    picker.launch(profileDocumentPicker())
                } catch (_: RuntimeException) {
                    session.finish(R.string.picker_unavailable)
                }
            }
        }
        content.addView(importButton)
        status = TextView(this).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, dp(4), 0, dp(8))
        }
        content.addView(status)
        rows = LinearLayout(this).apply {
            id = R.id.profiles_list
            orientation = LinearLayout.VERTICAL
        }
        content.addView(rows)
        setContentView(ScrollView(this).apply {
            addView(content)
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        })
        render()
    }

    override fun onStart() {
        super.onStart()
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
        rows.removeAllViews()
        session.catalog.entries.forEach { profile -> addRow(profile, working) }
    }

    private fun addRow(profile: ImportedProfile, working: Boolean) {
        val id = profile.documentKey
        val selected = session.catalog.selectedId == id
        val inUse = VpnConnection.activeProfileId == id
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(8))
        }
        item.addView(TextView(this).apply {
            text = profile.name
            textSize = 18f
        })
        item.addView(TextView(this).apply {
            text = listOfNotNull(labels.country(id), getString(R.string.profiles_selected).takeIf { selected },
                getString(R.string.profiles_in_use).takeIf { inUse }).joinToString(" · ")
        })
        val actions = LinearLayout(this)
        actions.addView(Button(this).apply {
            setText(if (VpnConnection.phase == VpnPhase.IDLE) R.string.profiles_connect else R.string.profiles_switch)
            isEnabled = !working && !inUse && !VpnConnection.checkingRoutes &&
                VpnConnection.phase != VpnPhase.STARTING && VpnConnection.phase != VpnPhase.STOPPING
            setOnClickListener {
                val request = ProfileConnectionRequest(id, VpnConnection.manualStopGeneration)
                session.edit(applicationContext, request) { it.select(id) }
            }
        })
        actions.addView(Button(this).apply {
            setText(R.string.profiles_actions)
            isEnabled = !working
            setOnClickListener { showActions(it, profile, selected) }
        })
        item.addView(actions)
        rows.addView(item)
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

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        internal const val PROFILE_ID = "selected_vpn_profile_id"
        internal const val STOP_GENERATION = "profile_connect_stop_generation"
        private const val PENDING_REMOVAL = "pending_profile_removal"
        private const val SELECT = 1
        private const val RENAME = 2
        private const val DELETE = 3
    }
}
