package com.kachur.vpn

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import java.util.concurrent.Executors

class SplitTunnelingActivity : KachurActivity() {
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
        val header = uiColumn(18)
        header.uiAdd(uiHeader(R.string.split_title) { finish() })
        header.uiAdd(uiCaption(R.string.split_description), 12)
        header.uiAdd(uiButton(R.string.ip_title, primary = true).apply {
            id = R.id.ip_open_button
            setOnClickListener { startActivity(Intent(this@SplitTunnelingActivity, IpRoutesActivity::class.java)) }
        }, 14)
        reconnect = uiButton(R.string.split_apply_reconnect).apply {
            id = R.id.split_reconnect_button
            setOnClickListener {
                VpnConnection.reconnectWithSavedExclusions(applicationContext)
                finish()
            }
        }
        header.uiAdd(reconnect, 8)
        search = EditText(this).apply {
            id = R.id.split_search
            setHint(R.string.split_search_hint)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            uiField()
        }
        header.uiAdd(search, 14)
        categories = Spinner(this).apply {
            id = R.id.split_categories
            minimumHeight = dp(48)
            contentDescription = getString(R.string.split_categories)
            adapter = ArrayAdapter(
                this@SplitTunnelingActivity, android.R.layout.simple_spinner_item, AppCategory.entries.map { getString(it.title) },
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        header.uiAdd(categories, 8)
        empty = uiLabel(15, R.color.ui_muted).apply { id = R.id.split_empty }
        header.uiAdd(empty, 6)
        val list = ListView(this).apply {
            id = R.id.split_apps
            itemsCanFocus = true
            addHeaderView(header, null, false)
            divider = android.graphics.drawable.ColorDrawable(getColor(R.color.ui_background))
            dividerHeight = dp(8)
            adapter = this@SplitTunnelingActivity.adapter
        }
        val root = uiColumn().apply {
            isFocusableInTouchMode = true
            uiInsets()
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
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
        empty.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
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
        val icon = ImageView(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val label = context.uiLabel(16, bold = true)
        val packageName = context.uiLabel(13, R.color.ui_muted)
        val toggle = Switch(context).apply {
            id = R.id.split_app_switch
            minimumWidth = dp(48)
            minimumHeight = dp(48)
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            background = context.uiShape(R.color.ui_surface, 16, R.color.ui_border)
            setPadding(dp(14), dp(8), dp(14), dp(8))
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

}
