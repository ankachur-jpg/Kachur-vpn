package com.kachur.vpn

import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

class IpSourcesActivity : KachurActivity() {
    private val settings by lazy { IpUpdateSettings(applicationContext) }
    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "ip_update_attempt" || key == "ip_update_result") renderStatus()
    }
    private lateinit var sourceRows: LinearLayout
    private lateinit var registry: CheckBox
    private lateinit var frequency: Button
    private lateinit var update: Button
    private lateinit var add: Button
    private lateinit var status: TextView
    private val timeFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    private var rendering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = uiColumn(18)
        content.uiAdd(uiHeader(R.string.ip_update_title) { finish() })
        content.uiAdd(uiCard().apply {
            registry = CheckBox(this@IpSourcesActivity).apply {
                id = R.id.ip_update_registry
                minHeight = dp(48)
                setText(R.string.ip_update_registry)
                setOnCheckedChangeListener { _, checked ->
                    if (!rendering) with(settings.configuration()) { save(checked, custom, schedule) }
                }
            }
            uiAdd(registry)
            uiAdd(uiCaption(R.string.ip_update_registry_description), 6)
            frequency = uiButton(0).apply {
                id = R.id.ip_update_schedule
                setOnClickListener { chooseSchedule() }
            }
            uiAdd(frequency, 10)
        }, 16)
        sourceRows = uiColumn()
        content.uiAdd(sourceRows)
        content.uiAdd(uiCard().apply {
            add = uiButton(R.string.ip_update_add).apply {
                id = R.id.ip_update_add
                setOnClickListener { addSource() }
            }
            update = uiButton(R.string.ip_update_now, primary = true).apply {
                id = R.id.ip_update_now
                setOnClickListener {
                    status.setText(if (IpUpdateJobs.updateNow(applicationContext)) R.string.ip_update_queued else R.string.ip_update_schedule_failed)
                }
            }
            uiAdd(add)
            uiAdd(update, 8)
            status = uiLabel(15).apply {
                id = R.id.ip_update_status
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            uiAdd(status, 10)
            uiAdd(uiCaption(R.string.ip_update_apply_note), 8)
        }, 14)
        setContentView(uiScroll(content).apply { uiInsets() })
    }

    override fun onStart() {
        super.onStart()
        settings.preferences.registerOnSharedPreferenceChangeListener(preferencesListener)
        render()
        if (!IpUpdateJobs.schedule(applicationContext, settings.configuration())) status.setText(R.string.ip_update_schedule_failed)
    }

    override fun onStop() {
        settings.preferences.unregisterOnSharedPreferenceChangeListener(preferencesListener)
        super.onStop()
    }

    private fun chooseSchedule() {
        val config = settings.configuration()
        val choices = IpUpdateSchedule.entries
        AlertDialog.Builder(this).setTitle(R.string.ip_update_schedule_title)
            .setSingleChoiceItems(choices.map { getString(it.label) }.toTypedArray(), config.schedule.ordinal) { dialog, index ->
                save(config.useRegistry, config.custom, choices[index])
                dialog.dismiss()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun addSource() {
        val input = EditText(this).apply {
            uiField()
            setSingleLine(true)
            setHint(R.string.ip_update_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.ip_update_add).setView(input)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.ip_add, null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val source = IpUpdateSource.custom(input.text.toString())
            val config = settings.configuration()
            when {
                source == null -> input.error = getString(R.string.ip_update_url_invalid)
                source in config.custom -> input.error = getString(R.string.ip_update_url_duplicate)
                config.custom.size >= MAX_CUSTOM -> input.error = getString(R.string.ip_update_url_limit, MAX_CUSTOM)
                else -> {
                    save(config.useRegistry, config.custom + source, config.schedule)
                    dialog.dismiss()
                }
            }
        }
    }

    private fun save(enabled: Boolean, custom: List<IpUpdateSource>, schedule: IpUpdateSchedule) {
        settings.change(enabled, custom, schedule)
        render()
        if (!IpUpdateJobs.schedule(applicationContext, settings.configuration())) status.setText(R.string.ip_update_schedule_failed)
    }

    private fun render() {
        rendering = true
        val config = settings.configuration()
        registry.isChecked = config.useRegistry
        frequency.text = getString(R.string.ip_update_schedule_value, getString(config.schedule.label))
        update.isEnabled = config.sources.isNotEmpty()
        add.isEnabled = config.custom.size < MAX_CUSTOM
        rendering = false
        renderStatus()
    }

    private fun renderStatus() {
        val config = settings.configuration()
        sourceRows.removeAllViews()
        for (source in config.sources) {
            val row = uiCard(12)
            val time = settings.timeFor(source)
            val description = if (time == 0L) getString(R.string.ip_update_source_pending, source.title(this)) else
                getString(R.string.ip_update_source_status, source.title(this), timeFormat.format(Date(time)), settings.routesFor(source).size)
            row.uiAdd(uiLabel().apply { text = description })
            if (source.url != null) row.uiAdd(uiButton(R.string.ip_remove).apply {
                contentDescription = getString(R.string.ip_update_remove_source, source.title(this@IpSourcesActivity))
                setOnClickListener {
                    val current = settings.configuration()
                    save(current.useRegistry, current.custom - source, current.schedule)
                }
            }, 8)
            sourceRows.uiAdd(row, 12)
        }
        val result = settings.lastResult()
        if (config.sources.isEmpty()) {
            status.setText(R.string.ip_update_no_sources)
        } else if (result == null) {
            status.setText(R.string.ip_update_no_attempt)
        } else {
            val source = config.sources.firstOrNull { it.key == settings.failedSource() }?.title(this)
            val report = listOfNotNull(
                timeFormat.format(Date(settings.lastAttempt())), getString(result.text), source,
            )
            status.text = report.joinToString(" · ")
        }
    }

    private companion object {
        const val MAX_CUSTOM = 8
    }
}
