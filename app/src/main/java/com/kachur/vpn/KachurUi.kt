package com.kachur.vpn

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import kotlin.math.roundToInt

internal enum class AppAppearance(val label: Int, private val night: Int?) {
    SYSTEM(R.string.ui_theme_system, null),
    LIGHT(R.string.ui_theme_light, Configuration.UI_MODE_NIGHT_NO),
    DARK(R.string.ui_theme_dark, Configuration.UI_MODE_NIGHT_YES);

    fun applyTo(base: Context): Context = night?.let { mode ->
        base.createConfigurationContext(Configuration().apply { uiMode = mode })
    } ?: base

    fun save(context: Context) = preferences(context).edit().putString("mode", name).apply()

    companion object {
        private fun preferences(context: Context) = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

        fun read(context: Context): AppAppearance {
            val saved = preferences(context).getString("mode", null)
            return entries.firstOrNull { it.name == saved } ?: SYSTEM
        }
    }
}

abstract class KachurActivity : ComponentActivity() {
    private var appliedAppearance = AppAppearance.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        appliedAppearance = AppAppearance.read(newBase)
        super.attachBaseContext(appliedAppearance.applyTo(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val light = resources.getBoolean(R.bool.ui_light_bars)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
    }

    override fun onResume() {
        super.onResume()
        if (AppAppearance.read(this) != appliedAppearance) recreate()
    }
}

internal fun Context.dp(size: Int): Int = (resources.displayMetrics.density * size).roundToInt()

internal fun Context.uiStackControls(): Boolean =
    resources.configuration.fontScale >= 1.35f || resources.configuration.screenWidthDp in 1..339

internal fun View.uiInsets() {
    val left = paddingLeft
    val top = paddingTop
    val right = paddingRight
    val bottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + maxOf(bars.bottom, ime.bottom))
        insets
    }
}

internal fun Context.uiColumn(padding: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
}

internal fun Context.uiScroll(content: View): ScrollView = ScrollView(this).apply {
    isFillViewport = true
    clipToPadding = false
    overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
}

internal fun Context.uiShape(fill: Int, radius: Int, stroke: Int? = null): GradientDrawable =
    GradientDrawable().apply {
        setColor(getColor(fill))
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), getColor(stroke))
    }

internal fun Context.uiGradient(start: Int, end: Int, radius: Int): GradientDrawable =
    GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(getColor(start), getColor(end))).apply {
        cornerRadius = dp(radius).toFloat()
    }

internal fun Context.uiPanel(radius: Int = 18, border: Int = R.color.ui_border): GradientDrawable =
    uiGradient(R.color.ui_surface_high, R.color.ui_surface, radius).apply { setStroke(dp(1), getColor(border)) }

internal fun Context.uiCard(padding: Int = 16): LinearLayout = uiColumn(padding).apply {
    background = uiPanel()
}

internal fun Context.uiActionRow(): LinearLayout = LinearLayout(this).apply {
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = dp(64)
    setPaddingRelative(dp(14), dp(10), dp(14), dp(10))
    background = RippleDrawable(ColorStateList.valueOf(getColor(R.color.ui_ripple)), uiPanel(), null)
    isFocusable = true
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = Button::class.java.name
        }
    })
}

internal fun LinearLayout.uiAdd(child: View, gap: Int = 0) {
    addView(child, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.dp(gap) })
}

