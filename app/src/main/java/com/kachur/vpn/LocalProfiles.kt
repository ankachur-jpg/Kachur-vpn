package com.kachur.vpn

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

open class KachurApplication : Application() {
    internal val profiles by lazy { LocalProfiles(this, ::profileKey) }

    @Synchronized
    protected open fun profileKey(): SecretKey {
        val alias = "KachurVPN.selected-profile.v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
}

internal fun Context.localProfiles(): LocalProfiles = (applicationContext as KachurApplication).profiles

internal object ProfileJson {
    fun encode(profile: ImportedProfile) = JSONObject()
        .put("name", profile.name).put("key", profile.documentKey).put("conf", profile.config.toPortableConfig())

    fun decode(json: JSONObject): ImportedProfile {
        val name = json.get("name") as? String ?: throw IOException()
        val id = json.get("key") as? String ?: throw IOException()
        val config = json.get("conf") as? String ?: throw IOException()
        if (!validName(name) || !validId(id) || config.length > AwgConfigParser.MAX_CHARS + 1
        ) throw IOException()
        return ImportedProfile(name, AwgConfigParser.parse(config), id)
    }

    private val idPattern = Regex("[0-9a-f]{64}")
    fun validId(value: String) = idPattern.matches(value)
    fun validName(value: String) = value.length in 1..100 && value.isNotBlank() &&
        value.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
}

internal class ProfileLimitException : IOException()

internal class ProfileCatalog(val entries: List<ImportedProfile>, val selectedId: String?) {
    val selected: ImportedProfile? get() = entries.firstOrNull { it.documentKey == selectedId }

    init {
        if (entries.size > MAX_PROFILES) throw ProfileLimitException()
        require(entries.map { it.documentKey }.toSet().size == entries.size)
        require(entries.all { ProfileJson.validId(it.documentKey) && ProfileJson.validName(it.name) })
        require(if (entries.isEmpty()) selectedId == null else selected != null)
    }

    companion object {
        const val MAX_PROFILES = 64
        const val MAX_BYTES = 2 * 1024 * 1024
        val EMPTY = ProfileCatalog(emptyList(), null)
        fun legacy(profile: ImportedProfile?) = profile?.let { ProfileCatalog(listOf(it), it.documentKey) } ?: EMPTY
    }
}

internal object ProfileCatalogJson {
    fun encode(catalog: ProfileCatalog): JSONObject = JSONObject()
        .put("profiles", JSONArray().apply { catalog.entries.forEach { put(ProfileJson.encode(it)) } })
        .put("selected", catalog.selectedId ?: JSONObject.NULL)

    fun decode(json: JSONObject): ProfileCatalog {
        if (json.toString().toByteArray(Charsets.UTF_8).size > ProfileCatalog.MAX_BYTES) throw ProfileLimitException()
        val array = json.getJSONArray("profiles")
        if (array.length() > ProfileCatalog.MAX_PROFILES) throw ProfileLimitException()
        val selected = when (val id = json.get("selected")) {
            JSONObject.NULL -> null
            is String -> id
            else -> throw IOException()
        }
        return ProfileCatalog(List(array.length()) { ProfileJson.decode(array.getJSONObject(it)) }, selected)
    }
}

internal class StoredProfiles(val catalog: ProfileCatalog, val revision: Long)

internal class LocalProfiles(context: Context, private val key: () -> SecretKey) {
    private val preferences = SplitTunnelingSettings(context).preferences

    fun revision(): Long = preferences.getLong(REVISION, 0L)
    fun exists(): Boolean = preferences.contains(CATALOG) || preferences.contains(LEGACY_PROFILE)

    fun load(): ImportedProfile? = read().catalog.selected

    fun read(): StoredProfiles = synchronized(SplitTunnelingSettings.ipSettingsLock) {
        val revision = revision()
        val stored = preferences.getString(CATALOG, null)
        if (stored != null) return StoredProfiles(ProfileCatalogJson.decode(open(stored, CATALOG_AAD)), revision)
        val legacy = preferences.getString(LEGACY_PROFILE, null) ?: return StoredProfiles(ProfileCatalog.EMPTY, revision)
        val migrated = ProfileCatalog.legacy(ProfileJson.decode(open(legacy, LEGACY_AAD)))
        // Keep the original encrypted entry until the equivalent catalog is committed.
        val next = seal(migrated)
        if (!put(preferences.edit(), next, revision).commit()) {
            preferences.edit().remove(CATALOG).putString(LEGACY_PROFILE, legacy).apply()
        }
        StoredProfiles(migrated, revision)
    }

