package com.kachur.vpn

import android.content.ContentResolver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import java.io.IOException
import java.nio.charset.CharacterCodingException

internal class ImportedProfile(val name: String, val config: AwgConfig)

// Retain the profile and in-flight read across rotation without serializing credentials.
internal class ImportSession : ViewModel() {
    private val main = Handler(Looper.getMainLooper())
    var profile: ImportedProfile? = null
        private set
    private val history = mutableListOf<Int>()
    val stages: List<Int> get() = history
    var reading = false
        private set
    var picking = false
        private set
    val busy: Boolean get() = picking || reading
    var feedback: Int? = null
        private set
    var errorLine: Int? = null
        private set
    var onChange: (() -> Unit)? = null

    fun showMessage(message: Int, line: Int? = null) {
        feedback = message
        errorLine = line
        onChange?.invoke()
    }

    fun startPicker() {
        history.clear()
        feedback = null
        errorLine = null
        picking = true
        record(R.string.import_picker_opening)
    }

    fun record(stage: Int) {
        history.add(stage)
        onChange?.invoke()
    }

    fun finish(message: Int, line: Int? = null) {
        picking = false
        reading = false
        showMessage(message, line)
    }

    fun load(resolver: ContentResolver, uri: Uri, fallbackName: String) {
        if (reading) return
        picking = false
        reading = true
        record(R.string.import_document_opening)
        Thread {
            var imported: ImportedProfile? = null
            var message = R.string.profile_imported
            var line: Int? = null
            var accessError = R.string.cannot_open_config
            try {
                val stream = resolver.openInputStream(uri) ?: throw IOException()
                accessError = R.string.cannot_read_config
                main.post { record(R.string.import_document_reading) }
                val source = stream.use(AwgConfigParser::read)
                main.post { record(R.string.import_config_parsing) }
                accessError = R.string.config_parse_failed
                val config = AwgConfigParser.parse(source)

                main.post { record(R.string.import_profile_naming) }
                val name = documentName(resolver, uri)
                if (name == null) main.post { record(R.string.import_profile_default_name) }
                imported = ImportedProfile(name ?: fallbackName, config)
            } catch (error: ConfigFormatException) {
                line = error.line
                message = when (error.problem) {
                    ConfigProblem.TOO_LARGE -> R.string.config_too_large
                    ConfigProblem.MISSING_INTERFACE -> R.string.config_missing_interface
                    ConfigProblem.MISSING_PEER -> R.string.config_missing_peer
                    ConfigProblem.MISSING_PRIVATE_KEY -> R.string.config_missing_private_key
                    ConfigProblem.MISSING_PUBLIC_KEY -> R.string.config_missing_public_key
                    null -> when {
                        error.unsupportedOption -> R.string.unsupported_config_option
                        line != null -> R.string.invalid_config_line
                        else -> R.string.invalid_config
                    }
                }
            } catch (_: CharacterCodingException) {
                message = R.string.invalid_config_encoding
            } catch (_: SecurityException) {
                message = R.string.config_access_denied
            } catch (_: IOException) {
                message = accessError
            } catch (_: RuntimeException) {
                // Provider exception messages can contain the URI.
                message = accessError
            }
            main.post {
                imported?.let {
                    profile = it
                    history.add(R.string.import_profile_displayed)
                }
                finish(message, line)
            }
        }.start()
    }

    private fun documentName(resolver: ContentResolver, uri: Uri): String? {
        val name = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        } catch (_: RuntimeException) {
            // A readable document may have no queryable display name.
            null
        } ?: return null

        val trimmed = name.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }.trim()
        val title = if (trimmed.endsWith(".conf", ignoreCase = true)) trimmed.dropLast(5).trimEnd() else trimmed
        return title.take(100).takeIf { it.isNotEmpty() }
    }
}
