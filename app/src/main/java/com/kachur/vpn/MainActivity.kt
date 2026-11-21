package com.kachur.vpn

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.IOException
import java.nio.charset.CharacterCodingException

class MainActivity : Activity() {
    private lateinit var session: ImportSession
    private lateinit var profileName: TextView
    private lateinit var feedback: TextView
    private lateinit var importButton: Button
    private lateinit var connectButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = lastNonConfigurationInstance as? ImportSession ?: ImportSession()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(18, 24, 35))
        }
        layout.addView(label(getString(R.string.app_name), 30f, Color.WHITE))
        profileName = label("", 22f, Color.WHITE)
        layout.addView(profileName)
        layout.addView(label(getString(R.string.vpn_disconnected), 18f, Color.LTGRAY))
        feedback = label("", 16f, Color.LTGRAY).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        layout.addView(feedback)
        importButton = Button(this).apply {
            setText(R.string.import_profile)
            setOnClickListener { selectConfig() }
        }
        layout.addView(importButton)
        connectButton = Button(this).apply {
            setText(R.string.connect_vpn)
            setOnClickListener {
                session.feedback = R.string.engine_unavailable
                session.errorLine = null
                render()
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

    override fun onRetainNonConfigurationInstance(): Any = session

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
            // Providers use different MIME types for .conf. Check the display name after selection.
            type = "*/*"
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(picker, OPEN_CONFIG)
        } catch (_: ActivityNotFoundException) {
            session.feedback = R.string.picker_unavailable
            session.errorLine = null
            render()
        }
    }

    @Deprecated("The platform picker uses the Activity result callback.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OPEN_CONFIG && resultCode == RESULT_OK) {
            data?.data?.let { session.load(applicationContext.contentResolver, it) }
        }
    }

    private fun render() {
        profileName.text = session.profile?.name ?: getString(R.string.no_profile)
        feedback.text = session.errorLine?.let { getString(R.string.invalid_config_line, it) }
            ?: session.feedback?.let { getString(it) }.orEmpty()
        feedback.visibility = if (feedback.text.isEmpty()) View.GONE else View.VISIBLE
        importButton.isEnabled = !session.loading
        connectButton.isEnabled = session.profile != null && !session.loading
    }

    private companion object {
        const val OPEN_CONFIG = 1
    }
}

private class ImportedProfile(val name: String, val config: AwgConfig)

// Retain the selected profile and in-flight read across rotation, without serializing credentials.
private class ImportSession {
    private val main = Handler(Looper.getMainLooper())
    var profile: ImportedProfile? = null
        private set
    var loading = false
        private set
    var feedback: Int? = null
    var errorLine: Int? = null
    var onChange: (() -> Unit)? = null

    fun load(resolver: ContentResolver, uri: Uri) {
        if (loading) return
        loading = true
        feedback = R.string.importing_profile
        errorLine = null
        onChange?.invoke()
        Thread {
            var imported: ImportedProfile? = null
            var message = R.string.profile_imported
            var line: Int? = null
            try {
                val name = profileName(resolver, uri)
                if (name == null) {
                    message = R.string.conf_extension_required
                } else {
                    val config = resolver.openInputStream(uri)?.use(AwgConfigParser::parse)
                        ?: throw IOException()
                    imported = ImportedProfile(name, config)
                }
            } catch (error: ConfigFormatException) {
                message = R.string.invalid_config
                line = error.line
            } catch (_: CharacterCodingException) {
                message = R.string.invalid_config
            } catch (_: IOException) {
                message = R.string.cannot_read_config
            } catch (_: SecurityException) {
                message = R.string.cannot_read_config
            } catch (_: IllegalArgumentException) {
                message = R.string.cannot_read_config
            }
            main.post {
                imported?.let { profile = it }
                feedback = message
                errorLine = line
                loading = false
                onChange?.invoke()
            }
        }.start()
    }

    private fun profileName(resolver: ContentResolver, uri: Uri): String? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            } ?: return null
        if (!name.endsWith(".conf", ignoreCase = true)) return null
        return name.dropLast(5)
            .filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
            .trim().take(100).takeIf { it.isNotEmpty() }
    }
}
