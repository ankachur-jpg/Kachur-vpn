package com.kachur.vpn

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.io.IOException
import java.util.concurrent.Executors

class IpRoutesActivity : KachurActivity() {
    private val settings by lazy { SplitTunnelingSettings(applicationContext) }
    private val session by lazy { ViewModelProvider(this)[IpImportSession::class.java] }
    private val vpnObserver: () -> Unit = ::renderVpn
    private val downloadsObserver by lazy { settings.onDownloadsChanged(::renderVpn) }
    private lateinit var importButton: Button
    private lateinit var feedback: TextView
    private lateinit var search: EditText
    private lateinit var input: EditText
    private lateinit var empty: TextView
    private lateinit var reconnect: Button
    private lateinit var vpnStatus: TextView
    private val routes = RouteList()
    private var savedRoutes = emptyList<String>()
    private var applyingRoutes = false

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uri = data?.data ?: data?.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
        when {
            result.resultCode == Activity.RESULT_CANCELED -> session.show(R.string.ip_import_cancelled)
            result.resultCode != Activity.RESULT_OK || uri?.scheme != ContentResolver.SCHEME_CONTENT ->
                session.show(R.string.ip_import_read)
            else -> session.load(applicationContext, uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyingRoutes = savedInstanceState?.getBoolean(APPLYING) == true
        if (savedInstanceState?.getBoolean(IMPORTING) == true && !session.loading && session.feedback == null) {
            session.show(R.string.ip_import_interrupted)
        }
        val header = uiColumn(18)
        header.uiAdd(uiHeader(R.string.ip_title) { finish() })
        header.uiAdd(uiCaption(R.string.ip_description), 12)
        header.uiAdd(uiCard().apply {
            val sources = uiSettingsLink(R.string.ip_update_title, R.drawable.ic_ui_routes).apply {
                id = R.id.ip_open_sources
                setOnClickListener { startActivity(Intent(this@IpRoutesActivity, IpSourcesActivity::class.java)) }
            }
            importButton = uiButton(R.string.ip_import, primary = true, icon = R.drawable.ic_ui_import).apply {
                id = R.id.ip_import_button
                setOnClickListener { openFilePicker() }
            }
            uiAdd(sources)
            uiAdd(importButton, 8)
            feedback = uiLabel(15).apply {
                id = R.id.ip_import_feedback
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            uiAdd(feedback, 8)
            reconnect = uiButton(R.string.split_apply_reconnect).apply {
                id = R.id.ip_reconnect_button
                setOnClickListener {
                    applyingRoutes = true
                    VpnConnection.reconnectWithSavedExclusions(applicationContext)
                    renderVpn()
                }
            }
            uiAdd(reconnect, 8)
            vpnStatus = uiLabel(15).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
            uiAdd(vpnStatus, 6)
        }, 14)
        search = routeField(R.id.ip_search, R.string.ip_search_hint, EditorInfo.IME_ACTION_SEARCH)
        header.uiAdd(search, 14)
        input = routeField(R.id.ip_input, R.string.ip_add_hint, EditorInfo.IME_ACTION_DONE)
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_DONE) false else {
                addRoute()
                true
            }
        }
        val add = uiButton(R.string.ip_add).apply {
            id = R.id.ip_add_button
            setOnClickListener { addRoute() }
        }
        header.uiAdd(uiPair(input, add), 8)
        empty = uiLabel(15, R.color.ui_muted)
        header.uiAdd(empty, 10)
        val list = ListView(this).apply {
            id = R.id.ip_list
            itemsCanFocus = true
            addHeaderView(header, null, false)
            divider = android.graphics.drawable.ColorDrawable(getColor(R.color.ui_background))
            dividerHeight = dp(8)
            adapter = routes
        }
        val root = uiColumn().apply {
            isFocusableInTouchMode = true
            uiInsets()
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = showMatches()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        setContentView(root)
        root.requestFocus()
        render()
    }

    override fun onStart() {
        super.onStart()
        session.onChange = ::render
        settings.preferences.registerOnSharedPreferenceChangeListener(downloadsObserver)
        VpnConnection.observe(vpnObserver)
        render()
    }

    override fun onStop() {
        settings.preferences.unregisterOnSharedPreferenceChangeListener(downloadsObserver)
        VpnConnection.removeObserver(vpnObserver)
        session.onChange = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(IMPORTING, session.loading)
        outState.putBoolean(APPLYING, applyingRoutes)
    }

    private fun routeField(viewId: Int, hint: Int, action: Int) = EditText(this).apply {
        id = viewId
        setHint(hint)
        uiField()
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = action or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }

    private fun openFilePicker() {
        if (session.loading) return
        try {
            picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                // Some providers label JSON as text/plain or application/octet-stream.
                type = "*/*"
            })
        } catch (_: RuntimeException) {
            session.show(R.string.ip_import_picker_failed)
        }
    }

    private fun addRoute() {
        val route = IpRoutePrefix.parse(input.text.toString())
        if (route == null) {
            session.show(R.string.ip_invalid)
            return
        }
        try {
            val added = settings.addIpRoutes(setOf(route.toString()))
            if (added != 0) input.text.clear()
            if (added == 0) session.show(R.string.ip_duplicate) else session.show(R.string.ip_import_success, added)
        } catch (error: IpImportException) {
            session.show(error.error.message, IpRouteImport.MAX_ENTRIES)
        }
    }

    private fun render() {
        savedRoutes = settings.manualIpRoutes().sorted()
        importButton.isEnabled = !session.loading
        feedback.text = session.feedback?.let { (message, value) ->
            if (value == null) getString(message) else getString(message, value)
        }.orEmpty()
        feedback.visibility = if (feedback.text.isEmpty()) View.GONE else View.VISIBLE
        showMatches()
        renderVpn()
    }

    private fun showMatches() {
        val query = search.text.toString().trim()
        val exact = IpRoutePrefix.parse(query)?.toString()
        routes.items = savedRoutes.filter { it.contains(query, ignoreCase = true) || it == exact }
        empty.setText(if (savedRoutes.isEmpty()) R.string.ip_no_routes else R.string.ip_no_matches)
        empty.visibility = if (routes.items.isEmpty()) View.VISIBLE else View.GONE
        routes.notifyDataSetChanged()
    }

    private fun renderVpn() {
        val needsReconnect = VpnConnection.routingNeedsReconnect(applicationContext)
        reconnect.visibility = if (needsReconnect) View.VISIBLE else View.GONE
        reconnect.isEnabled = !VpnConnection.checkingRoutes
        vpnStatus.visibility = if (needsReconnect || applyingRoutes) View.VISIBLE else View.GONE
        val message = VpnConnection.message
        vpnStatus.text = when {
            VpnConnection.checkingRoutes -> getString(R.string.ip_vpn_route_checking)
            message == R.string.ip_vpn_route_error && applyingRoutes ->
                VpnConnection.lastError?.display(this) ?: getString(message)
            needsReconnect && !applyingRoutes -> getString(R.string.ip_reconnect_note)
            else -> getString(message)
        }
    }

    private inner class RouteList : BaseAdapter() {
        var items = emptyList<String>()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView as? RouteRow ?: RouteRow()
            val route = getItem(position)
            row.label.text = route
            row.remove.contentDescription = getString(R.string.ip_remove_route, route)
            row.remove.setOnClickListener {
                settings.removeIpRoute(route)
                session.show(R.string.ip_removed)
            }
            return row
        }
    }

    private inner class RouteRow : LinearLayout(this@IpRoutesActivity) {
        val label = context.uiLabel(16, bold = true)
        val remove = context.uiButton(R.string.ip_remove).apply {
            id = R.id.ip_remove_button
        }

        init {
            orientation = VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            uiAdd(context.uiCard(12).apply { uiAdd(context.uiPair(label, remove)) })
        }
    }

    private companion object {
        const val IMPORTING = "importing_ip_json"
        const val APPLYING = "applying_ip_routes"
    }
}

