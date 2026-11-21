package com.kachur.vpn

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.ContentProvider
import android.content.Context
import android.content.ContextWrapper
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.net.VpnService
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.TextView
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
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

    @Test
    fun connectionButtonRequiresProfileAndStatusFollowsService() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val connect = activity.findViewById<Button>(R.id.connect_vpn_button)
            assertTrue(connect.performClick())
            assertEquals(activity.getString(R.string.vpn_profile_required), text(activity, R.id.connection_status))

            registerDocument(activity, validConfig())
            deliverDocument(activity)
            awaitFeedback(activity, R.string.profile_imported)
            val diagnostics = text(activity, R.id.import_diagnostics)
            try {
                VpnConnection.publish(VpnPhase.WAITING, R.string.vpn_waiting_handshake)
                assertEquals(activity.getString(R.string.vpn_waiting_handshake), text(activity, R.id.connection_status))
                assertEquals(activity.getString(R.string.vpn_disconnect), connect.text.toString())
                assertEquals(diagnostics, text(activity, R.id.import_diagnostics))

                controller.recreate()
                val recreated = controller.get()
                assertEquals(recreated.getString(R.string.vpn_waiting_handshake), text(recreated, R.id.connection_status))
                VpnConnection.publish(VpnPhase.HANDSHAKE, R.string.vpn_handshake_received)
                assertEquals(recreated.getString(R.string.vpn_handshake_received), text(recreated, R.id.connection_status))
            } finally {
                VpnConnection.publish(VpnPhase.IDLE, R.string.vpn_disconnected)
            }
        }
    }

    @Test
    @Config(shadows = [VpnConsentShadow::class])
    fun deniedSystemConsentDoesNotStartVpn() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            registerDocument(activity, validConfig())
            deliverDocument(activity)
            awaitFeedback(activity, R.string.profile_imported)

            val connect = activity.findViewById<Button>(R.id.connect_vpn_button)
            val consent = requestVpnPermission(activity)
            assertEquals(VpnConsentShadow.ACTION, consent.intent.action)
            assertEquals(activity.getString(R.string.vpn_waiting_permission), text(activity, R.id.connection_status))
            assertFalse(connect.isEnabled)

            assertTrue(activity.activityResultRegistry.dispatchResult(consent.requestCode, Activity.RESULT_CANCELED, null))
            assertEquals(activity.getString(R.string.vpn_permission_denied), text(activity, R.id.connection_status))
            assertEquals(VpnPhase.IDLE, VpnConnection.phase)
            assertTrue(connect.isEnabled)
        }
    }

    @Test
    @Config(shadows = [VpnConsentShadow::class])
    fun grantedResultWaitsForResumeAndAllowsRetryIfServiceNeverStarts() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            registerDocument(activity, validConfig())
            deliverDocument(activity)
            awaitFeedback(activity, R.string.profile_imported)
            val connect = activity.findViewById<Button>(R.id.connect_vpn_button)
            try {
                val consent = requestVpnPermission(activity)
                controller.pause()
                VpnConsentShadow.granted = true
                assertTrue(activity.activityResultRegistry.dispatchResult(consent.requestCode, Activity.RESULT_OK, null))
                assertEquals(VpnPhase.IDLE, VpnConnection.phase)

                controller.resume().postResume()
                awaitDiagnostic(activity, R.string.vpn_service_launch_accepted)
                assertEquals(VpnPhase.STARTING, VpnConnection.phase)
                assertEquals(activity.getString(R.string.vpn_cancel_start), connect.text.toString())
                assertTrue(connect.isEnabled)
                assertTrue(text(activity, R.id.connection_diagnostics).contains("permissionPending · false"))
                awaitDiagnostic(activity, R.string.vpn_service_awaiting_reply)

                shadowOf(Looper.getMainLooper()).idleFor(VpnConnection.STARTUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                assertEquals(activity.getString(R.string.vpn_service_timeout), text(activity, R.id.connection_status))
                assertEquals(activity.getString(R.string.connect_vpn), connect.text.toString())
                assertTrue(connect.isEnabled)
                awaitDiagnostic(activity, R.string.vpn_service_no_reply)
                resetButton(activity).performClick()
                awaitDiagnostic(activity, R.string.vpn_service_timeout)
                assertEquals(activity.getString(R.string.profile_imported), text(activity, R.id.import_feedback))
            } finally {
                VpnConsentShadow.granted = false
                VpnConnection.reset()
            }
        }
    }

    @Test
    @Config(shadows = [VpnConsentShadow::class])
    fun allowsRetryAfterReopeningWithAnUnansweredPermissionRequest() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            registerDocument(activity, validConfig())
            deliverDocument(activity)
            awaitFeedback(activity, R.string.profile_imported)

            val consent = requestVpnPermission(activity)
            assertEquals(VpnConsentShadow.ACTION, consent.intent.action)
            controller.recreate()
            val reopened = controller.get()
            awaitDiagnostic(reopened, R.string.vpn_permission_no_result)
            assertEquals(reopened.getString(R.string.vpn_permission_no_result), text(reopened, R.id.connection_status))
            assertTrue(reopened.findViewById<Button>(R.id.connect_vpn_button).isEnabled)
            assertEquals(VpnPhase.IDLE, VpnConnection.phase)
        }
    }

    @Test
    fun reopenedActivityKeepsReceivingUpdatesWhenPreviousActivityStops() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { first ->
            Robolectric.buildActivity(MainActivity::class.java).setup().use { second ->
                first.pause().stop()
                val reopened = second.get()
                try {
                    VpnConnection.publish(VpnPhase.STARTING, R.string.vpn_service_started)
                    assertEquals(reopened.getString(R.string.vpn_service_started), text(reopened, R.id.connection_status))
                    VpnConnection.publish(VpnPhase.IDLE, R.string.vpn_start_error)
                    assertEquals(reopened.getString(R.string.vpn_start_error), text(reopened, R.id.connection_status))
                    assertTrue(reopened.findViewById<Button>(R.id.connect_vpn_button).isEnabled)
                } finally {
                    VpnConnection.publish(VpnPhase.IDLE, R.string.vpn_disconnected)
                }
            }
        }
    }

    @Test
    fun serviceLaunchRejectionReturnsToIdleAndDropsPendingProfile() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val rejectingContext = object : ContextWrapper(activity.applicationContext) {
                override fun startForegroundService(service: Intent): ComponentName {
                    assertEquals(AwgVpnService::class.java.name, service.component?.className)
                    assertEquals(setOf(VpnConnection.EXTRA_ATTEMPT), service.extras?.keySet())
                    throw SecurityException("synthetic system error")
                }
            }
            VpnConnection.start(rejectingContext, AwgConfigParser.parse(validConfig()))
            awaitDiagnostic(activity, R.string.vpn_service_rejected)
            assertFalse(text(activity, R.id.connection_diagnostics).contains("synthetic system error"))
            assertEquals(VpnPhase.IDLE, VpnConnection.phase)
            assertEquals(activity.getString(R.string.vpn_service_rejected), text(activity, R.id.connection_status))
            assertTrue(activity.findViewById<Button>(R.id.connect_vpn_button).isEnabled)
        }
    }

    @Test
    @Config(shadows = [VpnConsentShadow::class])
    fun diagnosticsAndResetRemainUsableWhileAndroidPermissionCheckDoesNotReturn() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val panel = activity.findViewById<TextView>(R.id.connection_diagnostics)
            assertEquals(View.VISIBLE, panel.visibility)
            assertTrue(panel.text.contains("Событие № %d".format(VpnConnection.eventNumber)))
            registerDocument(activity, validConfig())
            deliverDocument(activity)
            awaitFeedback(activity, R.string.profile_imported)
            val before = VpnConnection.eventNumber
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            VpnConsentShadow.prepareEntered = entered
            VpnConsentShadow.prepareRelease = release
            val connect = activity.findViewById<Button>(R.id.connect_vpn_button)
            try {
                assertTrue(connect.performClick())
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals(View.VISIBLE, panel.visibility)
                assertTrue(panel.text.contains("permissionPending · false"))
                assertTrue(panel.text.contains(activity.getString(R.string.vpn_blocked_permission_check)))
                assertTrue(VpnConnection.eventNumber > before)
                assertFalse(connect.isEnabled)

                assertTrue(resetButton(activity).performClick())
                assertEquals(activity.getString(R.string.vpn_reset_done), text(activity, R.id.connection_status))
                assertTrue(panel.text.contains(activity.getString(R.string.vpn_button_available)))
                assertTrue(connect.isEnabled)
                assertEquals("Test profile", text(activity, R.id.profile_name))
                assertEquals(activity.getString(R.string.profile_imported), text(activity, R.id.import_feedback))
            } finally {
                release.countDown()
                VpnConsentShadow.prepareEntered = null
                VpnConsentShadow.prepareRelease = null
            }
        }
    }

    @Test
    @Config(shadows = [VpnConsentShadow::class])
    fun resettingPendingDialogPreventsItsLateActivityResultFromConsumingRetry() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            registerDocument(activity, validConfig())
            deliverDocument(activity)
            awaitFeedback(activity, R.string.profile_imported)
            val connect = activity.findViewById<Button>(R.id.connect_vpn_button)

            val cancelled = requestVpnPermission(activity)
            assertTrue(text(activity, R.id.connection_diagnostics).contains("permissionPending · true"))
            awaitDiagnostic(activity, R.string.vpn_blocked_permission_result)
            assertFalse(connect.isEnabled)
            assertTrue(resetButton(activity).performClick())
            assertTrue(connect.isEnabled)
            assertEquals("Test profile", text(activity, R.id.profile_name))

            val retry = requestVpnPermission(activity)
            assertTrue(cancelled.requestCode != retry.requestCode)
            activity.activityResultRegistry.dispatchResult(cancelled.requestCode, Activity.RESULT_CANCELED, null)
            assertEquals(activity.getString(R.string.vpn_waiting_permission), text(activity, R.id.connection_status))
            assertFalse(connect.isEnabled)

            assertTrue(activity.activityResultRegistry.dispatchResult(retry.requestCode, Activity.RESULT_CANCELED, null))
            assertTrue(connect.isEnabled)
            awaitDiagnostic(activity, R.string.vpn_permission_denied)
        }
    }

    @Test
    fun resetOfClaimedServiceWaitsForCleanupAndIgnoresLateStartup() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            registerDocument(activity, validConfig())
            deliverDocument(activity)
            awaitFeedback(activity, R.string.profile_imported)
            val launchId = AtomicReference<Long>()
            val capturingContext = object : ContextWrapper(activity.applicationContext) {
                override fun startForegroundService(service: Intent): ComponentName {
                    launchId.set(service.getLongExtra(VpnConnection.EXTRA_ATTEMPT, 0L))
                    return checkNotNull(service.component)
                }
            }
            val config = AwgConfigParser.parse(validConfig())
            VpnConnection.start(capturingContext, config)
            awaitDiagnostic(activity, R.string.vpn_service_launch_accepted)
            val id = checkNotNull(launchId.get())
            var cancellations = 0
            assertTrue(VpnConnection.claim(id) { cancellations++ } === config)
            VpnConnection.foregroundReady(id)
            awaitDiagnostic(activity, R.string.vpn_service_foreground)

            resetButton(activity).performClick()
            assertEquals(1, cancellations)
            assertEquals(VpnPhase.STOPPING, VpnConnection.phase)
            awaitDiagnostic(activity, R.string.vpn_blocked_stopping)
            VpnConnection.tunnelReady(id)
            assertEquals(VpnPhase.STOPPING, VpnConnection.phase)
            VpnConnection.serviceClosed(id, cleanupFailed = false)

            assertEquals(VpnPhase.IDLE, VpnConnection.phase)
            assertEquals(activity.getString(R.string.vpn_reset_done), text(activity, R.id.connection_status))
            assertTrue(activity.findViewById<Button>(R.id.connect_vpn_button).isEnabled)
            assertNull(VpnConnection.claim(id) { error("obsolete request") })
            assertEquals("Test profile", text(activity, R.id.profile_name))
        }
    }

    @Test
    @Config(shadows = [VpnConsentShadow::class])
    fun displaysSafeParserFailureAfterTheServiceStops() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val invalidValue = "4294967295"
            registerDocument(activity, validConfig().replace("Jc = 4", "Jc = $invalidValue"))
            deliverDocument(activity)
            awaitFeedback(activity, R.string.profile_imported)
            VpnConsentShadow.granted = true
            try {
                val connect = activity.findViewById<Button>(R.id.connect_vpn_button)
                assertTrue(connect.performClick())
                awaitDiagnostic(activity, R.string.vpn_service_launch_accepted)
                val serviceIntent = shadowOf(activity).nextStartedService
                val service = Robolectric.buildService(AwgVpnService::class.java, serviceIntent).create()
                val safeError = activity.getString(
                    R.string.vpn_config_error_parameter,
                    activity.getString(R.string.vpn_config_error_invalid_number),
                    "Jc",
                )
                try {
                    service.startCommand(0, 1)
                    awaitViewText(activity, R.id.connection_diagnostics, safeError)
                } finally {
                    service.destroy()
                }

                awaitDiagnostic(activity, R.string.vpn_service_closed)
                assertEquals(activity.getString(R.string.vpn_backend_invalid_profile), text(activity, R.id.connection_status))
                val diagnostics = text(activity, R.id.connection_diagnostics)
                assertTrue(diagnostics.contains(safeError))
                assertFalse(diagnostics.contains(invalidValue))
                assertTrue(connect.isEnabled)
            } finally {
                VpnConsentShadow.granted = false
                VpnConnection.reset()
            }
        }
    }

    private fun requestVpnPermission(activity: MainActivity): ShadowActivity.IntentForResult {
        assertTrue(activity.findViewById<Button>(R.id.connect_vpn_button).performClick())
        awaitViewText(activity, R.id.connection_status, activity.getString(R.string.vpn_waiting_permission))
        return shadowOf(activity).nextStartedActivityForResult
    }

    private fun resetButton(activity: MainActivity): Button = activity.findViewById(R.id.reset_vpn_button)

    private fun awaitDiagnostic(activity: MainActivity, message: Int) {
        awaitViewText(activity, R.id.connection_diagnostics, activity.getString(message))
    }

    private fun awaitViewText(activity: MainActivity, id: Int, expected: String) {
        val deadline = System.nanoTime() + 5_000_000_000L
        val main = shadowOf(Looper.getMainLooper())
        do {
            main.idle()
            if (text(activity, id).contains(expected)) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        assertTrue("Expected $expected in ${text(activity, id)}", text(activity, id).contains(expected))
    }

    @Implements(VpnService::class)
    class VpnConsentShadow {
        companion object {
            const val ACTION = "com.kachur.vpn.test.CONSENT"
            @Volatile var granted = false
            @Volatile var prepareEntered: CountDownLatch? = null
            @Volatile var prepareRelease: CountDownLatch? = null

            @JvmStatic
            @Implementation
            @Suppress("UNUSED_PARAMETER")
            fun prepare(context: Context): Intent? {
                val release = prepareRelease
                prepareEntered?.countDown()
                release?.await(5, TimeUnit.SECONDS)
                return if (granted) null else Intent(ACTION)
            }
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
