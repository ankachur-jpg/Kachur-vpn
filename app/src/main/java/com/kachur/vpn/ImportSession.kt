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
    var loading = false
        private set
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

    fun load(resolver: ContentResolver, uri: Uri, fallbackName: String) {
        if (loading) return
        loading = true
        showMessage(R.string.importing_profile)
        Thread {
            var imported: ImportedProfile? = null
            var message = R.string.profile_imported
            var line: Int? = null
            try {
                val config = resolver.openInputStream(uri)?.use(AwgConfigParser::parse) ?: throw IOException()
                imported = ImportedProfile(documentName(resolver, uri) ?: fallbackName, config)
            } catch (error: ConfigFormatException) {
                line = error.line
                message = when {
                    error.unsupportedOption -> R.string.unsupported_config_option
                    line != null -> R.string.invalid_config_line
                    else -> R.string.invalid_config
                }
            } catch (_: CharacterCodingException) {
                message = R.string.invalid_config_encoding
            } catch (_: IOException) {
                message = R.string.cannot_read_config
            } catch (_: RuntimeException) {
                // Providers can fail with unchecked exceptions. Their messages can contain the URI.
                message = R.string.cannot_read_config
            }
            main.post {
                imported?.let { profile = it }
                loading = false
                showMessage(message, line)
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
