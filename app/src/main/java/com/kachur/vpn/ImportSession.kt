package com.kachur.vpn

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.util.UUID

internal class ImportedProfile(val name: String, val config: AwgConfig, val documentKey: String)
internal class ProfileConnectionRequest(val id: String, val stopGeneration: Long)

// Retain the profile and in-flight read across rotation without serializing credentials.
internal class ImportSession : ViewModel() {
    private val main = Handler(Looper.getMainLooper())
    var catalog = ProfileCatalog.EMPTY
        private set
    val profile: ImportedProfile? get() = catalog.selected
    private val history = mutableListOf<Int>()
    val stages: List<Int> get() = history
    var reading = false
        private set
    var restoring = false
        private set
    var picking = false
        private set
    private var editing = false
    val busy: Boolean get() = picking || reading || restoring || editing
    var openChecked = false
    private var revision = -1L
    val savedRevision: Long get() = revision
    var profileToConnect: ProfileConnectionRequest? = null
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

    fun syncSavedProfile(context: Context) {
        val store = context.localProfiles()
        val expected = store.revision()
        if (busy || revision == expected) return
        if (!store.exists()) {
            catalog = ProfileCatalog.EMPTY
            revision = expected
            return
        }
        restoring = true
        Thread {
            val saved = try { store.read() } catch (_: Exception) { null }
            main.post {
                restoring = false
                if (store.revision() != (saved?.revision ?: expected)) {
                    syncSavedProfile(context)
                    onChange?.invoke()
                } else {
                    if (saved == null) {
                        revision = expected
                        catalog = ProfileCatalog.EMPTY
                        showMessage(R.string.profile_restore_error)
                    } else {
                        apply(saved)
                        onChange?.invoke()
                    }
                }
            }
        }.start()
    }

    fun handlePickerResult(context: Context, resultCode: Int, data: Intent?) {
        record(R.string.import_result_received)
        if (resultCode != Activity.RESULT_OK) {
            finish(if (resultCode == Activity.RESULT_CANCELED) R.string.import_cancelled else R.string.unexpected_picker_result)
            return
        }
        val clip = data?.clipData
        val count = clip?.itemCount ?: 0
        if (count > ProfileCatalog.MAX_PROFILES) {
            finish(R.string.profiles_limit)
            return
        }
        val uris = buildSet {
            data?.data?.let { add(it) }
            if (clip != null) for (i in 0 until count) {
                val uri = clip.getItemAt(i).uri
                if (uri == null) {
                    finish(R.string.unsupported_document_uri)
                    return
                }
                add(uri)
            }
        }
        when {
            uris.isEmpty() -> finish(R.string.no_document_returned)
            uris.any { it.scheme != ContentResolver.SCHEME_CONTENT } -> finish(R.string.unsupported_document_uri)
            uris.size > ProfileCatalog.MAX_PROFILES -> finish(R.string.profiles_limit)
            else -> load(context, uris)
        }
    }

    private fun load(context: Context, uris: Set<Uri>) {
        if (reading) return
        val resolver = context.contentResolver
        picking = false
        reading = true
        record(R.string.import_document_opening)
        Thread {
            var imported: StoredProfiles? = null
            var message = if (uris.size == 1) R.string.profile_imported else R.string.profiles_imported
            var line: Int? = null
            var accessError = R.string.cannot_open_config
            try {
                val profiles = uris.map { uri ->
                    accessError = R.string.cannot_open_config
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
                    val id = (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "")
                    ImportedProfile(name ?: context.getString(R.string.unnamed_profile), config, id)
                }
                accessError = R.string.profiles_store_error
                imported = context.localProfiles().append(profiles)
            } catch (_: ProfileLimitException) {
                message = R.string.profiles_limit
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
                message = if (accessError == R.string.profiles_store_error) accessError else R.string.invalid_config_encoding
            } catch (_: SecurityException) {
                message = if (accessError == R.string.profiles_store_error) accessError else R.string.config_access_denied
            } catch (_: IOException) {
                message = accessError
            } catch (_: Exception) {
                // Provider exception messages can contain the URI.
                message = accessError
            }
            main.post {
                imported?.let {
                    apply(it)
                    history.add(R.string.import_profile_displayed)
                }
                finish(message, line)
            }
        }.start()
    }

    fun edit(context: Context, connection: ProfileConnectionRequest? = null, change: (LocalProfiles) -> StoredProfiles) {
        if (busy) return
        editing = true
        onChange?.invoke()
        Thread {
            val result = try { change(context.localProfiles()) } catch (_: Exception) { null }
            main.post {
                editing = false
                if (result == null) {
                    showMessage(R.string.profiles_store_error)
                } else {
                    apply(result)
                    profileToConnect = connection
                    feedback = null
                    errorLine = null
                    onChange?.invoke()
                }
            }
        }.start()
    }

    fun consumeConnection(): ProfileConnectionRequest? = profileToConnect.also { profileToConnect = null }

    private fun apply(state: StoredProfiles) {
        catalog = state.catalog
        revision = state.revision
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

internal fun profileDocumentPicker(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
    addCategory(Intent.CATEGORY_OPENABLE)
    type = "*/*"
    putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
