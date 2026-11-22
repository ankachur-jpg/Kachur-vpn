package com.kachur.vpn

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.ImageView
import androidx.core.graphics.ColorUtils

internal enum class PowerTone(
    val rim: Int,
    val highlight: Int,
    val shade: Int,
    val center: Int,
    val ink: Int,
) {
    READY(0xff7384f5.toInt(), 0xffb9ccff.toInt(), 0xff2b376c.toInt(), 0xff131936.toInt(), 0xffeef2ff.toInt()),
    ACTIVE(0xff24d97a.toInt(), 0xff9afaaa.toInt(), 0xff145c3d.toInt(), 0xff071e23.toInt(), 0xffc7ffdb.toInt()),
    ERROR(0xffe4739e.toInt(), 0xffffb0c7.toInt(), 0xff5f2c46.toInt(), 0xff2a192d.toInt(), 0xffffe5ed.toInt()),
}

internal fun Context.powerDisc(tone: PowerTone, compact: Boolean): Drawable {
    val outline = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(tone.highlight, tone.rim),
    )
    val face = GradientDrawable().apply {
        colors = intArrayOf(tone.center, tone.center, tone.shade)
        gradientType = GradientDrawable.RADIAL_GRADIENT
        gradientRadius = dp(if (compact) 220 else 94).toFloat()
        setGradientCenter(0.5f, 0.56f)
    }
    for (layer in listOf(outline, face)) {
        if (compact) layer.cornerRadius = dp(24).toFloat() else layer.shape = GradientDrawable.OVAL
    }
    val disc = LayerDrawable(arrayOf(outline, face)).apply {
        val thickness = dp(if (compact) 2 else 6)
        setLayerInset(1, thickness, thickness, thickness, thickness)
    }
    return RippleDrawable(ColorStateList.valueOf(getColor(R.color.ui_ripple)), disc, null)
}

/** Static gradients behind the power control. Shaders are rebuilt only when the bounds or phase changes. */
internal class PowerHaloDrawable(private val tone: PowerTone, private val horizon: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbit = RectF()
    private var atmosphere: Shader? = null
    private var glow: Shader? = null
    private var edge: Shader? = null
    private var radius = 0f

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        radius = minOf(bounds.width(), bounds.height()) / 2f
        if (radius <= 0f) return
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        val orbitRadius = radius * 0.88f
        orbit.set(x - orbitRadius, y - orbitRadius, x + orbitRadius, y + orbitRadius)
        atmosphere = RadialGradient(
            x, y - radius * 0.48f, radius * 0.99f,
            intArrayOf(ColorUtils.setAlphaComponent(horizon, 174), ColorUtils.setAlphaComponent(horizon, 54), Color.TRANSPARENT),
            floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP,
        )
        glow = RadialGradient(
            x, y, radius,
            intArrayOf(Color.TRANSPARENT, ColorUtils.setAlphaComponent(tone.rim, 120), Color.TRANSPARENT),
            floatArrayOf(0.56f, 0.78f, 1f), Shader.TileMode.CLAMP,
        )
        edge = LinearGradient(
            x, orbit.top, x, orbit.top + radius * 0.48f,
            intArrayOf(ColorUtils.blendARGB(horizon, Color.WHITE, 0.48f), horizon, Color.TRANSPARENT),
            floatArrayOf(0f, 0.36f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        if (radius <= 0f) return
        paint.style = Paint.Style.FILL
        paint.shader = atmosphere
        canvas.drawRect(bounds, paint)
        paint.shader = glow
        canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius * 0.012f
        paint.shader = edge
        canvas.drawArc(orbit, 204f, 132f, false, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

internal fun ImageView.showServerBadge(country: String?) {
    if (country.equals(context.getString(R.string.vpn_country_default), ignoreCase = true)) {
        setImageResource(R.drawable.ic_ui_flag_de)
        background = null
    } else {
        setImageDrawable(context.uiGlyph(R.drawable.ic_ui_servers))
        background = context.uiShape(R.color.ui_accent_soft, 12)
    }
}
