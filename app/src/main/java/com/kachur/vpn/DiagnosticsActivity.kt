package com.kachur.vpn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class DiagnosticsActivity : ComponentActivity() {
    private val session by lazy { ViewModelProvider(this)[DiagnosticsSession::class.java] }
    private val visibility by lazy { (application as KachurApplication).visibility }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var run: Button
    private lateinit var feedback: TextView
    private lateinit var measurements: LinearLayout
    private lateinit var chart: PingChart
    private var resumed = false
    private var automatic = false
    private val measurementObserver: () -> Unit = ::updateMeasurementSchedule
    private val tick = object : Runnable {
        override fun run() {
            if (!canMeasure() || VpnConnection.phase != VpnPhase.HANDSHAKE) {
                updateMeasurementSchedule()
                return
            }
            measure()
            main.postDelayed(this, AUTOMATIC_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(20))
        }
        content.addView(Button(this).apply {
            setText(R.string.split_back)
            setOnClickListener { finish() }
        })
        content.addView(TextView(this).apply { setText(R.string.diag_title); textSize = 24f })
        content.addView(TextView(this).apply { setText(R.string.diag_explanation) })
        run = Button(this).apply {
            id = R.id.diag_run
            setText(R.string.diag_run)
            setOnClickListener { measure() }
        }
        content.addView(run)
        feedback = TextView(this).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addView(feedback)
        measurements = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(measurements)
        content.addView(TextView(this).apply {
            setPadding(0, dp(12), 0, 0)
            setText(R.string.diag_history)
        })
        chart = PingChart(this)
        content.addView(chart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(120)))
        setContentView(ScrollView(this).apply {
            addView(content)
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        })
        render()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        session.onChange = ::render
        VpnConnection.observe(measurementObserver)
        visibility.observe(measurementObserver)
        render()
    }

    override fun onPause() {
        resumed = false
        visibility.removeObserver(measurementObserver)
        VpnConnection.removeObserver(measurementObserver)
        session.onChange = null
        updateMeasurementSchedule()
        super.onPause()
    }

    private fun canMeasure(): Boolean = resumed && visibility.screenInteractive

    private fun updateMeasurementSchedule() {
        val open = canMeasure()
        if (!open && session.running) {
            session.cancel()
            render()
        }
        run.isEnabled = open && !session.running
        val enabled = open && VpnConnection.phase == VpnPhase.HANDSHAKE
        if (automatic == enabled) return
        automatic = enabled
        if (enabled) main.postDelayed(tick, AUTOMATIC_INTERVAL_MS) else main.removeCallbacks(tick)
    }

    private fun measure() {
        if (canMeasure()) session.start(applicationContext, VpnConnection.diagnosticSession())
    }

    private fun render() {
        run.isEnabled = canMeasure() && !session.running
        feedback.setText(session.status)
        measurements.removeAllViews()
        for (result in session.history.lastOrNull().orEmpty()) {
            measurements.addView(TextView(this).apply {
                textSize = 16f
                setPadding(0, dp(12), 0, 0)
                setText(result.target.label)
            })
            val icmp = result.icmp
            val tcp = result.tcp
            if (icmp == null) detail(R.string.diag_path_unavailable)
            else if (icmp.samples == 0) detail(R.string.diag_icmp_unavailable)
            else detail(getString(R.string.diag_icmp_stats, time(icmp.average), time(icmp.jitter),
                icmp.failedPercent ?: 0, icmp.replies, icmp.samples))
            if (tcp != null && tcp.samples == 0) detail(R.string.diag_tcp_unavailable)
            else if (tcp != null) detail(getString(R.string.diag_tcp_stats, time(tcp.average), time(tcp.jitter),
                tcp.failedPercent ?: 0, tcp.replies, tcp.samples))
        }
        chart.update(session.history)
    }

    private fun time(value: Double?): String = value?.let { getString(R.string.diag_millis, it) } ?: "—"

    private fun detail(label: Int) = detail(getString(label))
    private fun detail(value: String) { measurements.addView(TextView(this).apply { text = value }) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val AUTOMATIC_INTERVAL_MS = 30_000L
    }
}

internal class DiagnosticsSession : ViewModel() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var task: Future<*>? = null
    private var cancelling: AtomicBoolean? = null
    val running: Boolean get() = cancelling != null
    var onChange: (() -> Unit)? = null
    var status = R.string.diag_idle
        private set
    var history = emptyList<List<DiagnosticResult>>()
        private set

    fun start(context: Context, session: DiagnosticSession?) {
        if (running) return
        val token = AtomicBoolean(false)
        cancelling = token
        status = R.string.diag_running
        onChange?.invoke()
        task = worker.submit {
            if (token.get()) return@submit
            val result = try {
                val profile = if (session == null) context.localProfiles().load()?.config else null
                NetworkDiagnostics(context).measure(session, profile, token::get)
            } catch (_: Exception) { null }
            main.post {
                if (cancelling !== token) return@post
                cancelling = null
                task = null
                if (result == null) status = R.string.diag_failed else {
                    history = (history + listOf(result)).takeLast(20)
                    status = R.string.diag_finished
                }
                onChange?.invoke()
            }
        }
    }

    fun cancel() {
        cancelling?.set(true)
        cancelling = null
        task?.cancel(true)
        task = null
        status = if (history.isEmpty()) R.string.diag_idle else R.string.diag_finished
    }

    override fun onCleared() {
        cancel()
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
    }
}

private class PingChart(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f; textSize = 26f }
    private var first = emptyList<Double?>()
    private var second = emptyList<Double?>()

    fun update(history: List<List<DiagnosticResult>>) {
        fun samples(target: DiagnosticTarget) = history.map { batch -> batch.firstOrNull { it.target == target }?.icmp?.average }
        first = samples(DiagnosticTarget.FIRST_DIRECT)
        second = samples(DiagnosticTarget.FIRST_VPN)
        contentDescription = context.getString(R.string.diag_history)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val limit = (first + second).filterNotNull().maxOrNull()
        paint.color = Color.DKGRAY
        if (limit == null) {
            canvas.drawText(context.getString(R.string.diag_history_empty), 8f, 34f, paint)
            return
        }
        val top = 44f
        val bottom = height - 12f
        canvas.drawLine(8f, bottom, width - 8f, bottom, paint)
        canvas.drawText(context.getString(R.string.diag_millis, limit), 8f, 28f, paint)
        drawSeries(canvas, first, Color.rgb(0, 101, 125), top, bottom, limit)
        drawSeries(canvas, second, Color.rgb(129, 56, 131), top, bottom, limit)
    }

    private fun drawSeries(canvas: Canvas, data: List<Double?>, color: Int, top: Float, bottom: Float, limit: Double) {
        paint.color = color
        var previous: Pair<Float, Float>? = null
        data.forEachIndexed { index, value ->
            if (value == null) {
                previous = null
                return@forEachIndexed
            }
            val x = 8f + (width - 16f) * index / (data.size - 1).coerceAtLeast(1)
            val y = bottom - ((bottom - top) * value / limit.coerceAtLeast(1.0)).toFloat()
            previous?.let { (a, b) -> canvas.drawLine(a, b, x, y, paint) }
            canvas.drawCircle(x, y, 4f, paint)
            previous = x to y
        }
    }
}
