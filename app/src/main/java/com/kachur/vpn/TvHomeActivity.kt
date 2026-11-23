package com.kachur.vpn

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Remote-first TV entry point. The existing phone activity and tunnel engine are unchanged.
 * Imports use MainActivity; connections use the existing quick-connect consent flow.
 */
class TvHomeActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var autoStart: Button
    private val observer: () -> Unit = { updateStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scale = resources.displayMetrics.density
        fun dp(value: Int) = (value * scale + 0.5f).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(dp(48), dp(32), dp(48), dp(32))
            setBackgroundColor(0xff101923.toInt())
        }
        fun label(value: String, size: Float): TextView = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(0xffffffff.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(label("Kachur VPN · Android TV", 30f))
        status = label("", 20f)
        root.addView(status, LinearLayout.LayoutParams(-1, dp(76)))
        fun action(title: String, onClick: () -> Unit): Button = Button(this).apply {
            text = title
            textSize = 20f
            isFocusable = true
            isFocusableInTouchMode = false
            minHeight = dp(64)
            setOnClickListener { onClick() }
            root.addView(this, LinearLayout.LayoutParams(dp(420), dp(72)).apply {
                topMargin = dp(14)
            })
        }
        val open = action("Подключить VPN") {
            startActivity(VpnQuickActivity.intent(this))
        }
        action("Выбрать сервер / импортировать профиль") {
            startActivity(Intent(this, MainActivity::class.java))
        }
        action("Отключить VPN") {
            VpnConnection.stop()
            updateStatus()
        }
        autoStart = action("Автоподключение") {
            val settings = VpnLaunchSettings(applicationContext)
            val enabled = !settings.enabled
            settings.setEnabled(enabled)
            if (enabled) settings.setPaused(false)
            updateStatus()
        }
        root.addView(label("VPN включается после загрузки приставки.\nСначала выберите профиль и один раз разрешите VPN.", 16f),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        action("Настройки и профили") {
            startActivity(Intent(this, MainActivity::class.java))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })
        open.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        VpnConnection.observe(observer)
        updateStatus()
    }

    override fun onStop() {
        VpnConnection.removeObserver(observer)
        super.onStop()
    }

    private fun updateStatus() {
        status.text = getString(VpnConnection.message)
        val settings = VpnLaunchSettings(applicationContext)
        autoStart.text = if (settings.enabled) "Автоподключение: включено" else "Автоподключение: выключено"
    }
}