    private fun open(encoded: String, aad: ByteArray): JSONObject {
        if (encoded.length > MAX_ENCODED_SIZE) throw IOException()
        val bytes = Base64.getDecoder().decode(encoded)
        if (bytes.size <= NONCE_SIZE + 16) throw IOException()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, NONCE_SIZE))
        cipher.updateAAD(aad)
        val content = cipher.doFinal(bytes, NONCE_SIZE, bytes.size - NONCE_SIZE)
        return try {
            JSONObject(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(content)).toString())
        } finally {
            content.fill(0)
        }
    }

    fun seal(catalog: ProfileCatalog): String? {
        if (catalog.entries.isEmpty()) return null
        val content = ProfileCatalogJson.encode(catalog).toString().toByteArray(Charsets.UTF_8)
        return try {
            if (content.size > ProfileCatalog.MAX_BYTES) throw ProfileLimitException()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            check(cipher.iv.size == NONCE_SIZE)
            cipher.updateAAD(CATALOG_AAD)
            Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(content))
        } finally {
            content.fill(0)
        }
    }

    fun append(profiles: List<ImportedProfile>): StoredProfiles = edit { old ->
        ProfileCatalog(old.entries + profiles, old.selectedId ?: profiles.firstOrNull()?.documentKey)
    }

    fun select(id: String): StoredProfiles = edit { old ->
        require(old.entries.any { it.documentKey == id })
        if (old.selectedId == id) old else ProfileCatalog(old.entries, id)
    }

    fun rename(id: String, name: String): StoredProfiles = edit { old ->
        require(ProfileJson.validName(name) && old.entries.any { it.documentKey == id })
        ProfileCatalog(old.entries.map { if (it.documentKey == id) ImportedProfile(name, it.config, id) else it }, old.selectedId)
    }

    fun remove(id: String): StoredProfiles = edit { old ->
        val remaining = old.entries.filterNot { it.documentKey == id }
        if (remaining.size == old.entries.size) old else {
            ProfileCatalog(remaining, if (old.selectedId == id) remaining.firstOrNull()?.documentKey else old.selectedId)
        }
    }

    private fun edit(change: (ProfileCatalog) -> ProfileCatalog): StoredProfiles = synchronized(SplitTunnelingSettings.ipSettingsLock) {
        val original = read()
        val catalog = change(original.catalog)
        if (catalog === original.catalog) return original
        val sealed = seal(catalog)
        val previous = preferences.getString(CATALOG, null)
        val legacy = preferences.getString(LEGACY_PROFILE, null)
        val revision = original.revision + 1
        if (!put(preferences.edit(), sealed, revision).commit()) {
            put(preferences.edit(), previous, original.revision).putString(LEGACY_PROFILE, legacy).apply()
            throw IOException()
        }
        StoredProfiles(catalog, revision)
    }

    fun put(writer: SharedPreferences.Editor, sealed: String?, revision: Long): SharedPreferences.Editor =
        writer.putString(CATALOG, sealed).remove(LEGACY_PROFILE).putLong(REVISION, revision)

    private companion object {
        const val CATALOG = "vpn_profile_catalog_ciphertext"
        const val LEGACY_PROFILE = "selected_profile_ciphertext"
        const val REVISION = "selected_profile_revision"
        const val NONCE_SIZE = 12
        const val MAX_ENCODED_SIZE = (ProfileCatalog.MAX_BYTES + NONCE_SIZE + 18) / 3 * 4
        val LEGACY_AAD = "Kachur VPN selected profile v1".toByteArray(Charsets.UTF_8)
        val CATALOG_AAD = "Kachur VPN profile catalog v2".toByteArray(Charsets.UTF_8)
    }
}

internal class VpnLaunchSettings(context: Context) {
    private val prefs = SplitTunnelingSettings(context).preferences

    val enabled: Boolean get() = prefs.getBoolean(ENABLED, true)
    val paused: Boolean get() = prefs.getBoolean(PAUSED, false)

    fun setEnabled(enabled: Boolean) = synchronized(SplitTunnelingSettings.ipSettingsLock) {
        val writer = prefs.edit().putBoolean(ENABLED, enabled)
        if (enabled) writer.putBoolean(PAUSED, false)
        writer.apply()
    }

    fun setPaused(paused: Boolean) = synchronized(SplitTunnelingSettings.ipSettingsLock) {
        prefs.edit().putBoolean(PAUSED, paused).apply()
    }

    companion object {
        const val ENABLED = "vpn_auto_connect"
        const val PAUSED = "vpn_auto_connect_paused"
    }
}
