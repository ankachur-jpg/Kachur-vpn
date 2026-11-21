package com.kachur.vpn

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.annotation.StringRes
import java.text.Collator

internal class SplitTunnelingSettings(context: Context) {
    private val preferences = context.getSharedPreferences("split_tunneling", Context.MODE_PRIVATE)

    fun excludedPackages(): Set<String> =
        preferences.getStringSet("excluded_packages", emptySet()).orEmpty().toSet()

    fun setExcluded(packageName: String, excluded: Boolean) {
        val selection = excludedPackages()
        val updated = if (excluded) selection + packageName else selection - packageName
        if (updated != selection) preferences.edit().putStringSet("excluded_packages", updated).apply()
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
