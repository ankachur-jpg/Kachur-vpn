package com.kachur.vpn

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TvLauncherUiTest {
    @Test
    fun tvLauncherArtworkRendersVisibleBranding() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.packageManager
        val activity = manager.getActivityInfo(ComponentName(context, TvHomeActivity::class.java), 0)
        assertEquals(R.drawable.tv_banner, activity.banner)
        assertEquals(R.drawable.ic_tv_launcher, activity.icon)
        fun render(drawable: Drawable, width: Int, height: Int, name: String) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
            var brightPixels = 0
            for (y in 0 until height) for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.red(pixel) > 200 && Color.green(pixel) > 200 && Color.blue(pixel) > 200) brightPixels++
            }
            assertTrue("Launcher artwork must contain visible white branding", brightPixels > 200)
            val output = File(context.filesDir, "ui-screenshots/$name.png")
            output.parentFile!!.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        render(checkNotNull(activity.loadBanner(manager)), 320, 180, "12-tv-banner")
        render(activity.loadIcon(manager), 216, 216, "13-tv-icon")
    }
}
