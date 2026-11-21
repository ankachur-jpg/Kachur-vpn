package com.kachur.vpn

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Base64
import javax.crypto.AEADBadTagException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestKachurApplication::class)
class VpnBackupTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun passwordAndAuthenticatedHeaderRejectWrongPasswordAndBitChanges() {
        val plain = "synthetic configuration".toByteArray()
        val password = "synthetic password".toCharArray()
        val first = BackupCipher.encrypt(plain, password)
        val second = BackupCipher.encrypt(plain, password)
        assertFalse(first.contentEquals(second))
        assertTrue(plain.contentEquals(BackupCipher.decrypt(first, password)))
        assertThrows(AEADBadTagException::class.java) { BackupCipher.decrypt(first, "another password".toCharArray()) }
        for (index in listOf(8, first.lastIndex)) {
            val changed = first.clone()
            changed[index] = (changed[index].toInt() xor 1).toByte()
            assertThrows(AEADBadTagException::class.java) { BackupCipher.decrypt(changed, password) }
        }
    }

    @Test
    fun encryptedCopyRoundTripsTheSelectedProfileRoutingAndUpdateSettings() {
        val deviceKey = Base64.getEncoder().encodeToString(ByteArray(32) { 4 })
        val peerKey = Base64.getEncoder().encodeToString(ByteArray(32) { 5 })
        val profile = ImportedProfile("Snapshot", AwgConfigParser.parse(
            "[Interface]\nPrivateKey = $deviceKey\n[Peer]\nPublicKey = $peerKey\nEndpoint = 192.0.2.1:51820\nPersistentKeepalive = 25",
        ), "a".repeat(64))
        context.localProfiles().save(profile)
        val feeds = IpUpdateSettings(context)
        val source = checkNotNull(IpUpdateSource.custom("https://example.test/source.json"))
        val routeSettings = SplitTunnelingSettings(context)
        feeds.change(true, listOf(source), IpUpdateSchedule.DAILY)
        routeSettings.setExcluded("com.example.direct", true)
        routeSettings.addIpRoutes(setOf("198.51.100.0/24"))
        assertTrue(VpnProfileLabels(context).saveCountry(profile.documentKey, "Франция"))
        VpnLaunchSettings(context).setEnabled(false)
        VpnLaunchSettings(context).setRecoveryEnabled(false)
        assertEquals(IpUpdateResult.SUCCESS, IpFeedUpdater(context, { setOf("203.0.113.0/24") }).refresh())
        val store = VpnBackupStore(context)
        val password = "snapshot password".toCharArray()
        val encrypted = BackupCipher.encrypt(BackupJson.encode(store.snapshot()), password)
        assertFalse(encrypted.toString(Charsets.UTF_8).contains(deviceKey))
        assertFalse(routeSettings.preferences.all.toString().contains(deviceKey))
        assertFalse(routeSettings.preferences.all.toString().contains("192.0.2.1"))

        feeds.change(false, emptyList(), IpUpdateSchedule.MANUAL)
        routeSettings.removeIpRoute("198.51.100.0/24")
        routeSettings.setExcluded("com.example.direct", false)
        VpnLaunchSettings(context).setRecoveryEnabled(true)
        val revision = context.localProfiles().revision()
        val imported = BackupJson.decode(BackupCipher.decrypt(encrypted, password))
        assertEquals(revision, context.localProfiles().revision())
        assertTrue(routeSettings.ipRoutes().isEmpty())
        assertTrue(store.restore(imported))
        assertEquals(deviceKey, context.localProfiles().load()?.config?.interfaceSection?.get("PrivateKey"))
        assertEquals("Франция", VpnProfileLabels(context).country(profile.documentKey))
        assertEquals(setOf("198.51.100.0/24", "203.0.113.0/24"), routeSettings.ipRoutes())
        assertEquals(setOf("com.example.direct"), routeSettings.excludedPackages())
        assertEquals(IpUpdateSchedule.DAILY, feeds.configuration().schedule)
        assertEquals(listOf(source), feeds.configuration().custom)
        assertFalse(VpnLaunchSettings(context).enabled)
        assertFalse(VpnLaunchSettings(context).recoveryEnabled)
    }

    @Test
    fun backupsWithoutRecoverySettingEnableItByDefault() {
        val json = JSONObject(BackupJson.encode(VpnBackupStore(context).snapshot()).toString(Charsets.UTF_8))
        json.remove("autoRecovery")
        val oldBackup = BackupJson.decode(json.toString().toByteArray(Charsets.UTF_8))
        assertTrue(oldBackup.autoRecovery)
    }
}
