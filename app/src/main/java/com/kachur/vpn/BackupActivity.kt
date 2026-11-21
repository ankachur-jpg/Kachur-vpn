package com.kachur.vpn

import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.io.IOException
import java.util.concurrent.Executors
import javax.crypto.AEADBadTagException

class BackupActivity : KachurActivity() {
    private val session by lazy { ViewModelProvider(this)[BackupSession::class.java] }
    private lateinit var export: Button
    private lateinit var restore: Button
    private lateinit var confirm: Button
    private lateinit var discard: Button
    private lateinit var status: TextView
    private lateinit var preview: TextView
    private var resumed = false
    private val observer: () -> Unit = ::render

    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri?.scheme == ContentResolver.SCHEME_CONTENT) session.writeExport(applicationContext, uri)
        else session.cancelExport(R.string.backup_cancelled)
    }
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri?.scheme == ContentResolver.SCHEME_CONTENT) askPassword(false) { session.read(applicationContext, uri, it) }
        else session.status(R.string.backup_cancelled)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val layout = uiColumn(18)
        layout.uiAdd(uiHeader(R.string.backup_title) { finish() })
        layout.uiAdd(uiCard().apply {
            uiAdd(uiCaption(R.string.backup_description))
            export = uiButton(R.string.backup_export, primary = true).apply {
                id = R.id.backup_export
                setOnClickListener { askPassword(true) { session.prepareExport(applicationContext, it) } }
            }
            restore = uiButton(R.string.backup_open).apply {
                id = R.id.backup_open
                setOnClickListener {
                    try { openDocument.launch(arrayOf("*/*")) } catch (_: RuntimeException) { session.status(R.string.backup_file_error) }
                }
            }
            uiAdd(export, 14)
            uiAdd(restore, 8)
        }, 16)
        layout.uiAdd(uiCard().apply {
            status = uiLabel(16).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
            preview = uiLabel(16).apply { id = R.id.backup_preview }
            confirm = uiButton(R.string.backup_confirm, primary = true).apply {
                id = R.id.backup_confirm
                setOnClickListener { if (VpnConnection.phase == VpnPhase.IDLE) session.applyRestore(applicationContext) }
            }
            discard = uiButton(android.R.string.cancel).apply { setOnClickListener { session.discard() } }
            uiAdd(status)
            uiAdd(preview, 8)
            uiAdd(confirm, 10)
            uiAdd(discard, 8)
        }, 14)
        setContentView(uiScroll(layout).apply { uiInsets() })
        render()
    }

    override fun onStart() {
        super.onStart()
        session.onChange = observer
        VpnConnection.observe(observer)
        render()
    }

    override fun onResume() { super.onResume(); resumed = true; render() }
    override fun onPause() { resumed = false; super.onPause() }

    override fun onStop() {
        VpnConnection.removeObserver(observer)
        session.onChange = null
        super.onStop()
    }

    private fun render() {
        if (resumed && session.beginExportPicker()) {
            try { createDocument.launch("kachur-vpn.kvb") } catch (_: RuntimeException) { session.cancelExport(R.string.backup_file_error) }
        }
        val pending = session.preview
        val visible = if (pending == null) View.GONE else View.VISIBLE
        export.isEnabled = !session.busy && pending == null
        restore.isEnabled = export.isEnabled
        status.setText(session.message)
        preview.visibility = visible
        confirm.visibility = visible
        discard.visibility = visible
        confirm.isEnabled = !session.busy && VpnConnection.phase == VpnPhase.IDLE
        discard.isEnabled = !session.busy
        if (pending != null) {
            preview.text = getString(R.string.backup_preview, pending.profiles.entries.size,
                pending.manualRoutes.size, pending.apps.size, pending.sources.size) +
                if (VpnConnection.phase != VpnPhase.IDLE) "\n" + getString(R.string.backup_disconnect_first) else ""
        }
    }

    private fun askPassword(creating: Boolean, action: (CharArray) -> Unit) {
        fun field(hint: Int) = EditText(this).apply {
            uiField()
            setHint(hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            isSaveEnabled = false
            filters = arrayOf(InputFilter.LengthFilter(128))
        }
        val first = field(R.string.backup_password)
        val repeated = if (creating) field(R.string.backup_password_repeat) else null
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(first)
            if (repeated != null) addView(repeated)
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.backup_password).setView(form)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).show()
        dialog.setOnDismissListener { first.text.clear(); repeated?.text?.clear() }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val password = CharArray(first.text.length) { first.text[it] }
            val confirmation = repeated?.let { field -> CharArray(field.text.length) { field.text[it] } }
            val valid = password.isNotEmpty() && (!creating ||
                (password.size >= BackupCipher.MIN_PASSWORD && confirmation != null && password.contentEquals(confirmation)))
            confirmation?.fill('\u0000')
            if (valid) {
                dialog.dismiss()
                action(password)
            } else {
                password.fill('\u0000')
                first.error = getString(if (creating) R.string.backup_password_mismatch else R.string.backup_password)
            }
        }
    }
}

