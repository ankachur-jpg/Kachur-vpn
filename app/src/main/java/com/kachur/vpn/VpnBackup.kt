package com.kachur.vpn

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal class IpSourceBackup(val routes: Set<String>, val updatedAt: Long)

internal class VpnBackup(
    val profile: ImportedProfile?, val country: String?,
    val apps: Set<String>, val manualRoutes: Set<String>,
    val sources: Map<IpUpdateSource, IpSourceBackup>, val schedule: IpUpdateSchedule,
    val autoConnect: Boolean, val autoPaused: Boolean,
)

internal class BackupFileException(val label: Int) : IOException()

internal object BackupCipher {
    const val MIN_PASSWORD = 12
    const val MAX_FILE_BYTES = 6 * 1024 * 1024
    private const val SALT_SIZE = 16
    private const val NONCE_SIZE = 12
    private val magic = "KACHUR\u0001".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()

    // Version 1 fixes the work factor. The header is authenticated along with the ciphertext.
    fun encrypt(content: ByteArray, password: CharArray): ByteArray {
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val header = magic + salt + nonce
        if (content.size > MAX_FILE_BYTES - header.size - 16) throw BackupFileException(R.string.backup_too_large)
        return header + cipher(Cipher.ENCRYPT_MODE, password, salt, nonce, header).doFinal(content)
    }

    fun decrypt(bytes: ByteArray, password: CharArray): ByteArray {
        val headerSize = magic.size + SALT_SIZE + NONCE_SIZE
        if (bytes.size > MAX_FILE_BYTES) throw BackupFileException(R.string.backup_too_large)
        if (bytes.size <= headerSize + 16 || !bytes.copyOfRange(0, magic.size).contentEquals(magic)) {
            throw BackupFileException(R.string.backup_format_error)
        }
        val salt = bytes.copyOfRange(magic.size, magic.size + SALT_SIZE)
        val nonce = bytes.copyOfRange(magic.size + SALT_SIZE, headerSize)
        return cipher(Cipher.DECRYPT_MODE, password, salt, nonce, bytes.copyOfRange(0, headerSize))
            .doFinal(bytes, headerSize, bytes.size - headerSize)
    }

    private fun cipher(mode: Int, password: CharArray, salt: ByteArray, nonce: ByteArray, header: ByteArray): Cipher {
        val spec = PBEKeySpec(password, salt, 600_000, 256)
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(header)
            }
        } finally {
            key.fill(0)
        }
    }
}

internal object BackupJson {
    private val packageName = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*")

