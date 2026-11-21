package com.kachur.vpn

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IpRoutesActivityTest {
    @Test
    fun importsThroughTheSystemPickerAfterRecreationAndPersistsEdits() {
        Robolectric.buildActivity(IpRoutesActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val settings = SplitTunnelingSettings(activity)
            settings.setExcluded("com.example.direct", true)
            val request = launchPicker(activity)
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
            assertTrue(request.intent.hasCategory(Intent.CATEGORY_OPENABLE))
            assertTrue(request.intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))

            controller.recreate()
            val recreated = controller.get()
            val json = """[{"hostname":"192.0.2.8/24","ip":""},{"hostname":"198.51.100.9","ip":""}]"""
            shadowOf(recreated.contentResolver).registerInputStream(document, json.byteInputStream())
            val result = Intent().apply { clipData = ClipData.newRawUri("test", document) }
            assertTrue(recreated.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, result))
            awaitFeedback(recreated, recreated.getString(R.string.ip_import_success, 2))

            input(recreated).setText("203.0.113.17/24")
            controls(recreated).findViewById<Button>(R.id.ip_add_button).performClick()
            val list = recreated.findViewById<ListView>(R.id.ip_list)
            assertEquals(3, list.adapter.count - list.headerViewsCount)
            controls(recreated).findViewById<EditText>(R.id.ip_search).setText("198.51")
            assertEquals(1, list.adapter.count - list.headerViewsCount)
            val row = list.adapter.getView(list.headerViewsCount, null, list)
            row.findViewById<Button>(R.id.ip_remove_button).performClick()
            assertEquals(0, list.adapter.count - list.headerViewsCount)
            assertEquals(setOf("192.0.2.0/24", "203.0.113.0/24"), settings.ipRoutes())
        }
        Robolectric.buildActivity(IpRoutesActivity::class.java).setup().use { reopened ->
            val activity = reopened.get()
            val list = activity.findViewById<ListView>(R.id.ip_list)
            assertEquals(2, list.adapter.count - list.headerViewsCount)
            assertEquals(setOf("com.example.direct"), SplitTunnelingSettings(activity).excludedPackages())
        }
    }

    @Test
    fun invalidFileKeepsExistingRoutesAndCancellationAllowsRetry() {
        Robolectric.buildActivity(IpRoutesActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val settings = SplitTunnelingSettings(activity)
            settings.addIpRoutes(setOf("192.0.2.0/24"))
            val first = launchPicker(activity)
            assertTrue(activity.activityResultRegistry.dispatchResult(first.requestCode, Activity.RESULT_CANCELED, null))
            assertEquals(activity.getString(R.string.ip_import_cancelled), feedback(activity).text.toString())

            val request = launchPicker(activity)
            val content = """{"version":1,"bypass":["203.0.113.0/24","synthetic-sensitive-value"]}"""
            shadowOf(activity.contentResolver).registerInputStream(document, content.byteInputStream())
            assertTrue(activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent().setData(document)))
            awaitFeedback(activity, activity.getString(R.string.ip_import_entry, 2))
            assertFalse(feedback(activity).text.contains("synthetic-sensitive-value"))
            assertEquals(setOf("192.0.2.0/24"), settings.ipRoutes())
            assertTrue(controls(activity).findViewById<Button>(R.id.ip_import_button).isEnabled)
        }
    }

    private fun launchPicker(activity: IpRoutesActivity) = shadowOf(activity).run {
        controls(activity).findViewById<Button>(R.id.ip_import_button).performClick()
        nextStartedActivityForResult
    }

    private fun controls(activity: IpRoutesActivity): View {
        val list = activity.findViewById<ListView>(R.id.ip_list)
        return list.adapter.getView(0, null, list)
    }

    private fun input(activity: IpRoutesActivity) = controls(activity).findViewById<EditText>(R.id.ip_input)
    private fun feedback(activity: IpRoutesActivity) = controls(activity).findViewById<TextView>(R.id.ip_import_feedback)

    private fun awaitFeedback(activity: IpRoutesActivity, expected: String) {
        val main = shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            main.idle()
            if (feedback(activity).text.toString() == expected) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        assertEquals(expected, feedback(activity).text.toString())
    }

    private companion object {
        val document = Uri.parse("content://com.kachur.vpn.test.ip/document.json")
    }
}
