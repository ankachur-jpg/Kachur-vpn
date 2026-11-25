package com.kachur.vpn

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import androidx.annotation.StringRes
import java.text.Collator

internal data class VpnRoutingSettings(
    val apps: Set<String>,
    val ipRoutes: Set<String>,
    val variablePadding: Boolean = false,
)

internal class SplitTunnelingSettings(context: Context) {
    internal val preferences = context.applicationContext.getSharedPreferences("split_tunneling", Context.MODE_PRIVATE)

    fun excludedPackages(): Set<String> =
        preferences.getStringSet("excluded_packages", emptySet()).orEmpty().toSet()

    fun manualIpRoutes(): Set<String> =
        preferences.getStringSet("direct_ip_routes", emptySet()).orEmpty().toSet()

    fun ipRoutes(): Set<String> = manualIpRoutes() + preferences.getStringSet(DOWNLOADED_IP_ROUTES, emptySet()).orEmpty()

    fun appRoutingEnabled(): Boolean = preferences.getBoolean("app_routing_enabled", true)

    fun ipRoutingEnabled(): Boolean = preferences.getBoolean("ip_routing_enabled", true)

    fun variablePaddingEnabled(): Boolean = preferences.getBoolean("variable_padding", false)

    fun setVariablePadding(enabled: Boolean) = synchronized(ipSettingsLock) {
        preferences.edit().putBoolean("variable_padding", enabled).apply()
    }

    fun setAppRoutingEnabled(enabled: Boolean) = synchronized(ipSettingsLock) {
        preferences.edit().putBoolean("app_routing_enabled", enabled).apply()
    }

    fun setIpRoutingEnabled(enabled: Boolean) = synchronized(ipSettingsLock) {
        preferences.edit().putBoolean("ip_routing_enabled", enabled).apply()
    }

    fun snapshot() = synchronized(ipSettingsLock) {
        VpnRoutingSettings(
            if (appRoutingEnabled()) excludedPackages() else emptySet(),
            if (ipRoutingEnabled()) ipRoutes() else emptySet(),
            variablePaddingEnabled(),
        )
    }

    // Only canonical prefixes reach this store. Perform edits on the main thread.
    fun addIpRoutes(routes: Set<String>): Int = synchronized(ipSettingsLock) {
        val current = manualIpRoutes()
        val updated = current + routes
        if (updated.size > IpRouteImport.MAX_ENTRIES) throw IpImportException(IpImportError.LIMIT)
        if (updated.size != current.size) preferences.edit().putStringSet("direct_ip_routes", updated).apply()
        updated.size - current.size
    }

    fun removeIpRoute(route: String) = synchronized(ipSettingsLock) {
        val current = manualIpRoutes()
        if (route in current) preferences.edit().putStringSet("direct_ip_routes", current - route).apply()
    }

    fun setExcluded(packageName: String, excluded: Boolean) = synchronized(ipSettingsLock) {
        val selection = excludedPackages()
        val updated = if (excluded) selection + packageName else selection - packageName
        if (updated != selection) preferences.edit().putStringSet("excluded_packages", updated).apply()
    }

    fun putRules(writer: SharedPreferences.Editor, apps: Set<String>, manual: Set<String>): SharedPreferences.Editor =
        writer.putStringSet("excluded_packages", apps).putStringSet("direct_ip_routes", manual)

    fun onDownloadsChanged(listener: () -> Unit) = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == DOWNLOADED_IP_ROUTES) listener()
    }

    companion object {
        internal val ipSettingsLock = Any()
        internal const val DOWNLOADED_IP_ROUTES = "downloaded_ip_routes"
    }
}

internal enum class AppCategory(@StringRes val title: Int) {
    ALL(R.string.split_category_all),
    BANKS(R.string.split_category_banks),
    GOVERNMENT(R.string.split_category_government),
    MARKETPLACES(R.string.split_category_marketplaces),
    INVESTMENTS(R.string.split_category_investments);

    companion object {
        private val knownPackages = mapOf(
            "ru.sberbankmobile" to BANKS,
            "com.idamob.tinkoff.android" to BANKS,
            "ru.alfabank.mobile.android" to BANKS,
            "ru.vtb24.mobilebanking.android" to BANKS,
            "ru.rostel" to GOVERNMENT,
            "ru.gosuslugi.pos" to GOVERNMENT,
            "ru.gosuslugi.dom" to GOVERNMENT,
            "ru.ozon.app.android" to MARKETPLACES,
            "com.wildberries.ru" to MARKETPLACES,
            "com.yandex.market" to MARKETPLACES,
            "com.avito.android" to MARKETPLACES,
            "ru.tinkoff.investing" to INVESTMENTS,
            "ru.vtb.invest" to INVESTMENTS,
            "ru.broker.my" to INVESTMENTS,
            "ru.unicredit.invest" to INVESTMENTS,
        )

        fun forPackage(packageName: String): AppCategory? = knownPackages[packageName]
    }
}

internal class InstalledApp(val packageName: String, val label: String, val icon: Drawable?) {
    private val category = AppCategory.forPackage(packageName)

    fun matches(query: String, filter: AppCategory): Boolean =
        (filter == AppCategory.ALL || filter == category) &&
            (label.contains(query, ignoreCase = true) || packageName.contains(query, ignoreCase = true))
}

@Suppress("DEPRECATION")
internal fun installedApps(context: Context): List<InstalledApp> {
    val manager = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val collator = Collator.getInstance()
    return manager.queryIntentActivities(launcher, 0)
        .mapNotNull { it.activityInfo?.applicationInfo }
        .distinctBy { it.packageName }
        .map { InstalledApp(it.packageName, it.loadLabel(manager).toString(), it.loadIcon(manager)) }
        .sortedWith { a, b ->
            collator.compare(a.label, b.label).takeIf { it != 0 } ?: a.packageName.compareTo(b.packageName)
        }
}
