package com.kachur.vpn

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class ReleaseMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun exportsEncryptedBackupFromDebug() {
        assertPackage(debuggable = true)
        VpnLaunchSettings(context).apply {
            setEnabled(false)
            setPaused(true)
            setRecoveryEnabled(false)
        }
        val primary = fixture(PRIMARY_NAME, PRIMARY_ID)
        val selected = fixture(SELECTED_NAME, SELECTED_ID)
        context.localProfiles().append(listOf(primary, selected))
        context.localProfiles().select(SELECTED_ID)
        VpnProfileLabels(context).saveCountry(SELECTED_ID, COUNTRY)
        SplitTunnelingSettings(context).apply {
            setExcluded(EXCLUDED_APP, true)
            addIpRoutes(setOf(DIRECT_ROUTE))
        }
        assertTrue(profileKeyExists())

        val destination = File(context.cacheDir, ARCHIVE)
        withBackupSession { session ->
            instrumentation.runOnMainSync { session.prepareExport(context, testPassword()) }
            await(session, R.string.backup_choose_destination)
            instrumentation.runOnMainSync {
                assertTrue(session.beginExportPicker())
                session.writeExport(context, Uri.fromFile(destination))
            }
            await(session, R.string.backup_saved)
        }
        assertTrue(destination.length() > 40L)
        assertFalse(destination.readBytes().toString(Charsets.UTF_8).contains(SELECTED_NAME))
    }

    @Test
    fun restoresBackupIntoReinstalledRelease() {
        assertPackage(debuggable = false)
        assertFalse(context.localProfiles().exists())
        assertFalse(profileKeyExists())
        val archive = File(context.cacheDir, ARCHIVE)
        val shellCopy = instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/kachur-ci-migration.kvb")
        ParcelFileDescriptor.AutoCloseInputStream(shellCopy).use { external ->
            archive.outputStream().use { external.copyTo(it) }
        }
        try {
            val uri = Uri.fromFile(archive)
            withBackupSession { session ->
                instrumentation.runOnMainSync { session.read(context, uri, testPassword().apply { this[0] = 'z' }) }
                await(session, R.string.backup_auth_error)
                instrumentation.runOnMainSync {
                    assertNull(session.preview)
                    session.read(context, uri, testPassword())
                }
                await(session, R.string.backup_confirm_note)
                instrumentation.runOnMainSync {
                    assertEquals(2, session.preview?.profiles?.entries?.size)
                    assertFalse(context.localProfiles().exists())
                    session.applyRestore(context)
                }
                await(session, R.string.backup_restored)
            }
            assertRestoredData()
            assertTrue(context.getSharedPreferences("ci_release_migration", Context.MODE_PRIVATE)
                .edit().putBoolean("restore_verified", true).commit())
        } finally {
            archive.delete()
        }
    }

    @Test
    fun higherVersionKeepsRestoredData() {
        assertPackage(debuggable = false)
        assertTrue(context.getSharedPreferences("ci_release_migration", Context.MODE_PRIVATE)
            .getBoolean("restore_verified", false))
        assertRestoredData()
    }

    private fun assertPackage(debuggable: Boolean) {
        assertEquals("com.kachur.vpn", context.packageName)
        assertEquals(debuggable, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val arguments = InstrumentationRegistry.getArguments()
        assertEquals(arguments.getString("expectedVersionName"), info.versionName)
        assertEquals(arguments.getString("expectedVersionCode")?.toInt(), info.versionCode)
    }

    private fun assertRestoredData() {
        assertTrue(profileKeyExists())
        val catalog = context.localProfiles().read().catalog
        assertEquals(listOf(PRIMARY_NAME, SELECTED_NAME), catalog.entries.map { it.name })
        assertEquals(SELECTED_ID, catalog.selectedId)
        assertTrue(!catalog.selected?.config?.interfaceSection?.get("PrivateKey").isNullOrEmpty())
        assertEquals(COUNTRY, VpnProfileLabels(context).country(SELECTED_ID))
        val routing = SplitTunnelingSettings(context)
        assertEquals(setOf(EXCLUDED_APP), routing.excludedPackages())
        assertEquals(setOf(DIRECT_ROUTE), routing.manualIpRoutes())
        val launch = VpnLaunchSettings(context)
        assertFalse(launch.enabled)
        assertTrue(launch.paused)
        assertFalse(launch.recoveryEnabled)
    }

    private fun profileKeyExists(): Boolean = KeyStore.getInstance("AndroidKeyStore").run {
        load(null)
        containsAlias("KachurVPN.selected-profile.v1")
    }

    private fun fixture(name: String, id: String): ImportedProfile {
        fun key(seed: Byte) = Base64.getEncoder().encodeToString(ByteArray(32) { seed })
        val config = AwgConfigParser.parse("[Interface]\nPrivateKey=${key(4)}\n[Peer]\nPublicKey=${key(5)}")
        return ImportedProfile(name, config, id)
    }

    private fun testPassword() = CharArray(20) { 'a' + it }

    private fun withBackupSession(action: (BackupSession) -> Unit) {
        val store = ViewModelStore()
        lateinit var session: BackupSession
        instrumentation.runOnMainSync {
            session = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[BackupSession::class.java]
        }
        try {
            action(session)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    private fun await(session: BackupSession, expected: Int) {
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        var status = R.string.backup_ready
        do {
            instrumentation.runOnMainSync { status = session.message }
            if (status == expected) return
            Thread.sleep(60)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Expected ${context.getString(expected)}. Received ${context.getString(status)}")
    }

    private companion object {
        const val ARCHIVE = "ci-migration.kvb"
        const val PRIMARY_NAME = "CI основной"
        const val SELECTED_NAME = "CI выбранный"
        const val COUNTRY = "Франция"
        const val EXCLUDED_APP = "com.example.bypass"
        const val DIRECT_ROUTE = "192.0.2.0/24"
        val PRIMARY_ID = "a".repeat(64)
        val SELECTED_ID = "b".repeat(64)
    }
}
