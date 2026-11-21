package com.kachur.vpn

import android.app.Activity
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.TextView
import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigImportActivityTest {
    @Test
    fun opensDocumentAndShowsProfileFromClipDataWithoutDisplayMetadataAfterRecreation() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            registerDocument(activity, validConfig(), metadataFails = true)
            val request = launchPicker(activity)
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
            assertTrue(request.intent.hasCategory(Intent.CATEGORY_OPENABLE))
            assertTrue(request.intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))

            controller.recreate()
            val recreated = controller.get()
            val result = Intent().apply { clipData = ClipData.newRawUri("Test document", document) }
            assertTrue(recreated.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, result))
            awaitFeedback(recreated, R.string.profile_imported)
            assertEquals(recreated.getString(R.string.unnamed_profile), text(recreated, R.id.profile_name))

            controller.recreate()
            val rotated = controller.get()
            assertEquals(rotated.getString(R.string.unnamed_profile), text(rotated, R.id.profile_name))
            assertEquals(rotated.getString(R.string.profile_imported), text(rotated, R.id.import_feedback))
            assertStages(
                rotated, R.string.import_picker_opening, R.string.import_result_received,
                R.string.import_document_opening, R.string.import_document_reading,
                R.string.import_config_parsing, R.string.import_profile_naming,
                R.string.import_profile_default_name, R.string.import_profile_displayed,
            )
        }
    }

    @Test
    fun showsCancellationAndMissingPickerResult() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val request = launchPicker(activity)
            assertTrue(activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_CANCELED, null))
            assertEquals(activity.getString(R.string.import_cancelled), text(activity, R.id.import_feedback))
            assertStages(activity, R.string.import_picker_opening, R.string.import_result_received)
            assertTrue(activity.findViewById<Button>(R.id.import_profile_button).isEnabled)

            val next = launchPicker(activity)
            assertTrue(activity.activityResultRegistry.dispatchResult(next.requestCode, Activity.RESULT_OK, null))
            assertEquals(activity.getString(R.string.no_document_returned), text(activity, R.id.import_feedback))
        }
    }

    @Test
    fun allowsRetryWhenActivityResumesWithoutPickerCallback() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            launchPicker(activity)
            controller.pause().resume().postResume()

            assertEquals(activity.getString(R.string.picker_no_callback), text(activity, R.id.import_feedback))
            assertStages(activity, R.string.import_picker_opening)
            assertTrue(activity.findViewById<Button>(R.id.import_profile_button).isEnabled)
        }
    }

    @Test
    fun showsRedactedParserAndReadErrorsAndKeepsPreviousProfile() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            // Some document providers supply a display name without the .conf suffix.
            registerDocument(activity, validConfig(), displayName = "Test profile")
            deliverDocument(activity)
            awaitFeedback(activity, R.string.profile_imported)
            assertEquals("Test profile", text(activity, R.id.profile_name))

            val invalidValue = "synthetic-sensitive-value"
            registerDocument(activity, validConfig().replace("Jc = 4", "Jc = $invalidValue"))
            deliverDocument(activity)
            awaitFeedback(activity, R.string.invalid_config_line, 3)
            assertFalse(text(activity, R.id.import_feedback).contains(invalidValue))
            assertFalse(text(activity, R.id.import_diagnostics).contains(invalidValue))
            assertStages(
                activity, R.string.import_picker_opening, R.string.import_result_received,
                R.string.import_document_opening, R.string.import_document_reading, R.string.import_config_parsing,
            )
            assertEquals("Test profile", text(activity, R.id.profile_name))

            registerDocument(activity, validConfig(), openFails = true)
            deliverDocument(activity)
            awaitFeedback(activity, R.string.cannot_open_config)
            assertStages(
                activity, R.string.import_picker_opening, R.string.import_result_received,
                R.string.import_document_opening,
            )
            assertEquals("Test profile", text(activity, R.id.profile_name))
            assertTrue(activity.findViewById<Button>(R.id.import_profile_button).isEnabled)
        }
    }

    @Test
    fun distinguishesUnreadableTextFromMissingConfigSections() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            registerDocument(activity, "", bytes = byteArrayOf(0xC3.toByte(), 0x28))
            deliverDocument(activity)
            awaitFeedback(activity, R.string.invalid_config_encoding)
            assertStages(
                activity, R.string.import_picker_opening, R.string.import_result_received,
                R.string.import_document_opening, R.string.import_document_reading,
            )

            registerDocument(activity, "# Empty export")
            deliverDocument(activity)
            awaitFeedback(activity, R.string.config_missing_interface)
            assertStages(
                activity, R.string.import_picker_opening, R.string.import_result_received,
                R.string.import_document_opening, R.string.import_document_reading, R.string.import_config_parsing,
            )
            assertEquals(activity.getString(R.string.no_profile), text(activity, R.id.profile_name))
        }
    }

    private fun assertStages(activity: MainActivity, vararg stages: Int) {
        assertEquals(stages.joinToString("\n") { activity.getString(it) }, text(activity, R.id.import_diagnostics))
    }

    private fun deliverDocument(activity: MainActivity) {
        val request = launchPicker(activity)
        assertTrue(activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent().setData(document)))
    }

    private fun launchPicker(activity: MainActivity): ShadowActivity.IntentForResult {
        activity.findViewById<Button>(R.id.import_profile_button).performClick()
        return shadowOf(activity).nextStartedActivityForResult
    }

    private fun text(activity: MainActivity, id: Int): String = activity.findViewById<TextView>(id).text.toString()

    private fun awaitFeedback(activity: MainActivity, resource: Int, vararg args: Any) {
        val expected = activity.getString(resource, *args)
        val deadline = System.nanoTime() + 5_000_000_000L
        val main = shadowOf(Looper.getMainLooper())
        do {
            main.idle()
            if (text(activity, R.id.import_feedback) == expected) {
                assertEquals(View.VISIBLE, activity.findViewById<TextView>(R.id.import_feedback).visibility)
                return
            }
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        assertEquals(expected, text(activity, R.id.import_feedback))
    }

    private fun registerDocument(
        activity: MainActivity,
        content: String,
        displayName: String = "Test profile.conf",
        metadataFails: Boolean = false,
        openFails: Boolean = false,
        bytes: ByteArray = content.toByteArray(),
    ) {
        val fixture = File.createTempFile("import-test-", ".conf", activity.cacheDir).apply { writeBytes(bytes) }
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "application/octet-stream"

            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                if (openFails) throw IllegalStateException("synthetic provider error")
                assertEquals("r", mode)
                return ParcelFileDescriptor.open(fixture, ParcelFileDescriptor.MODE_READ_ONLY)
            }

            override fun query(
                uri: Uri,
                projection: Array<out String>?,
                selection: String?,
                selectionArgs: Array<out String>?,
                sortOrder: String?,
            ): Cursor {
                if (metadataFails) throw UnsupportedOperationException("synthetic metadata error")
                return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(displayName)) }
            }

            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(activity.applicationContext, ProviderInfo().apply {
            authority = AUTHORITY
            applicationInfo = activity.applicationInfo
        })
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
    }

    private fun validConfig(): String {
        fun dummy(byte: Byte) = Base64.getEncoder().encodeToString(ByteArray(32) { byte })
        return "[Interface]\nPrivateKey = ${dummy(0)}\nJc = 4\n[Peer]\nPublicKey = ${dummy(1)}"
    }

    private companion object {
        const val AUTHORITY = "com.kachur.vpn.test.documents"
        val document: Uri = Uri.parse("content://$AUTHORITY/document/test")
    }
}
