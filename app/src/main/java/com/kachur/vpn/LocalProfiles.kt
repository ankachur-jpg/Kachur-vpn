package com.kachur.vpn

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
        if (name.length !in 1..100 || name.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() } ||
            !Regex("[0-9a-f]{64}").matches(id) || config.length > AwgConfigParser.MAX_CHARS
        ) throw IOException()
        return ImportedProfile(name, AwgConfigParser.parse(config), id)
    }
}

internal class LocalProfiles(context: Context, private val key: () -> SecretKey) {
    private val preferences = SplitTunnelingSettings(context).preferences

    fun revision(): Long = preferences.getLong(REVISION, 0L)
    fun exists(): Boolean = preferences.contains(PROFILE)

    fun load(): ImportedProfile? {
        val encoded = preferences.getString(PROFILE, null) ?: return null
        if (encoded.length > 1_500_000) throw IOException()
        val bytes = Base64.getDecoder().decode(encoded)
        if (bytes.size <= NONCE_SIZE + 16) throw IOException()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, NONCE_SIZE))
        cipher.updateAAD(AAD)
        val content = cipher.doFinal(bytes, NONCE_SIZE, bytes.size - NONCE_SIZE)
        return try {
            ProfileJson.decode(JSONObject(content.toString(Charsets.UTF_8)))
        } finally {
            content.fill(0)
        }
    }

    fun seal(profile: ImportedProfile?): String? {
        if (profile == null) return null
        val content = ProfileJson.encode(profile).toString().toByteArray(Charsets.UTF_8)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            check(cipher.iv.size == NONCE_SIZE)
            cipher.updateAAD(AAD)
            Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(content))
        } finally {
            content.fill(0)
        }
    }

    fun save(profile: ImportedProfile): Long {
        val sealed = seal(profile)
        return synchronized(SplitTunnelingSettings.ipSettingsLock) {
            val revision = revision() + 1
            val previous = preferences.getString(PROFILE, null)
            if (!put(preferences.edit(), sealed, revision).commit()) {
                put(preferences.edit(), previous, revision - 1).apply()
                throw IOException()
            }
            revision
        }
    }

    fun put(writer: SharedPreferences.Editor, sealed: String?, revision: Long): SharedPreferences.Editor =
        writer.putString(PROFILE, sealed).putLong(REVISION, revision)

    private companion object {
        const val PROFILE = "selected_profile_ciphertext"
        const val REVISION = "selected_profile_revision"
        const val NONCE_SIZE = 12
        val AAD = "Kachur VPN selected profile v1".toByteArray(Charsets.UTF_8)
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
