package com.kachur.vpn

import org.amnezia.awg.config.BadConfigException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AwgBackendConfigTest {
    @Test
    fun preservesAmneziaWg2FieldsInOfficialEngineConfig() {
        val config = profile("S3 = 16\nS4 = 32\nH1 = 101-110\nI1 = <b 0x0102>").toBackendConfig()
        val lines = config.toAwgUserspaceString().lineSequence().toSet()
        assertTrue("s3=16" in lines)
        assertTrue("s4=32" in lines)
        assertTrue("h1=101-110" in lines)
        assertTrue("i1=<b 0x0102>" in lines)
        assertEquals(1, config.peers.size)
    }

    @Test
    fun rejectsNewerConfigOptionsInsteadOfDroppingThem() {
        assertThrows(BadConfigException::class.java) {
            profile("DisableCookies = true").toBackendConfig()
        }
    }

    private fun profile(options: String): AwgConfig {
        fun dummy(seed: Byte) = Base64.getEncoder().encodeToString(ByteArray(32) { seed })
        return AwgConfigParser.parse(
            "[Interface]\nPrivateKey = ${dummy(1)}\n$options\n[Peer]\nPublicKey = ${dummy(2)}",
        )
    }
}