internal class BackupSession : ViewModel() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var cleared = false
    private var processing = false
    private var payload: ByteArray? = null
    private var picker = false
    val busy: Boolean get() = processing || payload != null
    var onChange: (() -> Unit)? = null
    var message = R.string.backup_ready
        private set
    var preview: VpnBackup? = null
        private set

    fun status(label: Int) { message = label; onChange?.invoke() }

    fun prepareExport(context: Context, password: CharArray) {
        if (busy) { password.fill('\u0000'); return }
        submit({
            try {
                val plain = BackupJson.encode(VpnBackupStore(context).snapshot())
                try { BackupCipher.encrypt(plain, password) } finally { plain.fill(0) }
            } finally { password.fill('\u0000') }
        }) { output -> payload = output; status(R.string.backup_choose_destination) }
    }

    fun beginExportPicker(): Boolean {
        if (payload == null || picker) return false
        picker = true
        return true
    }

    fun cancelExport(label: Int) { payload = null; picker = false; status(label) }

    fun writeExport(context: Context, uri: Uri) {
        val output = payload
        payload = null
        picker = false
        if (output == null) { status(R.string.backup_interrupted); return }
        submit({
            val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException()
            stream.use { it.write(output) }
        }) { status(R.string.backup_saved) }
    }

    fun read(context: Context, uri: Uri, password: CharArray) {
        if (busy) { password.fill('\u0000'); return }
        submit({
            try {
                val stream = context.contentResolver.openInputStream(uri) ?: throw IOException()
                val plain = BackupCipher.decrypt(stream.use(::readBackupFile), password)
                try { BackupJson.decode(plain) } finally { plain.fill(0) }
            } finally { password.fill('\u0000') }
        }) { snapshot -> preview = snapshot; status(R.string.backup_confirm_note) }
    }

    fun applyRestore(context: Context) {
        val pending = preview ?: return
        if (busy) return
        submit({ VpnBackupStore(context).restore(pending) }) { scheduled ->
            preview = null
            status(if (scheduled) R.string.backup_restored else R.string.backup_restored_schedule)
        }
    }

    fun discard() { preview = null; status(R.string.backup_cancelled) }

    private fun <T> submit(work: () -> T, done: (T) -> Unit) {
        processing = true
        status(R.string.backup_working)
        worker.execute {
            val result = runCatching(work)
            main.post {
                if (cleared) return@post
                processing = false
                result.fold(done) { error -> status(when (error) {
                    is BackupFileException -> error.label
                    is ProfileLimitException -> R.string.profiles_limit
                    is AEADBadTagException -> R.string.backup_auth_error
                    is IOException, is SecurityException -> R.string.backup_file_error
                    else -> R.string.backup_format_error
                }) }
            }
        }
    }

    override fun onCleared() {
        cleared = true
        preview = null
        payload = null
        worker.shutdown()
        main.removeCallbacksAndMessages(null)
    }
}
