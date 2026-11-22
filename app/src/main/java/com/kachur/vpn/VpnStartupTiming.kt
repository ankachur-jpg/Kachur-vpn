package com.kachur.vpn

import android.content.Context
import android.os.SystemClock
import java.util.UUID

internal enum class VpnStartSource(val label: Int) {
    APP(R.string.vpn_timing_app),
    TILE(R.string.vpn_timing_tile),
    NOTIFICATION(R.string.vpn_timing_notification),
}

internal enum class VpnStartStage(val label: Int) {
    WINDOW_REQUESTED(R.string.vpn_timing_window_requested),
    WINDOW_RESUMED(R.string.vpn_timing_window_resumed),
    PROFILE_LOOKUP(R.string.vpn_timing_profile_lookup),
    PROFILE_CACHED(R.string.vpn_timing_profile_cached),
    PROFILE_READ(R.string.vpn_timing_profile_read),
    PERMISSION_CHECK(R.string.vpn_timing_permission_check),
    PERMISSION_CHECKED(R.string.vpn_timing_permission_checked),
    CONSENT_DIALOG(R.string.vpn_timing_consent_dialog),
    CONSENT_RESULT(R.string.vpn_timing_consent_result),
    ROUTES_READY(R.string.vpn_timing_routes_ready),
    SERVICE_REQUESTED(R.string.vpn_timing_service_requested),
    SERVICE_CALL(R.string.vpn_timing_service_call),
    SERVICE_ACCEPTED(R.string.vpn_timing_service_accepted),
    SERVICE_RESTRICTED(R.string.vpn_timing_service_restricted),
    SERVICE_ENTERED(R.string.vpn_timing_service_entered),
    FOREGROUND_READY(R.string.vpn_timing_foreground_ready),
    CONFIG_CHECK(R.string.vpn_timing_config_check),
    CONFIG_READY(R.string.vpn_timing_config_ready),
    ENGINE_READY(R.string.vpn_timing_engine_ready),
    TUNNEL_UP(R.string.vpn_timing_tunnel_up),
    HANDSHAKE_REQUESTED(R.string.vpn_timing_handshake_requested),
    FIRST_SAMPLE(R.string.vpn_timing_first_sample),
    HANDSHAKE_CONFIRMED(R.string.vpn_timing_handshake_confirmed),
    ENDED(R.string.vpn_timing_ended),
}

/** Elapsed times only. It is also written by the binder and backend workers. */
internal class VpnStartupTrace(
    val source: VpnStartSource,
    private val startedAt: Long,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    val id: String = UUID.randomUUID().toString()
    private val events = mutableListOf<Event>()
    private var finished = false

    data class Event(val stage: VpnStartStage, val elapsedMs: Long)

    @Synchronized
    fun mark(stage: VpnStartStage) {
        if (finished) return
        if (events.size >= MAX_EVENTS) events.removeAt(0)
        events.add(Event(stage, (clock() - startedAt).coerceAtLeast(0)))
        finished = stage == VpnStartStage.HANDSHAKE_CONFIRMED || stage == VpnStartStage.ENDED
    }

    @Synchronized
    fun snapshot(): List<Event> = events.toList()

    private companion object {
        const val MAX_EVENTS = 40
    }
}

/** Main-thread index of the latest attempt from each entry point. */
internal object VpnStartupTiming {
    private val latest = linkedMapOf<VpnStartSource, VpnStartupTrace>()

    fun begin(source: VpnStartSource, startedAt: Long = SystemClock.elapsedRealtime()): VpnStartupTrace =
        VpnStartupTrace(source, startedAt).also { latest[source] = it }

    fun find(id: String?): VpnStartupTrace? = latest.values.firstOrNull { it.id == id }

    fun last(source: VpnStartSource): VpnStartupTrace? = latest[source]

    fun display(context: Context): String {
        if (latest.isEmpty()) return ""
        return buildString {
            append(context.getString(R.string.vpn_timing_title))
            for (trace in latest.values) {
                append("\n\n")
                append(context.getString(trace.source.label))
                for (event in trace.snapshot()) {
                    append('\n')
                    append(context.getString(R.string.vpn_timing_row, event.elapsedMs, context.getString(event.stage.label)))
                }
            }
        }
    }
}
