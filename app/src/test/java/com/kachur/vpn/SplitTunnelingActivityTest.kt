package com.kachur.vpn

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.widget.EditText
import android.widget.ListView
import android.widget.Switch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SplitTunnelingActivityTest {
    @Test
    fun appSearchAndToggleRetainSelectionWhenTheScreenReopens() {
        installLauncherApp("ru.rostel", "Госуслуги")
        installLauncherApp("com.example.browser", "Браузер")
        val app = RuntimeEnvironment.getApplication()
        val settings = SplitTunnelingSettings(app)
        settings.setExcluded("ru.rostel", false)
        try {
            Robolectric.buildActivity(SplitTunnelingActivity::class.java).setup().use { controller ->
                val screen = controller.get()
                val toggle = searchForGovernmentApp(screen)
                assertFalse(toggle.isChecked)
                toggle.performClick()
                assertEquals(setOf("ru.rostel"), settings.excludedPackages())
            }
            Robolectric.buildActivity(SplitTunnelingActivity::class.java).setup().use { controller ->
                val toggle = searchForGovernmentApp(controller.get())
                assertTrue(toggle.isChecked)
                toggle.performClick()
            }
            assertTrue(SplitTunnelingSettings(app).excludedPackages().isEmpty())
        } finally {
            settings.setExcluded("ru.rostel", false)
        }
    }

    @Test
    fun categoriesAndSearchWorkTogetherWithoutSelectingApps() {
        val bank = InstalledApp("com.idamob.tinkoff.android", "Т-Банк", null)
        val government = InstalledApp("ru.rostel", "Госуслуги", null)
        val market = InstalledApp("ru.ozon.app.android", "Ozon", null)
        val investment = InstalledApp("ru.tinkoff.investing", "Т-Инвестиции", null)
        val other = InstalledApp("com.example.browser", "Браузер", null)

        assertTrue(bank.matches("БАНК", AppCategory.BANKS))
        assertTrue(government.matches("услуг", AppCategory.GOVERNMENT))
        assertTrue(market.matches("ozON", AppCategory.MARKETPLACES))
        assertTrue(investment.matches("инвест", AppCategory.INVESTMENTS))
        assertFalse(investment.matches("т-", AppCategory.BANKS))
        assertTrue(other.matches("браузер", AppCategory.ALL))
        assertFalse(other.matches("", AppCategory.GOVERNMENT))
    }

    private fun searchForGovernmentApp(screen: SplitTunnelingActivity): Switch {
        val list = screen.findViewById<ListView>(R.id.split_apps)
        screen.findViewById<EditText>(R.id.split_search).setText("ГОСУСЛ")
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            looper.idle()
            if (list.adapter.count == 1) break
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        assertEquals(1, list.adapter.count)
        assertEquals("ru.rostel", (list.adapter.getItem(0) as InstalledApp).packageName)
        return list.adapter.getView(0, null, list).findViewById(R.id.split_app_switch)
    }

    private fun installLauncherApp(packageName: String, label: String) {
        val manager = shadowOf(RuntimeEnvironment.getApplication().packageManager)
        val info = ApplicationInfo().apply {
            this.packageName = packageName
            nonLocalizedLabel = label
        }
        val launcher = ActivityInfo().apply {
            this.packageName = packageName
            name = "$packageName.Launcher"
            applicationInfo = info
            exported = true
        }
        manager.installPackage(PackageInfo().apply {
            this.packageName = packageName
            applicationInfo = info
            activities = arrayOf(launcher)
        })
        manager.addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            ResolveInfo().apply { activityInfo = launcher },
        )
    }
}
