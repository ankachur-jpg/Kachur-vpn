package com.kachur.vpn

import android.content.Intent
import android.net.ConnectivityManager
import android.os.Looper
import android.os.PowerManager
import android.widget.Button
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestKachurApplication::class)
class DiagnosticsActivityTest {
    @Test
    fun measuresOnlyWhenTheDiagnosticsScreenIsResumedAndInteractive() {
        val application = RuntimeEnvironment.getApplication()
        val power = shadowOf(application.getSystemService(PowerManager::class.java))
        val connectivity = shadowOf(application.getSystemService(ConnectivityManager::class.java))
        connectivity.setActiveNetworkInfo(null)
        connectivity.clearAllNetworks()
        power.setIsInteractive(true)
        val main = shadowOf(Looper.getMainLooper())
        try {
            VpnConnection.reset()
            Robolectric.buildActivity(DiagnosticsActivity::class.java).setup().use { controller ->
                val activity = controller.get()
                val run = activity.findViewById<Button>(R.id.diag_run)
                val session = ViewModelProvider(activity)[DiagnosticsSession::class.java]
                main.idleFor(Duration.ofMinutes(2))
                assertEquals(R.string.diag_idle, session.status)
                assertTrue(run.isEnabled)

                VpnConnection.publish(VpnPhase.HANDSHAKE, R.string.vpn_handshake_received)
                power.setIsInteractive(false)
                application.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
                main.idle()
                assertFalse(run.isEnabled)
                run.performClick()
                main.idleFor(Duration.ofMinutes(2))
                assertEquals(R.string.diag_idle, session.status)

                power.setIsInteractive(true)
                application.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
                main.idle()
                assertTrue(run.isEnabled)
                controller.pause()
                run.performClick()
                main.idleFor(Duration.ofMinutes(2))
                assertEquals(R.string.diag_idle, session.status)

                controller.resume().postResume()
                main.idleFor(Duration.ofMillis(29_999))
                assertEquals(R.string.diag_idle, session.status)
                main.idleFor(Duration.ofMillis(1))
                assertNotEquals(R.string.diag_idle, session.status)
                controller.pause()
                assertFalse(session.running)
            }
        } finally {
            VpnConnection.reset()
        }
    }
}
