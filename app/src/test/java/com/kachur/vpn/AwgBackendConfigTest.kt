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
    fun acceptsDisabledKeepaliveAndOptionalDefaults() {
        for (disabled in listOf("off", "(OFF)", "0")) {
            val config = profile(
                "IncludedApplications = com.example.one\nMTU = 0\nI5 =",
                "PersistentKeepalive = $disabled",
            ).toBackendConfig()

            assertFalse(config.peers.single().persistentKeepalive.isPresent)
            assertEquals(1, config.getInterface().includedApplications.size)
            assertFalse(config.getInterface().mtu.isPresent)
            assertFalse(config.getInterface().specialJunkI5.isPresent)
        }
    }

    @Test
    fun identifiesImportedOptionsThatThePinnedEngineDoesNotSupport() {
        val options = mapOf(
            AwgParameter.HEADER_PROTECTION_KEY to "HeaderProtectionKey = ${dummyKey(3)}\nS1 = 12\nS2 = 12\nS3 = 12\nS4 = 12",
            AwgParameter.REKEY_AFTER_TIME to "RekeyAfterTime = 60",
            AwgParameter.REKEY_TIMEOUT to "RekeyTimeout = 5",
            AwgParameter.REJECT_AFTER_TIME to "RejectAfterTime = 120",
            AwgParameter.KEEPALIVE_TIMEOUT to "KeepaliveTimeout = 5",
            AwgParameter.MAX_HANDSHAKE_ATTEMPTS to "MaxHandshakeAttempts = 10",
            AwgParameter.CONTENT_PADDING_ADDITION to "ContentPaddingAddition = 15",
            AwgParameter.RANDOM_TRAILERS to "RandomTrailers = true",
            AwgParameter.DISABLE_COOKIES to "dIsAbLeCoOkIeS = true",
        )
        options.forEach { (parameter, option) ->
            assertNativeParserError(profile(option), Reason.UNKNOWN_ATTRIBUTE, parameter, R.string.vpn_config_error_unknown_parameter)
        }
        assertNativeParserError(
            profile(peerOptions = "AdvancedSecurity = true"), Reason.UNKNOWN_ATTRIBUTE, AwgParameter.ADVANCED_SECURITY,
            R.string.vpn_config_error_unknown_parameter,
        )
    }

    @Test
    fun rejectsUnrepresentableNumbersAndKeepaliveRangesWithFixedParameterNames() {
        assertNativeParserError(
            profile("Jc = 4294967295"), Reason.INVALID_NUMBER, AwgParameter.JC,
            R.string.vpn_config_error_invalid_number,
        )
        assertNativeParserError(
            profile(peerOptions = "PersistentKeepalive = 10 - 20"), Reason.INVALID_NUMBER, AwgParameter.PERSISTENT_KEEPALIVE,
            R.string.vpn_config_error_invalid_number,
        )
        assertNativeParserError(
            profile(peerOptions = "PersistentKeepalive = 65536"), Reason.INVALID_VALUE, AwgParameter.PERSISTENT_KEEPALIVE,
            R.string.vpn_config_error_invalid_value,
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

    private fun assertNativeParserError(
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