internal class IpImportSession : ViewModel() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var cleared = false
    var loading = false
        private set
    var feedback: Pair<Int, Int?>? = null
        private set
    var onChange: (() -> Unit)? = null

    fun show(message: Int, value: Int? = null) {
        feedback = message to value
        onChange?.invoke()
    }

    fun load(context: Context, uri: Uri) {
        if (loading) return
        loading = true
        show(R.string.ip_import_loading)
        worker.execute {
            val result = try {
                val source = context.contentResolver.openInputStream(uri) ?: throw IOException()
                Result.success(source.use(IpRouteImport::read))
            } catch (error: IpImportException) {
                Result.failure(error)
            } catch (_: IOException) {
                Result.failure(IpImportException(IpImportError.READ))
            } catch (_: RuntimeException) {
                Result.failure(IpImportException(IpImportError.READ))
            }
            main.post {
                if (cleared) return@post
                loading = false
                try {
                    val count = SplitTunnelingSettings(context).addIpRoutes(result.getOrThrow())
                    show(R.string.ip_import_success, count)
                } catch (error: IpImportException) {
                    show(error.error.message, if (error.error == IpImportError.LIMIT) IpRouteImport.MAX_ENTRIES else error.row)
                }
            }
        }
    }

    override fun onCleared() {
        cleared = true
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }
}
