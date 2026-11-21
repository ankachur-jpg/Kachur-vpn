package com.kachur.vpn

import android.content.Context
import androidx.annotation.StringRes
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

internal enum class IpUpdateSchedule(val days: Long, @StringRes val label: Int) {
    DAILY(1, R.string.ip_update_daily),
    WEEKLY(7, R.string.ip_update_weekly),
    MANUAL(0, R.string.ip_update_manual),
}

internal data class IpUpdateSource(val url: URI?) {
    val key: String = url?.let {
        MessageDigest.getInstance("SHA-256").digest(it.toASCIIString().toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    } ?: "ripestat_ru"

    fun title(context: Context): String = url?.host ?: context.getString(R.string.ip_update_registry)

    companion object {
        fun custom(input: String): IpUpdateSource? {
            if (input.length > 2048) return null
            val uri = try {
                URI(input.trim()).normalize()
            } catch (_: URISyntaxException) {
                return null
            }
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.host == null ||
                uri.rawUserInfo != null || uri.rawFragment != null || (uri.port != -1 && uri.port !in 1..65535)
            ) return null
            return IpUpdateSource(uri)
        }
    }
}

internal data class IpUpdateConfiguration(
    val revision: Long,
    val useRegistry: Boolean,
    val custom: List<IpUpdateSource>,
    val schedule: IpUpdateSchedule,
) {
    val sources: List<IpUpdateSource> get() = if (useRegistry) listOf(IpUpdateSource(null)) + custom else custom
}

internal enum class IpUpdateResult(@StringRes val text: Int) {
    SUCCESS(R.string.ip_update_success), NETWORK(R.string.ip_update_network), INVALID(R.string.ip_update_invalid),
    LIMIT(R.string.ip_update_limit), UNSAFE(R.string.ip_update_unsafe), STORAGE(R.string.ip_update_storage),
    CHANGED(R.string.ip_update_changed), EMPTY(R.string.ip_update_no_sources),
}

internal class IpUpdateSettings(context: Context) {
    private val routing = SplitTunnelingSettings(context)
    val preferences = routing.preferences

    fun configuration() = synchronized(SplitTunnelingSettings.ipSettingsLock) {
        IpUpdateConfiguration(
            preferences.getLong("ip_update_revision", 0),
            preferences.getBoolean("ip_update_registry", false),
            preferences.getStringSet("ip_update_urls", emptySet()).orEmpty().sorted().mapNotNull { IpUpdateSource.custom(it) },
            IpUpdateSchedule.entries.firstOrNull { it.name == preferences.getString("ip_update_schedule", null) }
                ?: IpUpdateSchedule.WEEKLY,
        )
    }

    fun change(useRegistry: Boolean, custom: List<IpUpdateSource>, schedule: IpUpdateSchedule) {
        synchronized(SplitTunnelingSettings.ipSettingsLock) {
            val original = configuration()
            if (original.useRegistry == useRegistry && original.custom.toSet() == custom.toSet() && original.schedule == schedule) return
            val edited = IpUpdateConfiguration(original.revision + 1, useRegistry, custom, schedule)
            val removed = original.sources - edited.sources.toSet()
            val writer = preferences.edit().putLong("ip_update_revision", edited.revision)
                .putBoolean("ip_update_registry", useRegistry)
                .putStringSet("ip_update_urls", custom.mapTo(mutableSetOf()) { checkNotNull(it.url).toASCIIString() })
                .putString("ip_update_schedule", schedule.name)
            if (removed.isNotEmpty()) {
                removeSnapshots(writer, removed)
                writer.putStringSet(SplitTunnelingSettings.DOWNLOADED_IP_ROUTES,
                    edited.sources.flatMapTo(mutableSetOf(), ::routesFor))
            }
            writer.apply()
        }
    }

    fun routesFor(source: IpUpdateSource): Set<String> = preferences.getStringSet(routesKey(source), emptySet()).orEmpty()
    fun timeFor(source: IpUpdateSource): Long = preferences.getLong(timeKey(source), 0L)
    fun lastAttempt(): Long = preferences.getLong("ip_update_attempt", 0L)
    fun lastResult(): IpUpdateResult? = preferences.getString("ip_update_result", null)?.let { name ->
        IpUpdateResult.entries.firstOrNull { it.name == name }
    }
    fun failedSource(): String? = preferences.getString("ip_update_failed_source", null)
    fun manualRoutes(): Set<String> = routing.manualIpRoutes()

    fun save(
        config: IpUpdateConfiguration, manual: Set<String>, snapshots: Map<IpUpdateSource, Set<String>>, time: Long,
    ): IpUpdateResult = synchronized(SplitTunnelingSettings.ipSettingsLock) {
        if (config.revision != configuration().revision || manual != manualRoutes()) return IpUpdateResult.CHANGED
        val before = snapshots.keys.associateWith { routesFor(it) to timeFor(it) }
        val previousUnion = preferences.getStringSet(SplitTunnelingSettings.DOWNLOADED_IP_ROUTES, emptySet()).orEmpty()
        val writer = preferences.edit()
            .putStringSet(SplitTunnelingSettings.DOWNLOADED_IP_ROUTES, snapshots.values.flatten().toSet())
            .putLong("ip_update_attempt", time).putString("ip_update_result", IpUpdateResult.SUCCESS.name)
            .remove("ip_update_failed_source")
        for ((source, routes) in snapshots) writer.putStringSet(routesKey(source), routes).putLong(timeKey(source), time)
        if (writer.commit()) return IpUpdateResult.SUCCESS

        // SharedPreferences updates process memory before the disk write completes.
        val rollback = preferences.edit().putStringSet(SplitTunnelingSettings.DOWNLOADED_IP_ROUTES, previousUnion)
            .putLong("ip_update_attempt", time).putString("ip_update_result", IpUpdateResult.STORAGE.name)
        for ((source, snapshot) in before) rollback.putStringSet(routesKey(source), snapshot.first).putLong(timeKey(source), snapshot.second)
        rollback.apply()
        IpUpdateResult.STORAGE
    }

    fun recordError(config: IpUpdateConfiguration, result: IpUpdateResult, time: Long, sourceKey: String?) {
        synchronized(SplitTunnelingSettings.ipSettingsLock) {
            if (config.revision != configuration().revision) return
            preferences.edit().putLong("ip_update_attempt", time).putString("ip_update_result", result.name)
                .putString("ip_update_failed_source", sourceKey).apply()
        }
    }

    private fun removeSnapshots(writer: android.content.SharedPreferences.Editor, sources: List<IpUpdateSource>) {
        for (source in sources) writer.remove(routesKey(source)).remove(timeKey(source))
    }

    private fun routesKey(source: IpUpdateSource) = "ip_update_routes_" + source.key
    private fun timeKey(source: IpUpdateSource) = "ip_update_time_" + source.key
}
