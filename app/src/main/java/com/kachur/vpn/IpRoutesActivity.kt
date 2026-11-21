package com.kachur.vpn

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.io.IOException
import java.util.concurrent.Executors

class IpRoutesActivity : ComponentActivity() {
    private val settings by lazy { SplitTunnelingSettings(applicationContext) }
    private val session by lazy { ViewModelProvider(this)[IpImportSession::class.java] }
    private val vpnObserver: () -> Unit = ::renderVpn
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
                view.setPadding(insets.systemWindowInsetLeft + dp(16), insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight + dp(16), bottom)
                insets
            }
        }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(Button(context).apply {
                setText(R.string.split_back)
                setOnClickListener { finish() }
            })
            addView(TextView(context).apply {
                setText(R.string.ip_title)
                setTextColor(Color.WHITE)
                textSize = 22f
                setPadding(dp(12), 0, 0, 0)
            })
        })
        root.addView(TextView(this).apply { setText(R.string.ip_description) })
        importButton = Button(this).apply {
            id = R.id.ip_import_button
            setText(R.string.ip_import)
            setOnClickListener { openFilePicker() }
        }
        root.addView(importButton)
        feedback = TextView(this).apply {
            id = R.id.ip_import_feedback
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(feedback)
        reconnect = Button(this).apply {
            id = R.id.ip_reconnect_button
            setText(R.string.split_apply_reconnect)
            setOnClickListener {
                applyingRoutes = true
                VpnConnection.reconnectWithSavedExclusions(applicationContext)
                renderVpn()
            }
        }
        root.addView(reconnect)
        vpnStatus = TextView(this).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        root.addView(vpnStatus)
        search = routeField(R.id.ip_search, R.string.ip_search_hint, EditorInfo.IME_ACTION_SEARCH)
        root.addView(search)
        input = routeField(R.id.ip_input, R.string.ip_add_hint, EditorInfo.IME_ACTION_DONE)
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_DONE) false else {
                addRoute()
                true
            }
        }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(context).apply {
                id = R.id.ip_add_button
                setText(R.string.ip_add)
                setOnClickListener { addRoute() }
            })
        })
        empty = TextView(this).apply { gravity = Gravity.CENTER }
        val list = ListView(this).apply {
            id = R.id.ip_list
            adapter = routes
        }
        root.addView(FrameLayout(this).apply {
            addView(list)
            addView(empty)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        list.emptyView = empty
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
        VpnConnection.observe(vpnObserver)
        render()
    }

    override fun onStop() {
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
        savedRoutes = settings.ipRoutes().sorted()
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
        routes.notifyDataSetChanged()
    }

    private fun renderVpn() {
        val needsReconnect = VpnConnection.routingNeedsReconnect(applicationContext)
        reconnect.visibility = if (needsReconnect) View.VISIBLE else View.GONE
        reconnect.isEnabled = !VpnConnection.checkingRoutes
        vpnStatus.visibility = if (needsReconnect || applyingRoutes) View.VISIBLE else View.GONE
        val message = VpnConnection.message
        vpnStatus.text = when {
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
        val label = TextView(context).apply { textSize = 16f }
        val remove = Button(context).apply {
            id = R.id.ip_remove_button
            setText(R.string.ip_remove)
        }

        init {
            gravity = Gravity.CENTER_VERTICAL
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(remove)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

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