internal fun Context.uiLabel(size: Int = 16, color: Int = R.color.ui_text, bold: Boolean = false): TextView =
    TextView(this).apply {
        textSize = size.toFloat()
        setTextColor(getColor(color))
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

internal fun Context.uiHeading(label: Int, size: Int = 22): TextView = uiLabel(size, bold = true).apply {
    setText(label)
    ViewCompat.setAccessibilityHeading(this, true)
}

internal fun Context.uiCaption(label: Int): TextView = uiLabel(14, R.color.ui_muted).apply { setText(label) }

internal fun Context.uiGlyph(drawable: Int, color: Int = R.color.ui_accent): Drawable =
    requireNotNull(getDrawable(drawable)).mutate().apply { setTint(getColor(color)) }

internal fun Context.uiDecorativeIcon(drawable: Int, color: Int = R.color.ui_accent): ImageView =
    ImageView(this).apply {
        setImageDrawable(uiGlyph(drawable, color))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

internal fun Context.uiButton(label: Int, primary: Boolean = false, icon: Int? = null): Button = Button(this).apply {
    if (label != 0) setText(label)
    isAllCaps = false
    minHeight = dp(if (primary) 56 else 48)
    minimumWidth = dp(48)
    textSize = 16f
    gravity = Gravity.CENTER
    stateListAnimator = null
    setPaddingRelative(dp(14), dp(9), dp(14), dp(9))
    val colors = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(getColor(R.color.ui_muted), getColor(if (primary) R.color.ui_on_primary else R.color.ui_accent)),
    )
    setTextColor(colors)
    background = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), uiShape(R.color.ui_accent_soft, 16))
        val normal = if (primary) uiGradient(R.color.ui_primary_start, R.color.ui_primary_end, 18)
            else uiShape(R.color.ui_accent_soft, 16)
        addState(intArrayOf(), RippleDrawable(ColorStateList.valueOf(getColor(R.color.ui_ripple)), normal, null))
    }
    if (icon != null) {
        compoundDrawablePadding = dp(8)
        setCompoundDrawablesRelativeWithIntrinsicBounds(
            uiGlyph(icon).apply { setTintList(colors) }, null, null, null,
        )
    }
}

internal fun Context.uiIconButton(label: Int, glyph: Int, quiet: Boolean = false): ImageButton = ImageButton(this).apply {
    contentDescription = getString(label)
    minimumWidth = dp(48)
    minimumHeight = dp(48)
    setImageDrawable(uiGlyph(glyph))
    background = RippleDrawable(
        ColorStateList.valueOf(getColor(R.color.ui_ripple)),
        if (quiet) null else uiShape(R.color.ui_accent_soft, 14),
        if (quiet) uiShape(android.R.color.white, 14) else null,
    )
}

internal fun Context.uiSettingsLink(label: Int, glyph: Int): Button = uiButton(label, icon = glyph).apply {
    gravity = Gravity.START or Gravity.CENTER_VERTICAL
    setCompoundDrawablesRelativeWithIntrinsicBounds(
        compoundDrawablesRelative[0], null, uiGlyph(R.drawable.ic_ui_chevron), null,
    )
}

internal fun Context.uiHeader(label: Int, back: () -> Unit): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    addView(uiIconButton(R.string.split_back, R.drawable.ic_ui_back).apply {
        setOnClickListener { back() }
    }, LinearLayout.LayoutParams(dp(48), dp(48)))
    addView(uiHeading(label, 27), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
}

internal fun Context.uiPair(first: View, second: View): LinearLayout = LinearLayout(this).apply {
    if (context.uiStackControls()) {
        orientation = LinearLayout.VERTICAL
        uiAdd(first)
        uiAdd(second, 8)
    } else {
        orientation = LinearLayout.HORIZONTAL
        addView(first, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        addView(second, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
    }
}

internal fun EditText.uiField() {
    minHeight = context.dp(48)
    background = context.uiShape(R.color.ui_surface, 14, R.color.ui_border)
    setPaddingRelative(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
    setTextColor(context.getColor(R.color.ui_text))
    setHintTextColor(context.getColor(R.color.ui_muted))
}

internal fun Switch.uiSwitch() {
    minHeight = context.dp(52)
    setPaddingRelative(0, context.dp(8), 0, context.dp(8))
    switchPadding = context.dp(14)
    setTextColor(context.getColor(R.color.ui_text))
    textSize = 16f
}
