package com.kachur.vpn

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class RedesignUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Test
    fun themesNavigationAndScreenshots() {
        setFontScale("1.0")
        seedPreview()
        try {
            AppAppearance.DARK.save(context)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitProfile(scenario)
                scenario.onActivity { activity ->
                    assertFalse(activity.resources.getBoolean(R.bool.ui_light_bars))
                    checkPrimaryAction(activity)
                    assertContrast(activity)
                    assertEquals(activity.getString(R.string.ui_traffic_unavailable),
                        activity.findViewById<TextView>(R.id.ui_traffic_received).text.toString())
                }
                capture("01-home-dark", scenario)
                scenario.onActivity { activity ->
                    activity.findViewById<Button>(R.id.ui_open_settings).performClick()
                    assertTrue(activity.findViewById<View>(R.id.ui_settings_page).isShown)
                    assertTrue(activity.findViewById<Button>(R.id.ui_theme_choice).isShown)
                }
                capture("07-settings", scenario)
                scenario.onActivity { activity ->
                    activity.onBackPressedDispatcher.onBackPressed()
                    assertTrue(activity.findViewById<Button>(R.id.connect_vpn_button).isShown)
                }
            }
            screenshot(ProfilesActivity::class.java, "03-servers", R.id.profiles_list) { view ->
                (view as android.widget.LinearLayout).childCount == 2
            }
            screenshot(DiagnosticsActivity::class.java, "04-diagnostics", R.id.diag_run)
            screenshot(IpRoutesActivity::class.java, "05-ip-routes", R.id.ip_list)
            ActivityScenario.launch(BackupActivity::class.java).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    // Render the idle layout. Password dialogs and user data are never opened for this capture.
                    val content = activity.findViewById<View>(android.R.id.content)
                    val bitmap = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    val background = activity.getColor(R.color.ui_background)
                    canvas.drawColor(background)
                    content.draw(canvas)
                    assertTrue(hasVisibleContent(bitmap, background))
                    save("06-backups", bitmap)
                }
            }
            AppAppearance.LIGHT.save(context)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitProfile(scenario)
                scenario.onActivity { activity ->
                    assertTrue(activity.resources.getBoolean(R.bool.ui_light_bars))
                    checkPrimaryAction(activity)
                    assertContrast(activity)
                }
                capture("02-home-light", scenario)
            }
        } finally {
            AppAppearance.SYSTEM.save(context)
        }
    }

    @Test
    fun primaryActionRemainsReachableAtTwoHundredPercentTextScale() {
        seedPreview()
        AppAppearance.DARK.save(context)
        try {
            setFontScale("2.0")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitProfile(scenario)
                waitFor {
                    var scaled = false
                    scenario.onActivity { scaled = it.resources.configuration.fontScale >= 1.99f }
                    scaled
                }
                scenario.onActivity { activity ->
                    checkPrimaryAction(activity)
                    val action = activity.findViewById<Button>(R.id.connect_vpn_button)
                    val layout = checkNotNull(action.layout)
                    for (line in 0 until layout.lineCount) assertEquals(0, layout.getEllipsisCount(line))
                }
                capture("08-large-text", scenario)
                scenario.onActivity { activity ->
                    val scroll = activity.findViewById<ScrollView>(R.id.ui_home_scroll)
                    scroll.scrollTo(0, scroll.getChildAt(0).height)
                    for (id in listOf(R.id.profiles_open, R.id.diag_open_screen, R.id.ui_open_settings)) {
                        val action = activity.findViewById<Button>(id)
                        assertTrue(action.getGlobalVisibleRect(android.graphics.Rect()))
                        assertEquals(1, action.layout.lineCount)
                        assertEquals(0, action.layout.getEllipsisCount(0))
                    }
                }
            }
        } finally {
            setFontScale("1.0")
            AppAppearance.SYSTEM.save(context)
        }
    }

    private fun seedPreview() {
        VpnLaunchSettings(context).setEnabled(false)
        val store = context.localProfiles()
        if (!store.exists()) {
            fun key(byte: Byte) = Base64.getEncoder().encodeToString(ByteArray(32) { byte })
            val demo = AwgConfigParser.parse("[Interface]\nPrivateKey=${key(1)}\n[Peer]\nPublicKey=${key(2)}")
            val first = ImportedProfile("Домашний сервер", demo, "a".repeat(64))
            val second = ImportedProfile("Резервный сервер", demo, "b".repeat(64))
            store.append(listOf(first, second))
            VpnProfileLabels(context).saveCountry(second.documentKey, "Нидерланды")
        }
        SplitTunnelingSettings(context).addIpRoutes(setOf("192.0.2.0/24", "198.51.100.12", "2001:db8::/32"))
    }

    private fun awaitProfile(scenario: ActivityScenario<MainActivity>) = waitFor {
        var loaded = false
        scenario.onActivity { loaded = it.findViewById<TextView>(R.id.profile_name).text.toString() == "Домашний сервер" }
        loaded
    }

    private fun checkPrimaryAction(activity: MainActivity) {
        val action = activity.findViewById<Button>(R.id.connect_vpn_button)
        assertTrue(action.isShown && action.height >= activity.dp(48))
        val visible = android.graphics.Rect()
        assertTrue(action.getGlobalVisibleRect(visible))
        assertEquals(action.height, visible.height())
        assertEquals(activity.getString(R.string.connect_vpn), action.createAccessibilityNodeInfo().text.toString())
    }

    private fun assertContrast(activity: Activity) {
        for ((foreground, background) in listOf(
            R.color.ui_text to R.color.ui_surface,
            R.color.ui_good to R.color.ui_good_surface,
            R.color.ui_on_primary to R.color.ui_primary_start,
            R.color.ui_on_primary to R.color.ui_primary_end,
        )) {
            assertTrue(ColorUtils.calculateContrast(activity.getColor(foreground), activity.getColor(background)) >= 4.5)
        }
    }

    private fun <T : Activity> screenshot(
        activity: Class<T>, name: String, viewId: Int, ready: (View) -> Boolean = { it.isShown },
    ) {
        ActivityScenario.launch(activity).use { scenario ->
            waitFor {
                var visible = false
                scenario.onActivity { visible = it.findViewById<View>(viewId)?.let(ready) == true }
                visible
            }
            capture(name, scenario)
        }
    }

    private fun <T : Activity> capture(name: String, scenario: ActivityScenario<T>) {
        waitFor {
            var focused = false
            scenario.onActivity { focused = it.hasWindowFocus() }
            focused
        }
        val frame = CountDownLatch(1)
        var background = 0
        scenario.onActivity { activity ->
            background = activity.getColor(R.color.ui_background)
            val decor = activity.window.decorView
            decor.viewTreeObserver.registerFrameCommitCallback { frame.countDown() }
            decor.invalidate()
        }
        assertTrue("No committed frame for $name", frame.await(5, TimeUnit.SECONDS))
        waitFor {
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return@waitFor false
            val pixels = if (bitmap.config == Bitmap.Config.HARDWARE) {
                checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
            } else bitmap
            val ready = hasVisibleContent(pixels, background)
            if (pixels !== bitmap) pixels.recycle()
            if (ready) save(name, bitmap) else bitmap.recycle()
            ready
        }
    }

    private fun hasVisibleContent(bitmap: Bitmap, background: Int): Boolean {
        val margin = bitmap.getPixel(bitmap.width / 50, bitmap.height / 3)
        if (abs(Color.red(margin) - Color.red(background)) > 2 ||
            abs(Color.green(margin) - Color.green(background)) > 2 ||
            abs(Color.blue(margin) - Color.blue(background)) > 2
        ) return false
        val colors = mutableSetOf<Int>()
        for (row in 2..17) for (column in 1..15) {
            colors.add(bitmap.getPixel(bitmap.width * column / 16, bitmap.height * row / 20))
        }
        return colors.size >= 5
    }

    private fun save(name: String, bitmap: Bitmap) {
        val folder = File(context.filesDir, "ui-screenshots").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun setFontScale(value: String) {
        val command = instrumentation.uiAutomation.executeShellCommand("settings put system font_scale $value")
        ParcelFileDescriptor.AutoCloseInputStream(command).use { it.readBytes() }
    }

    private fun waitFor(ready: () -> Boolean) {
        val deadline = System.nanoTime() + 8_000_000_000L
        do {
            instrumentation.waitForIdleSync()
            if (ready()) return
            Thread.sleep(40)
        } while (System.nanoTime() < deadline)
        assertTrue("Screen did not finish laying out", ready())
    }
}
