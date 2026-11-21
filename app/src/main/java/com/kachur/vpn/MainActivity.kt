package com.kachur.vpn

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Color
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
    private lateinit var feedback: TextView
    private lateinit var importButton: Button
    private lateinit var connectButton: Button

    private val openConfig = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_CANCELED) return@registerForActivityResult

        val data = result.data
        val uri = data?.data ?: data?.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
        when {
            result.resultCode != Activity.RESULT_OK || uri == null ->
                session.showMessage(R.string.no_document_returned)
            uri.scheme != ContentResolver.SCHEME_CONTENT ->
                session.showMessage(R.string.unsupported_document_uri)
            else -> session.load(applicationContext.contentResolver, uri, getString(R.string.unnamed_profile))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = ViewModelProvider(this)[ImportSession::class.java]

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
        layout.addView(label(getString(R.string.vpn_disconnected), 18f, Color.LTGRAY))
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
        connectButton = Button(this).apply {
            setText(R.string.connect_vpn)
            setOnClickListener {
                session.showMessage(R.string.engine_unavailable)
            }
        }
        layout.addView(connectButton)
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
            session.showMessage(R.string.picker_unavailable)
        } catch (_: SecurityException) {
            session.showMessage(R.string.picker_unavailable)
        }
    }

    private fun render() {
        profileName.text = session.profile?.name ?: getString(R.string.no_profile)
        val message = session.feedback
        val errorLine = session.errorLine
        feedback.text = when {
            message == null -> ""
            errorLine != null -> getString(message, errorLine)
            else -> getString(message)
        }
        feedback.visibility = if (feedback.text.isEmpty()) View.GONE else View.VISIBLE
        importButton.isEnabled = !session.loading
        connectButton.isEnabled = session.profile != null && !session.loading
    }
}
