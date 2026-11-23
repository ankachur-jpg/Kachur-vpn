package com.kachur.vpn

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestKachurApplication::class)
class TvHomeActivityTest {
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
            assertEquals(5, buttons.size)
            assertTrue(scroll.isFillViewport)
            assertTrue(buttons.all { it.isFocusable })
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }
}
