package com.kachur.vpn

import org.amnezia.awg.config.BadConfigException
import org.amnezia.awg.config.BadConfigException.Reason
import org.amnezia.awg.config.Config as EngineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AwgBackendConfigTest {
    @Test
    fun forwardsAllAwg2OptionsAndNormalizesHeaderRangesForGo() {
        val config = profile(
            """
                Address = 192.0.2.2/32
                Address = 2001:db8::2/128
                DNS = 192.0.2.53, example.test
                ListenPort = 51820
                MTU = 1280
                ExcludedApplications = com.example.one, com.example.two
                Jc = 4
                Jmin = 40
                Jmax = 70
                S1 = 20
                S2 = 25
                S3 = 16
                S4 = 32
                H1 = 101 - 110
                H2 = 201
                H3 = 301 - 310
                H4 = 4294967294 - 4294967295
                I1 = <b 0x0102>
                I2 = <r 4>
                I3 = <rd 6>
                I4 = <rc 9>
                I5 = <t>
            """.trimIndent(),
            """
                PresharedKey = ${dummyKey(3)}
                Endpoint = 192.0.2.53:51820
                AllowedIPs = 0.0.0.0/0
                AllowedIPs = ::/0
                PersistentKeepalive = 25
            """.trimIndent(),
        ).toBackendConfig()

        val lines = config.toAwgUserspaceString().lineSequence().toSet()
        listOf(
            "listen_port=51820", "jc=4", "jmin=40", "jmax=70", "s1=20", "s2=25", "s3=16", "s4=32",
            "h1=101-110", "h2=201", "h3=301-310", "h4=4294967294-4294967295",
            "i1=<b 0x0102>", "i2=<r 4>", "i3=<rd 6>", "i4=<rc 9>", "i5=<t>",
            "persistent_keepalive_interval=25",
        ).forEach { assertTrue(it in lines) }
        val device = config.getInterface()
        assertEquals(2, device.addresses.size)
        assertEquals(1, device.dnsServers.size)
        assertEquals(1, device.dnsSearchDomains.size)
        assertEquals(1280, device.mtu.orElseThrow().toInt())
        assertEquals(2, device.excludedApplications.size)
        val peer = config.peers.single()
        assertEquals(2, peer.allowedIps.size)
        assertTrue(peer.endpoint.isPresent)
        assertTrue(peer.preSharedKey.isPresent)
    }

    @Test
    fun preservesScalarAndRangeKeepaliveForTheGoEngine() {
        val values = mapOf(
            "25" to "25",
            "0" to "0",
            "1" to "1",
            "65535" to "65535",
            "65536" to "65536",
            "4294967295" to "4294967295",
            "22-35" to "22-35",
            "22 \t- 35" to "22-35",
            "25 - 25" to "25-25",
            "4294967294 - 4294967295" to "4294967294-4294967295",
        )
        for ((input, expected) in values) {
            val imported = profile(peerOptions = "peRsiStentKeePalive =  $input  # exported setting")
            assertEquals(input, imported.peers.single()["PersistentKeepalive"])
            val config = imported.toBackendConfig()
            assertEquals(expected, config.peers.single().persistentKeepalive.orElseThrow())
            assertTrue(config.toAwgUserspaceString().lineSequence().any { it == "persistent_keepalive_interval=$expected" })
        }
    }

    @Test
    fun sendsExplicitZeroForAllDisabledKeepaliveSpellings() {
        for (disabled in listOf("", "off", "(OFF)", "0")) {
            val config = profile(
                "IncludedApplications = com.example.one\nMTU = 0\nI5 =",
                "PersistentKeepalive = $disabled",
            ).toBackendConfig()

            assertEquals("0", config.peers.single().persistentKeepalive.orElseThrow())
            assertTrue(config.toAwgUserspaceString().lineSequence().any { it == "persistent_keepalive_interval=0" })
            assertEquals(1, config.getInterface().includedApplications.size)
            assertFalse(config.getInterface().mtu.isPresent)
            assertFalse(config.getInterface().specialJunkI5.isPresent)
        }
    }

    @Test
    fun rejectsInvalidKeepaliveBeforeCallingTheEngine() {
        for (invalid in listOf("-1", "+25", "25.5", "22-", "-35", "35-22", "22-35-60", "4294967296", "25-4294967296", "on")) {
            val error = assertThrows(ConfigFormatException::class.java) {
                profile(peerOptions = "PersistentKeepalive = $invalid")
            }
            assertFalse(error.unsupportedOption)
            assertEquals(6, error.line)
        }
    }

    @Test
    fun forwardsSupportedTimingOptionsAndRejectsUnsupportedPeerOptions() {
        val config = profile("RekeyTimeout = 5-8\nDisableCookies = true").toBackendConfig()
        val lines = config.toAwgUserspaceString().lineSequence().toSet()
        assertTrue("rekey_timeout=5-8" in lines)
        assertTrue("disable_cookies=1" in lines)

        assertBackendParserError(
            profile(peerOptions = "AdvancedSecurity = true"), Reason.UNKNOWN_ATTRIBUTE, AwgParameter.ADVANCED_SECURITY,
            R.string.vpn_config_error_unknown_parameter,
        )
    }

    @Test
    fun rejectsUnrepresentableJunkCountWithFixedParameterName() {
        assertBackendParserError(
            profile("Jc = 4294967295"), Reason.INVALID_NUMBER, AwgParameter.JC,
            R.string.vpn_config_error_invalid_number,
        )
    }

    @Test
    fun neverDisplaysUnknownNamesRawValuesOrExceptionCauses() {
        val unknown = "synthetic_unknown_option"
        val endpoint = "synthetic-endpoint.123:51820"
        val invalidKey = "synthetic-invalid-key"
        val inputs = listOf(
            Triple(
                "[Interface]\nPrivateKey = ${dummyKey(1)}\n$unknown = ${dummyKey(2)}",
                unknown, VpnError(R.string.vpn_config_error_unknown_parameter),
            ),
            Triple(
                "[Interface]\nPrivateKey = ${dummyKey(1)}\n[Peer]\nPublicKey = ${dummyKey(2)}\nEndpoint = $endpoint",
                endpoint, VpnError(R.string.vpn_config_error_invalid_value, AwgParameter.ENDPOINT),
            ),
            Triple(
                "[Interface]\nPrivateKey = $invalidKey",
                invalidKey, VpnError(R.string.vpn_config_error_invalid_key, AwgParameter.PRIVATE_KEY),
            ),
        )
        val context = RuntimeEnvironment.getApplication()
        for ((input, secret, expectedError) in inputs) {
            val error = assertThrows(BadConfigException::class.java) {
                input.reader().buffered().use(EngineConfig::parse)
            }.toSafeVpnError()
            assertEquals(expectedError, error)
            assertFalse(error.display(context).contains(secret))
            assertFalse(error.display(context).contains(dummyKey(1)))
            assertFalse(error.display(context).contains(dummyKey(2)))
        }
    }

    @Test
    fun importReportsUnknownOptionsRatherThanDroppingThem() {
        val error = assertThrows(ConfigFormatException::class.java) {
            profile("UnrecognizedOption = 1")
        }
        assertTrue(error.unsupportedOption)
    }

    @Test
    fun appExclusionsReachTheGoBackendConfigAndPreserveImportedRules() {
        val imported = profile(
            "ExcludedApplications = com.example.fromprofile, com.example.overlap",
            "PersistentKeepalive = 22 - 35",
        )
        val applied = imported.toBackendConfig(setOf("com.example.overlap", "com.example.direct"))
        assertEquals(
            setOf("com.example.fromprofile", "com.example.overlap", "com.example.direct"),
            applied.getInterface().excludedApplications,
        )
        assertTrue(applied.getInterface().includedApplications.isEmpty())
        assertEquals("22-35", applied.peers.single().persistentKeepalive.orElseThrow())
        assertEquals(
            setOf("com.example.fromprofile", "com.example.overlap"),
            imported.toBackendConfig().getInterface().excludedApplications,
        )
        assertEquals(
            setOf("com.example.direct"),
            profile().toBackendConfig(setOf("com.example.direct")).getInterface().excludedApplications,
        )
    }

    @Test
    fun appExclusionsNarrowAnAllowlistWithoutOpeningTheTunnelToOtherApps() {
        val imported = profile("IncludedApplications = com.example.first, com.example.second")
        val applied = imported.toBackendConfig(setOf("com.example.first", "com.example.outside"))
        assertEquals(setOf("com.example.second"), applied.getInterface().includedApplications)
        assertTrue(applied.getInterface().excludedApplications.isEmpty())
        assertThrows(NoIncludedVpnAppsException::class.java) {
            imported.toBackendConfig(setOf("com.example.first", "com.example.second"))
        }
    }

    private fun assertBackendParserError(
        profile: AwgConfig,
        reason: Reason,
        parameter: AwgParameter,
        category: Int,
    ) {
        val error = assertThrows(BadConfigException::class.java) { profile.toBackendConfig() }
        assertEquals(reason, error.reason)
        assertEquals(VpnError(category, parameter), error.toSafeVpnError())
    }

    private fun profile(interfaceOptions: String = "", peerOptions: String = ""): AwgConfig =
        AwgConfigParser.parse(
            "[Interface]\nPrivateKey = ${dummyKey(1)}\n$interfaceOptions\n[Peer]\nPublicKey = ${dummyKey(2)}\n$peerOptions",
        )

    private fun dummyKey(seed: Byte): String = Base64.getEncoder().encodeToString(ByteArray(32) { seed })
}
