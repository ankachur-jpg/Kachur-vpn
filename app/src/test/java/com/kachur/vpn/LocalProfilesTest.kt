package com.kachur.vpn

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestKachurApplication::class)
class LocalProfilesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun migratesTheEncryptedSingleProfileAndKeepsItsIdentityCountryAndSettings() {
        val old = fixture("Original", "a")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(32) { 42 }, "AES"))
        cipher.updateAAD("Kachur VPN selected profile v1".toByteArray())
        val bytes = cipher.iv + cipher.doFinal(ProfileJson.encode(old).toString().toByteArray())
        val routing = SplitTunnelingSettings(context)
        val preferences = routing.preferences
        preferences.edit().putString("selected_profile_ciphertext", Base64.getEncoder().encodeToString(bytes))
            .putLong("selected_profile_revision", 5L).commit()
        VpnProfileLabels(context).saveCountry(old.documentKey, "Швеция")
        val launch = VpnLaunchSettings(context)
        launch.setEnabled(false)
        launch.setPaused(true)
        launch.setRecoveryEnabled(false)
        routing.setExcluded("com.example.direct", true)
        routing.addIpRoutes(setOf("198.51.100.0/24"))

        val migrated = context.localProfiles().read()
        assertEquals(5L, migrated.revision)
        assertEquals(old.documentKey, migrated.catalog.selectedId)
        assertEquals(old.config.toPortableConfig(), migrated.catalog.selected?.config?.toPortableConfig())
        assertEquals("Швеция", VpnProfileLabels(context).country(old.documentKey))
        assertFalse(launch.enabled)
        assertTrue(launch.paused)
        assertFalse(launch.recoveryEnabled)
        assertEquals(setOf("com.example.direct"), routing.excludedPackages())
        assertEquals(setOf("198.51.100.0/24"), routing.ipRoutes())
        assertFalse(preferences.contains("selected_profile_ciphertext"))
        assertTrue(preferences.contains("vpn_profile_catalog_ciphertext"))
        assertFalse(preferences.all.toString().contains("Original"))
        assertFalse(preferences.all.toString().contains(old.config.interfaceSection["PrivateKey"].orEmpty()))
    }

    @Test
    fun appendsRenamesSelectsAndRemovesWithoutLosingUnrelatedProfiles() {
        val store = context.localProfiles()
        val original = fixture("Original", "a")
        val next = fixture("Another", "b")
        val last = fixture("Final", "c")
        store.append(listOf(original))
        store.append(listOf(next, last))
        assertEquals(listOf("Original", "Another", "Final"), store.read().catalog.entries.map { it.name })
        assertEquals(original.documentKey, store.load()?.documentKey)
        store.select(last.documentKey)
        store.rename(last.documentKey, "Renamed")
        assertEquals("Renamed", store.load()?.name)
        store.remove(last.documentKey)
        assertEquals(original.documentKey, store.load()?.documentKey)
        store.remove(original.documentKey)
        store.remove(next.documentKey)
        assertNull(store.load())
        assertFalse(store.exists())
    }

    @Test
    fun rejectsAnInvalidOrOversizedCatalogBeforeChangingTheSelection() {
        val store = context.localProfiles()
        val original = fixture("Original", "a")
        val state = store.append(listOf(original))
        assertThrows(IllegalArgumentException::class.java) { store.append(listOf(original)) }
        val overLimit = List(ProfileCatalog.MAX_PROFILES) { index -> fixture("Server", (index + 1).toString(16).padStart(64, '0')) }
        assertThrows(ProfileLimitException::class.java) { store.append(overLimit) }
        assertEquals(state.revision, store.revision())
        assertEquals(original.documentKey, store.load()?.documentKey)
    }

    private fun fixture(name: String, id: String): ImportedProfile {
        fun key(value: Byte) = Base64.getEncoder().encodeToString(ByteArray(32) { value })
        return ImportedProfile(name, AwgConfigParser.parse(
            "[Interface]\nPrivateKey=${key(1)}\n[Peer]\nPublicKey=${key(2)}\nAllowedIPs=0.0.0.0/0"
        ), if (id.length == 1) id.repeat(64) else id)
    }
}
