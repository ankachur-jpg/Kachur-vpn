package com.kachur.vpn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class DiagnosticsActivity : KachurActivity() {
    private val session by lazy { ViewModelProvider(this)[DiagnosticsSession::class.java] }
    private val visibility by lazy { (application as KachurApplication).visibility }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var run: Button
    private lateinit var feedback: TextView
    private lateinit var measurements: LinearLayout
    private lateinit var chart: PingChart
    private lateinit var chartEmpty: TextView
    private lateinit var chartLegend: TextView
    private var renderedHistory: List<List<DiagnosticResult>>? = null
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
        val content = uiColumn(18)
        content.uiAdd(uiHeader(R.string.diag_title) { finish() })
        content.uiAdd(uiCard().apply {
            uiAdd(uiCaption(R.string.diag_explanation))
            run = uiButton(R.string.diag_run, primary = true, icon = R.drawable.ic_ui_chart).apply {
                id = R.id.diag_run
                setOnClickListener { measure() }
            }
            uiAdd(run, 12)
            feedback = uiLabel(15, R.color.ui_muted).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            uiAdd(feedback, 10)
        }, 16)
        content.uiAdd(uiCard().apply {
            uiAdd(uiHeading(R.string.diag_history, 20))
            chartEmpty = uiCaption(R.string.diag_history_empty)
            uiAdd(chartEmpty, 8)
            chartLegend = uiLabel(14, R.color.ui_muted)
            uiAdd(chartLegend, 8)
            chart = PingChart(this@DiagnosticsActivity)
            addView(chart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(118)).apply {
                topMargin = dp(8)
            })
        }, 14)
        measurements = uiColumn()
        content.uiAdd(measurements)
        setContentView(uiScroll(content).apply { uiInsets() })
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
        if (renderedHistory === session.history) return
        renderedHistory = session.history
        measurements.removeAllViews()
        for (result in session.history.lastOrNull().orEmpty()) {
            measurements.uiAdd(uiCard().apply {
                uiAdd(uiHeading(result.target.label, 18))
                val icmp = result.icmp
                val tcp = result.tcp
                val first = when {
                    icmp == null -> getString(R.string.diag_path_unavailable)
                    icmp.samples == 0 -> getString(R.string.diag_icmp_unavailable)
                    else -> getString(R.string.diag_icmp_stats, time(icmp.average), time(icmp.jitter),
                        icmp.failedPercent ?: 0, icmp.replies, icmp.samples)
                }
                uiAdd(uiLabel(15, R.color.ui_muted).apply { text = first }, 6)
                if (tcp != null) uiAdd(uiLabel(15, R.color.ui_chart_second).apply {
                    text = if (tcp.samples == 0) getString(R.string.diag_tcp_unavailable)
                        else getString(R.string.diag_tcp_stats, time(tcp.average), time(tcp.jitter),
                            tcp.failedPercent ?: 0, tcp.replies, tcp.samples)
                }, 6)
            }, 12)
        }
        val maximum = chart.update(session.history)
        val available = maximum != null
        chartLegend.visibility = if (available) View.VISIBLE else View.GONE
        if (maximum != null) chartLegend.text = getString(R.string.ui_ping_legend, time(maximum))
        chart.visibility = if (available) View.VISIBLE else View.GONE
        chartEmpty.visibility = if (available) View.GONE else View.VISIBLE
    }

    private fun time(value: Double?): String = value?.let { getString(R.string.diag_millis, it) } ?: "—"

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
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = context.dp(2).toFloat() }
    private var first = emptyList<Double?>()
    private var second = emptyList<Double?>()

    fun update(history: List<List<DiagnosticResult>>): Double? {
        fun samples(target: DiagnosticTarget) = history.map { batch -> batch.firstOrNull { it.target == target }?.icmp?.average }
        first = samples(DiagnosticTarget.FIRST_DIRECT)
        second = samples(DiagnosticTarget.FIRST_VPN)
        contentDescription = context.getString(R.string.diag_history)
        invalidate()
        return (first + second).filterNotNull().maxOrNull()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val limit = (first + second).filterNotNull().maxOrNull() ?: return
        val inset = context.dp(8).toFloat()
        val bottom = height - inset
        paint.color = context.getColor(R.color.ui_border)
        canvas.drawLine(inset, bottom, width - inset, bottom, paint)
        drawSeries(canvas, first, context.getColor(R.color.ui_accent), inset, bottom, limit)
        drawSeries(canvas, second, context.getColor(R.color.ui_chart_second), inset, bottom, limit)
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
