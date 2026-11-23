package com.kachur.vpn

import android.content.Intent
import android.content.pm.PackageManager
import com.kachur.vpn.ConfigImportActivityTest.VpnConsentShadow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestKachurApplication::class, shadows = [VpnConsentShadow::class])
class TvBootReceiverTest {
    private val app get() = RuntimeEnvironment.getApplication() as KachurApplication
    private val receiver = TvBootReceiver()

    @Before
    fun setUp() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        VpnLaunchSettings(app).setEnabled(true)
        VpnConsentShadow.granted = true
    }

    @After
    fun tearDown() {
        VpnConsentShadow.granted = false
        VpnConnection.reset()
    }

    @Test
    fun newDeviceBootIsNotBlockedByPreviousSessionPause() {
        VpnLaunchSettings(app).setPaused(true)
        assertTrue(receiver.shouldConnectAfterBoot(app, Intent.ACTION_BOOT_COMPLETED))
    }

    @Test
    fun explicitAutoConnectOffStillPreventsBootConnection() {
        VpnLaunchSettings(app).setEnabled(false)
        assertFalse(receiver.shouldConnectAfterBoot(app, Intent.ACTION_BOOT_COMPLETED))
    }

    @Test
    fun missingVpnConsentNeverLaunchesConnection() {
        VpnConsentShadow.granted = false
        assertFalse(receiver.shouldConnectAfterBoot(app, Intent.ACTION_BOOT_COMPLETED))
    }

    @Test
    fun phoneAndUnrelatedBroadcastsNeverConnect() {
        assertFalse(receiver.shouldConnectAfterBoot(app, Intent.ACTION_SCREEN_ON))
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, false)
        assertFalse(receiver.shouldConnectAfterBoot(app, Intent.ACTION_BOOT_COMPLETED))
    }
}
