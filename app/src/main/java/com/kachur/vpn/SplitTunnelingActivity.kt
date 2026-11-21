package com.kachur.vpn

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils.TruncateAt
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import java.util.concurrent.Executors

class SplitTunnelingActivity : Activity() {
    private val settings by lazy { SplitTunnelingSettings(applicationContext) }
    private val loader = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val vpnObserver: () -> Unit = ::renderReconnect
    private val downloadsObserver by lazy { settings.onDownloadsChanged(::renderReconnect) }
    private lateinit var search: EditText
    private lateinit var categories: Spinner
    private lateinit var reconnect: Button
    private lateinit var empty: TextView
    private val adapter = AppList()
    private var apps = emptyList<InstalledApp>()
    private var selection = emptySet<String>()
    private var listMessage = R.string.split_loading

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setBackgroundColor(Color.rgb(18, 24, 35))
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime()).bottom
                } else {
                    insets.systemWindowInsetBottom
                }
                @Suppress("DEPRECATION")
                view.setPadding(
                    insets.systemWindowInsetLeft + dp(16), insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight + dp(16), bottom,
                )
                insets
            }
        }
        root.addView(Button(this).apply {
            setText(R.string.split_back)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(TextView(this).apply {
            setText(R.string.split_title)
            setTextColor(Color.WHITE)
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            setText(R.string.split_description)
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(6), 0, dp(6))
        })
        root.addView(Button(this).apply {
            id = R.id.ip_open_button
            setText(R.string.ip_title)
            setOnClickListener { startActivity(Intent(this@SplitTunnelingActivity, IpRoutesActivity::class.java)) }
        })
        reconnect = Button(this).apply {
            id = R.id.split_reconnect_button
            setText(R.string.split_apply_reconnect)
            setOnClickListener {
                VpnConnection.reconnectWithSavedExclusions(applicationContext)
                finish()
            }
        }
        root.addView(reconnect)
        search = EditText(this).apply {
            id = R.id.split_search
            setHint(R.string.split_search_hint)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        root.addView(search)
        categories = Spinner(this).apply {
            id = R.id.split_categories
            contentDescription = getString(R.string.split_categories)
            adapter = ArrayAdapter(
                this@SplitTunnelingActivity, android.R.layout.simple_spinner_item, AppCategory.entries.map { getString(it.title) },
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        root.addView(categories)
        empty = TextView(this).apply {
            id = R.id.split_empty
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
        }
        val list = ListView(this).apply {
            id = R.id.split_apps
            adapter = this@SplitTunnelingActivity.adapter
        }
        root.addView(FrameLayout(this).apply {
            addView(list)
            addView(empty)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        list.emptyView = empty
        setContentView(root)
        root.requestFocus()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderApps()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        categories.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = renderApps()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        renderApps()
        renderReconnect()
    }

    override fun onStart() {
        super.onStart()
        settings.preferences.registerOnSharedPreferenceChangeListener(downloadsObserver)
        selection = settings.excludedPackages()
        VpnConnection.observe(vpnObserver)
        renderApps()
        renderReconnect()
        val context = applicationContext
        loader.execute {
            val result = try {
                installedApps(context)
            } catch (_: RuntimeException) {
                null
            }
            main.post {
                if (isDestroyed) return@post
                apps = result.orEmpty()
                listMessage = if (result == null) R.string.split_load_error else R.string.split_no_matches
                renderApps()
            }
        }
    }

    override fun onStop() {
        settings.preferences.unregisterOnSharedPreferenceChangeListener(downloadsObserver)
        VpnConnection.removeObserver(vpnObserver)
        super.onStop()
    }

    override fun onDestroy() {
        loader.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun renderApps() {
        val query = search.text.toString().trim()
        val category = AppCategory.entries.getOrElse(categories.selectedItemPosition) { AppCategory.ALL }
        empty.setText(listMessage)
        adapter.items = apps.filter { it.matches(query, category) }
        adapter.notifyDataSetChanged()
    }

    private fun renderReconnect() {
        reconnect.isEnabled = !VpnConnection.checkingRoutes
        reconnect.visibility = if (VpnConnection.routingNeedsReconnect(applicationContext)) View.VISIBLE else View.GONE
    }

    private inner class AppList : BaseAdapter() {
        var items = emptyList<InstalledApp>()

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView as? AppRow ?: AppRow()
            val app = getItem(position)
            row.icon.setImageDrawable(app.icon)
            row.label.text = app.label
            row.packageName.text = app.packageName
            row.toggle.setOnCheckedChangeListener(null)
            row.toggle.contentDescription = getString(R.string.split_direct_toggle, app.label)
            row.toggle.isChecked = app.packageName in selection
            row.toggle.setOnCheckedChangeListener { _, checked ->
                settings.setExcluded(app.packageName, checked)
                selection = settings.excludedPackages()
                renderReconnect()
            }
            return row
        }
    }

    private inner class AppRow : LinearLayout(this@SplitTunnelingActivity) {
        val icon = ImageView(context)
        val label = TextView(context).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
        }
        val packageName = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.LTGRAY)
            maxLines = 1
            ellipsize = TruncateAt.END
        }
        val toggle = Switch(context).apply {
            id = R.id.split_app_switch
            minimumWidth = dp(48)
            minimumHeight = dp(48)
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(0, dp(6), 0, dp(6))
            addView(icon, LayoutParams(dp(36), dp(36)))
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
                addView(label)
                addView(packageName)
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(toggle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
