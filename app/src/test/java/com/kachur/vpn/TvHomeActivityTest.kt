package com.kachur.vpn

import android.view.View
import android.content.ComponentName
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestKachurApplication::class)
class TvHomeActivityTest {
    @Test
    fun launcherResolvesTvBannerAndExplicitFlatIcon() {
        val app = RuntimeEnvironment.getApplication() as KachurApplication
        val info = app.packageManager.getActivityInfo(ComponentName(app, TvHomeActivity::class.java), 0)
        assertEquals(R.drawable.tv_banner, info.banner)
        assertEquals(R.drawable.ic_tv_launcher, info.icon)
        assertNotNull(info.loadBanner(app.packageManager))
        assertNotNull(info.loadIcon(app.packageManager))
    }

    @Test
    fun autoConnectChoiceSurvivesActivityRecreationAndEnablingClearsPause() {
        val settings = VpnLaunchSettings(RuntimeEnvironment.getApplication())
        settings.setEnabled(false)
        settings.setPaused(true)
        VpnConnection.reset()
        try {
            Robolectric.buildActivity(TvHomeActivity::class.java).setup().use { controller ->
                fun toggle(): Button = descendants(controller.get().findViewById(android.R.id.content))
                    .filterIsInstance<Button>().single { it.text.startsWith("Автоподключение:") }
                assertEquals("Автоподключение: выключено", toggle().text.toString())
                toggle().performClick()
                assertTrue(settings.enabled)
                assertFalse(settings.paused)
                controller.recreate()
                assertEquals("Автоподключение: включено", toggle().text.toString())
                toggle().performClick()
                controller.recreate()
                assertFalse(settings.enabled)
                assertEquals("Автоподключение: выключено", toggle().text.toString())
            }
        } finally {
            VpnConnection.reset()
        }
    }

    @Test
    fun remoteControlsAreInsideAScrollableScreen() {
        Robolectric.buildActivity(TvHomeActivity::class.java).setup().use { controller ->
            val content = controller.get().findViewById<ViewGroup>(android.R.id.content)
            val scroll = content.getChildAt(0) as ScrollView
            val buttons = descendants(scroll).filterIsInstance<Button>().toList()
            assertEquals(9, buttons.size)
            assertTrue(scroll.isFillViewport)
            assertTrue(buttons.all { it.isFocusable })
        }
    }

    @Test
    fun remoteSwitchesPersistAndRestoreTheRoutingSelection() {
        val settings = SplitTunnelingSettings(RuntimeEnvironment.getApplication())
        settings.preferences.edit().clear().commit()
        try {
            settings.setExcluded("com.example.tv", true)
            settings.addIpRoutes(setOf("192.0.2.0/24"))
            Robolectric.buildActivity(TvHomeActivity::class.java).setup().use { controller ->
                fun toggle(id: Int) = controller.get().findViewById<android.widget.Switch>(id)
                val initial = settings.snapshot()
                for (id in listOf(R.id.tv_variable_padding, R.id.tv_route_apps, R.id.tv_route_ips)) {
                    toggle(id).performClick()
                }
                assertEquals(VpnRoutingSettings(emptySet(), emptySet(), true), settings.snapshot())
                controller.recreate()
                assertTrue(toggle(R.id.tv_variable_padding).isChecked)
                assertFalse(toggle(R.id.tv_route_apps).isChecked)
                assertFalse(toggle(R.id.tv_route_ips).isChecked)
                for (id in listOf(R.id.tv_variable_padding, R.id.tv_route_apps, R.id.tv_route_ips)) {
                    toggle(id).performClick()
                }
                assertEquals(initial, settings.snapshot())
            }
        } finally {
            settings.preferences.edit().clear().commit()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }
}
