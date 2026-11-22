package com.kachur.vpn

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.UiAutomation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.RequiresApi
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) captureIconPreview(activity, "09-icon-dark")
                    val received = activity.findViewById<TextView>(R.id.ui_traffic_received)
                    assertEquals(activity.getString(R.string.ui_metric_empty), received.text.toString())
                    assertEquals(activity.getString(R.string.ui_traffic_unavailable), received.contentDescription.toString())
                }
                capture("01-home-dark", scenario)
                try {
                    // Exercise the presentation of a handshake without opening a real tunnel or accessing the network.
                    scenario.onActivity { activity ->
                        VpnConnection.publish(VpnPhase.HANDSHAKE, R.string.vpn_handshake_received)
                        val action = activity.findViewById<Button>(R.id.connect_vpn_button)
                        assertEquals(activity.getString(R.string.vpn_notice_connected), action.text.toString().substringAfter('\n'))
                        assertTrue(action.contentDescription.contains(activity.getString(R.string.vpn_disconnect)))
                        assertEquals(PowerTone.ACTIVE.ink, action.currentTextColor)
                        assertEquals(activity.getString(R.string.ui_protection_active),
                            activity.findViewById<TextView>(R.id.ui_protection_title).text.toString())
                    }
                    capture("11-home-connected-preview", scenario)
                } finally {
                    scenario.onActivity { VpnConnection.publish(VpnPhase.IDLE, R.string.vpn_disconnected) }
                }
                scenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.ui_open_settings).performClick()
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) captureIconPreview(activity, "10-icon-light")
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
                    for (id in listOf(R.id.ui_home_tab, R.id.diag_open_screen, R.id.profiles_open)) {
                        val action = activity.findViewById<Button>(id)
                        assertTrue(action.getGlobalVisibleRect(android.graphics.Rect()))
                        assertTrue(action.height >= activity.dp(48))
                        for (line in 0 until action.layout.lineCount) assertEquals(0, action.layout.getEllipsisCount(line))
                    }
                    assertTrue(activity.findViewById<View>(R.id.ui_connection_details)
                        .performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                    assertTrue(activity.findViewById<View>(R.id.reset_vpn_button).isShown)
                    scroll.scrollTo(0, 0)
                    assertTrue(activity.findViewById<View>(R.id.ui_open_settings)
                        .performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                    assertTrue(activity.findViewById<View>(R.id.ui_theme_choice).isShown)
                }
            }
        } finally {
            setFontScale("1.0")
            AppAppearance.SYSTEM.save(context)
        }
    }

    @Test
    @RequiresApi(Build.VERSION_CODES.Q)
    fun talkBackFocusAndAccessibilityActionsNavigateTheHomeScreen() {
        setFontScale("1.0")
        seedPreview()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val service = automation.serviceInfo
        val originalFlags = service.flags
        val manager = checkNotNull(context.getSystemService(AccessibilityManager::class.java))
        val talkBack = checkNotNull(manager.installedAccessibilityServiceList.firstOrNull {
            it.resolveInfo.serviceInfo.packageName == "com.google.android.marvin.talkback" &&
                it.resolveInfo.serviceInfo.name.endsWith(".TalkBackService")
        }) { "TalkBack is required for the screen reader test" }
        val resolver = context.contentResolver
        val servicesKey = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val enabledKey = Settings.Secure.ACCESSIBILITY_ENABLED
        val previousServices = Settings.Secure.getString(resolver, servicesKey)
        val previousEnabled = Settings.Secure.getString(resolver, enabledKey)
        val enabledServices = (previousServices.orEmpty().split(':').filter(String::isNotBlank) + talkBack.id)
            .distinct().joinToString(":")
        service.flags = originalFlags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = service
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            assertTrue(Settings.Secure.putString(resolver, servicesKey, enabledServices))
            assertTrue(Settings.Secure.putInt(resolver, enabledKey, 1))
            waitFor(timeoutMs = 30_000, description = "TalkBack did not enable touch exploration") {
                manager.isTouchExplorationEnabled &&
                    manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).any { it.id == talkBack.id }
            }
            // Give a fresh emulator time to finish opening the system TalkBack walkthrough before launching Kachur VPN.
            automation.waitForIdle(700, 8_000)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitProfile(scenario)
                val power = accessibleNode(automation, R.id.connect_vpn_button)
                assertEquals(Button::class.java.name, power.className.toString())
                assertTrue(power.contentDescription.contains(context.getString(R.string.connect_vpn)))
                assertTrue(power.isAccessibilityFocused || power.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                waitFor { accessibleNode(automation, R.id.connect_vpn_button).isAccessibilityFocused }

                val server = accessibleNode(automation, R.id.ui_server_card)
                assertEquals(Button::class.java.name, server.className.toString())
                assertTrue(server.contentDescription.contains(PREVIEW_PROFILE))
                assertTrue(server.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                assertTrue(accessibleNode(automation, R.id.profiles_import).isClickable)
                assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))

                assertTrue(accessibleNode(automation, R.id.ui_open_settings).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                assertTrue(accessibleNode(automation, R.id.ui_theme_choice).isClickable)
                assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))

                val homeTab = accessibleNode(automation, R.id.ui_home_tab)
                assertTrue(homeTab.isSelected)
                assertTrue(accessibleNode(automation, R.id.diag_open_screen).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                assertTrue(accessibleNode(automation, R.id.diag_run).isClickable)
                assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            }
        } finally {
            try {
                Settings.Secure.putString(resolver, servicesKey, previousServices)
                Settings.Secure.putString(resolver, enabledKey, previousEnabled)
            } finally {
                automation.dropShellPermissionIdentity()
                service.flags = originalFlags
                automation.serviceInfo = service
            }
        }
    }

    private fun accessibleNode(automation: UiAutomation, id: Int): AccessibilityNodeInfo {
        val name = context.resources.getResourceName(id)
        var node: AccessibilityNodeInfo? = null
        waitFor {
            node = automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(name)
                ?.firstOrNull { it.isVisibleToUser }
            node != null
        }
        return checkNotNull(node)
    }

    private fun seedPreview() {
        VpnLaunchSettings(context).setEnabled(false)
        val store = context.localProfiles()
        if (!store.exists()) {
            fun key(byte: Byte) = Base64.getEncoder().encodeToString(ByteArray(32) { byte })
            val demo = AwgConfigParser.parse("[Interface]\nPrivateKey=${key(1)}\n[Peer]\nPublicKey=${key(2)}")
            val first = ImportedProfile(PREVIEW_PROFILE, demo, "a".repeat(64))
            val second = ImportedProfile("Резервный сервер", demo, "b".repeat(64))
            store.append(listOf(first, second))
            VpnProfileLabels(context).saveCountry(second.documentKey, "Нидерланды")
        }
        SplitTunnelingSettings(context).addIpRoutes(setOf("192.0.2.0/24", "198.51.100.12", "2001:db8::/32"))
    }

    private fun awaitProfile(scenario: ActivityScenario<MainActivity>) = waitFor {
        var loaded = false
        scenario.onActivity { loaded = it.findViewById<TextView>(R.id.profile_name).text.toString() == PREVIEW_PROFILE }
        loaded
    }

    private fun checkPrimaryAction(activity: MainActivity) {
        val action = activity.findViewById<Button>(R.id.connect_vpn_button)
        assertTrue(action.isShown && action.height >= activity.dp(48))
        val visible = android.graphics.Rect()
        assertTrue(action.getGlobalVisibleRect(visible))
        assertEquals(action.height, visible.height())
        assertTrue(action.createAccessibilityNodeInfo().contentDescription.contains(activity.getString(R.string.connect_vpn)))
    }

    private fun assertContrast(activity: Activity) {
        for ((foreground, background) in listOf(
            R.color.ui_text to R.color.ui_surface,
            R.color.ui_text to R.color.ui_surface_high,
            R.color.ui_muted to R.color.ui_surface_high,
            R.color.ui_good to R.color.ui_good_surface,
            R.color.ui_on_primary to R.color.ui_primary_start,
            R.color.ui_on_primary to R.color.ui_primary_end,
        )) {
            assertTrue(ColorUtils.calculateContrast(activity.getColor(foreground), activity.getColor(background)) >= 4.5)
        }
        for (tone in PowerTone.entries) {
            assertTrue(ColorUtils.calculateContrast(tone.ink, tone.shade) >= 4.5)
        }
    }

    private fun launcherIcon(activity: Activity): AdaptiveIconDrawable =
        checkNotNull(activity.getDrawable(R.mipmap.ic_launcher) as? AdaptiveIconDrawable)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun captureIconPreview(activity: Activity, name: String) {
        assertSmallIconColors(launcherIcon(activity))
        assertTrue("Missing launcher monochrome layer", launcherIcon(activity).monochrome != null)
        val preview = activity.uiColumn(20).apply {
            setBackgroundColor(activity.getColor(R.color.ui_background))
            uiAdd(activity.uiLabel(25, bold = true).apply { text = "Значок приложения" })
            uiAdd(activity.uiCard().apply {
                uiAdd(activity.uiLabel(17, bold = true).apply { text = "Цветной" })
                uiAdd(iconSamples(activity) { launcherIcon(activity) }, 10)
            }, 14)
            uiAdd(activity.uiCard().apply {
                uiAdd(activity.uiLabel(17, bold = true).apply { text = "Монохромный" })
                uiAdd(iconSamples(activity) {
                    val layer = checkNotNull(launcherIcon(activity).monochrome).mutate()
                    layer.setTint(activity.getColor(R.color.ui_accent))
                    AdaptiveIconDrawable(ColorDrawable(activity.getColor(R.color.ui_accent_soft)), layer)
                }, 10)
            }, 14)
        }
        val width = activity.dp(380)
        preview.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        preview.layout(0, 0, width, preview.measuredHeight)
        val bitmap = Bitmap.createBitmap(width, preview.measuredHeight, Bitmap.Config.ARGB_8888)
        preview.draw(Canvas(bitmap))
        save(name, bitmap)
    }

    private fun iconSamples(activity: Activity, newIcon: () -> Drawable): LinearLayout =
        LinearLayout(activity).apply {
            gravity = Gravity.BOTTOM
            for (size in listOf(24, 32, 48, 64)) {
                val tile = activity.uiColumn().apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    val icon = ImageView(activity).apply { setImageDrawable(newIcon()) }
                    addView(icon, LinearLayout.LayoutParams(activity.dp(size), activity.dp(size)))
                    uiAdd(activity.uiLabel(12, R.color.ui_muted).apply {
                        text = "$size dp"
                        gravity = Gravity.CENTER
                    }, 4)
                }
                addView(tile, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

    private fun assertSmallIconColors(icon: Drawable) {
        val size = 24
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, size, size)
        icon.draw(Canvas(bitmap))
        var black = 0
        var tan = 0
        var blue = 0
        for (y in 0 until size) for (x in 0 until size) {
            val color = bitmap.getPixel(x, y)
            if (Color.alpha(color) < 224) continue
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            when {
                maxOf(r, g, b) < 65 -> black++
                r > 150 && r > g + 25 && g > b + 10 -> tan++
                b > 120 && b > r + 25 && b > g + 15 -> blue++
            }
        }
        bitmap.recycle()
        assertTrue("Black silhouette at 24 px", black >= 8)
        assertTrue("Tan markings at 24 px", tan >= 8)
        assertTrue("Blue-violet shield at 24 px", blue >= 8)
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

    private fun waitFor(
        timeoutMs: Long = 8_000L,
        description: String = "Screen did not finish laying out",
        ready: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        do {
            instrumentation.waitForIdleSync()
            if (ready()) return
            Thread.sleep(40)
        } while (System.nanoTime() < deadline)
        assertTrue(description, ready())
    }

    private companion object {
        const val PREVIEW_PROFILE = "Frankfurt am Main"
    }
}
