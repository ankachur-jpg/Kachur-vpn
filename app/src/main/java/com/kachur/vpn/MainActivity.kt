package com.kachur.vpn

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.rgb(18, 24, 35))
        }
        val title = TextView(this).apply {
            text = "Kachur VPN"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val status = TextView(this).apply {
            text = "VPN не подключён"
            textSize = 18f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 40, 0, 40)
        }
        val button = Button(this).apply {
            text = "Подключить VPN"
            setOnClickListener {
                status.text = "VPN пока не настроен"
            }
        }
        layout.addView(title)
        layout.addView(status)
        layout.addView(button)
        setContentView(layout)
    }
}
