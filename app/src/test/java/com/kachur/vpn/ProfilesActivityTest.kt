package com.kachur.vpn

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowPopupMenu
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestKachurApplication::class)
class ProfilesActivityTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun tapConnectSelectsTheProfileAndReturnsOneRequest() {
        val original = profile("Original", "a")
        val next = profile("Next", "b")
        context.localProfiles().append(listOf(original, next))
        Robolectric.buildActivity(ProfilesActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val list = activity.findViewById<LinearLayout>(R.id.profiles_list)
            await { list.childCount == 2 }
            list.getChildAt(1).findViewById<Button>(R.id.profiles_row_connect).performClick()
            await { activity.isFinishing }
            val result = shadowOf(activity)
            assertEquals(Activity.RESULT_OK, result.resultCode)
            assertEquals(next.documentKey, result.resultIntent.getStringExtra(ProfilesActivity.PROFILE_ID))
            assertEquals(next.documentKey, context.localProfiles().load()?.documentKey)
        }
    }

    @Test
    fun confirmedRemovalOfAnActiveProfileWaitsForTunnelCleanup() {
        val original = profile("Original", "a")
        val next = profile("Next", "b")
        context.localProfiles().append(listOf(original, next))
        Robolectric.buildActivity(ProfilesActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val list = activity.findViewById<LinearLayout>(R.id.profiles_list)
            await { list.childCount == 2 }
            VpnConnection.start(context, original.config, profileId = original.documentKey, profileName = original.name)
            try {
                await { VpnConnection.serviceLaunch == R.string.vpn_service_launch_accepted }
                val id = shadowOf(activity).nextStartedService.getLongExtra(VpnConnection.EXTRA_ATTEMPT, 0L)
                var stops = 0
                checkNotNull(VpnConnection.claim(id) { stops++ })
                VpnConnection.tunnelReady(id)
                VpnConnection.handshake(id)
                fun openDelete(): AlertDialog {
                    list.getChildAt(0).findViewById<Button>(R.id.profiles_row_actions).performClick()
                    val popup = checkNotNull(ShadowPopupMenu.getLatestPopupMenu())
                    val menu = Shadow.extract<ShadowPopupMenu>(popup)
                    menu.onMenuItemClickListener.onMenuItemClick(popup.menu.getItem(popup.menu.size() - 1))
                    popup.dismiss()
                    return ShadowAlertDialog.getLatestAlertDialog()
                }
                openDelete().getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(0, stops)

                openDelete().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
                await { VpnConnection.phase == VpnPhase.STOPPING }
                assertEquals(1, stops)
                assertTrue(context.localProfiles().read().catalog.entries.any { it.documentKey == original.documentKey })
                VpnConnection.serviceClosed(id, false)
                await { list.childCount == 1 }
                assertFalse(context.localProfiles().read().catalog.entries.any { it.documentKey == original.documentKey })
                assertEquals(next.documentKey, context.localProfiles().load()?.documentKey)
            } finally {
                VpnConnection.reset()
            }
        }
    }

    private fun profile(name: String, id: String): ImportedProfile {
        fun key(value: Byte) = Base64.getEncoder().encodeToString(ByteArray(32) { value })
        val config = AwgConfigParser.parse("[Interface]\nPrivateKey=${key(1)}\n[Peer]\nPublicKey=${key(2)}")
        return ImportedProfile(name, config, id.repeat(64))
    }

    private fun await(ready: () -> Boolean) {
        val main = shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + 5_000_000_000L
        do {
            main.idle()
            if (ready()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        assertTrue(ready())
    }
}