    fun encode(backup: VpnBackup): ByteArray = JSONObject()
        .put("profile", backup.profile?.let(ProfileJson::encode) ?: JSONObject.NULL)
        .put("country", backup.country ?: JSONObject.NULL)
        .put("apps", JSONArray(backup.apps)).put("manual", JSONArray(backup.manualRoutes))
        .put("schedule", backup.schedule.name)
        .put("autoConnect", backup.autoConnect).put("autoPaused", backup.autoPaused)
        .put("sources", JSONArray().apply {
            backup.sources.forEach { (source, data) -> put(JSONObject()
                .put("url", source.url?.toASCIIString() ?: JSONObject.NULL)
                .put("time", data.updatedAt).put("routes", JSONArray(data.routes))) }
        }).toString().toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): VpnBackup {
        val json = JSONObject(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString())
        val profile = if (json.isNull("profile")) null else ProfileJson.decode(json.getJSONObject("profile"))
        val country = if (json.isNull("country")) null else json.get("country") as? String ?: invalid()
        if ((profile == null) != (country == null) || (country != null && !VpnProfileLabels.validCountry(country))) invalid()
        val apps = strings(json.getJSONArray("apps"), 4096) { if (it.length <= 255 && packageName.matches(it)) it else invalid() }
        val manual = routes(json.getJSONArray("manual"), IpRouteImport.MAX_ENTRIES)
        val sources = linkedMapOf<IpUpdateSource, IpSourceBackup>()
        val sourceList = json.getJSONArray("sources")
        if (sourceList.length() > 9) invalid()
        for (i in 0 until sourceList.length()) {
            val entry = sourceList.getJSONObject(i)
            val source = if (entry.isNull("url")) IpUpdateSource(null) else {
                IpUpdateSource.custom(entry.get("url") as? String ?: invalid()) ?: invalid()
            }
            val time = (entry.get("time") as? Number)?.toLong()?.takeIf { it >= 0L } ?: invalid()
            if (sources.put(source, IpSourceBackup(routes(entry.getJSONArray("routes"), IpRouteImport.MAX_ENTRIES), time)) != null) invalid()
        }
        if (sources.keys.count { it.url != null } > 8) invalid()
        if (sources.values.any { it.routes.isNotEmpty() }) {
            try {
                validateDownloadedRoutes(manual, sources.values.map { it.routes }, Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            } catch (_: TooManyVpnRoutesException) {
                throw BackupFileException(R.string.backup_route_limit)
            } catch (_: IpFeedException) {
                throw BackupFileException(R.string.backup_route_limit)
            }
        }
        val schedule = IpUpdateSchedule.entries.firstOrNull { it.name == json.get("schedule") } ?: invalid()
        return VpnBackup(profile, country, apps, manual, sources, schedule,
            json.get("autoConnect") as? Boolean ?: invalid(), json.get("autoPaused") as? Boolean ?: invalid())
    }

    private fun routes(values: JSONArray, limit: Int): Set<String> = strings(values, limit) {
        IpRoutePrefix.parse(it)?.toString() ?: invalid()
    }

    private fun strings(values: JSONArray, limit: Int, validate: (String) -> String): Set<String> {
        if (values.length() > limit) invalid()
        return (0 until values.length()).mapTo(linkedSetOf()) { validate(values.get(it) as? String ?: invalid()) }
    }

    private fun invalid(): Nothing = throw BackupFileException(R.string.backup_format_error)
}

internal class VpnBackupStore(private val context: Context) {
    private val routing = SplitTunnelingSettings(context)
    private val feeds = IpUpdateSettings(context)
    private val profiles = context.localProfiles()

    fun snapshot(): VpnBackup = synchronized(SplitTunnelingSettings.ipSettingsLock) {
        val profile = profiles.load()
        val config = feeds.configuration()
        val launch = VpnLaunchSettings(context)
        VpnBackup(profile, profile?.let { VpnProfileLabels(context).country(it.documentKey) }, routing.excludedPackages(),
            routing.manualIpRoutes(), config.sources.associateWith { IpSourceBackup(feeds.routesFor(it), feeds.timeFor(it)) },
            config.schedule, launch.enabled, launch.paused)
    }

    fun restore(backup: VpnBackup): Boolean {
        val sealed = profiles.seal(backup.profile)
        synchronized(SplitTunnelingSettings.ipSettingsLock) {
            val prefs = routing.preferences
            val previous = prefs.all
            val writer = profiles.put(prefs.edit(), sealed, profiles.revision() + 1)
                .putBoolean(VpnLaunchSettings.ENABLED, backup.autoConnect)
                .putBoolean(VpnLaunchSettings.PAUSED, backup.autoPaused)
            routing.putRules(writer, backup.apps, backup.manualRoutes)
            feeds.putRestoredSources(writer, backup.schedule, backup.sources)
            val profile = backup.profile
            if (profile != null && backup.country != null) VpnProfileLabels.putCountry(writer, profile.documentKey, backup.country)
            if (!writer.commit()) {
                val rollback = prefs.edit().clear()
                previous.forEach { (key, value) -> putValue(rollback, key, value) }
                rollback.apply()
                throw IOException()
            }
        }
        return IpUpdateJobs.schedule(context, feeds.configuration())
    }

    private fun putValue(writer: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is Boolean -> writer.putBoolean(key, value)
            is String -> writer.putString(key, value)
            is Long -> writer.putLong(key, value)
            is Int -> writer.putInt(key, value)
            is Float -> writer.putFloat(key, value)
            is Set<*> -> writer.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }
}

internal fun readBackupFile(input: InputStream): ByteArray {
    val buffer = ByteArray(8192)
    val bytes = ByteArrayOutputStream()
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return bytes.toByteArray()
        if (bytes.size() + count > BackupCipher.MAX_FILE_BYTES) throw BackupFileException(R.string.backup_too_large)
        bytes.write(buffer, 0, count)
    }
}
