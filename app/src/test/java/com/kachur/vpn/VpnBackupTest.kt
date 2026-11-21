package com.kachur.vpn

import android.content.Context
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
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

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
        val other = ImportedProfile("Second server", profile.config, "b".repeat(64))
        context.localProfiles().append(listOf(profile, other))
        context.localProfiles().select(other.documentKey)
        val feeds = IpUpdateSettings(context)
        val source = checkNotNull(IpUpdateSource.custom("https://example.test/source.json"))
        val routeSettings = SplitTunnelingSettings(context)
        feeds.change(true, listOf(source), IpUpdateSchedule.DAILY)
        routeSettings.setExcluded("com.example.direct", true)
        routeSettings.addIpRoutes(setOf("198.51.100.0/24"))
        assertTrue(VpnProfileLabels(context).saveCountry(profile.documentKey, "Франция"))
        assertTrue(VpnProfileLabels(context).saveCountry(other.documentKey, "Швеция"))
        VpnLaunchSettings(context).setEnabled(false)
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
        context.localProfiles().remove(other.documentKey)
        val revision = context.localProfiles().revision()
        val imported = BackupJson.decode(BackupCipher.decrypt(encrypted, password))
        assertEquals(revision, context.localProfiles().revision())
        assertTrue(routeSettings.ipRoutes().isEmpty())
        assertTrue(store.restore(imported))
        assertEquals(listOf(profile.documentKey, other.documentKey), context.localProfiles().read().catalog.entries.map { it.documentKey })
        assertEquals(other.documentKey, context.localProfiles().load()?.documentKey)
        assertEquals(deviceKey, context.localProfiles().load()?.config?.interfaceSection?.get("PrivateKey"))
        assertEquals("Франция", VpnProfileLabels(context).country(profile.documentKey))
        assertEquals("Швеция", VpnProfileLabels(context).country(other.documentKey))
        assertEquals(setOf("198.51.100.0/24", "203.0.113.0/24"), routeSettings.ipRoutes())
        assertEquals(setOf("com.example.direct"), routeSettings.excludedPackages())
        assertEquals(IpUpdateSchedule.DAILY, feeds.configuration().schedule)
        assertEquals(listOf(source), feeds.configuration().custom)
        assertFalse(VpnLaunchSettings(context).enabled)
    }

    @Test
    fun opensPasswordEncryptedSingleProfileBackupFromThePreviousVersion() {
        val deviceKey = Base64.getEncoder().encodeToString(ByteArray(32) { 4 })
        val peerKey = Base64.getEncoder().encodeToString(ByteArray(32) { 5 })
        val id = "c".repeat(64)
        val oldJson = JSONObject().put("profile", JSONObject().put("name", "Old profile").put("key", id)
            .put("conf", "[Interface]\nPrivateKey=$deviceKey\n[Peer]\nPublicKey=$peerKey"))
            .put("country", "Германия").put("apps", JSONArray()).put("manual", JSONArray())
            .put("schedule", "WEEKLY").put("autoConnect", true).put("autoPaused", false).put("sources", JSONArray())
            .toString().toByteArray()
        val password = "synthetic legacy archive".toCharArray()
        val salt = ByteArray(16) { 7 }
        val nonce = ByteArray(12) { 9 }
        val header = "KACHUR\u0001".toByteArray() + salt + nonce
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password, salt, 600_000, 256)).encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(header)
        val restored = BackupJson.decode(BackupCipher.decrypt(header + cipher.doFinal(oldJson), password))
        assertEquals(id, restored.profiles.selectedId)
        assertEquals("Old profile", restored.profiles.selected?.name)
        assertEquals("Германия", restored.countries[id])
    }
}
