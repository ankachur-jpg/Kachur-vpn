package com.kachur.vpn

import android.net.IpPrefix
import android.net.RouteInfo
import android.net.VpnService
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
import org.robolectric.util.ReflectionHelpers
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AwgBackendConfigTest {
    @Test
    fun ipPlanUsesAnAndroidThrowRouteAndKeepsAwgAndAppRules() {
        val config = profile("", "AllowedIPs = 0.0.0.0/0, 192.0.2.64/26")
            .toBackendConfig(setOf("com.example.direct"))
        val builder = VpnService().Builder()
        checkNotNull(config.ipRoutePlan(setOf("192.0.2.0/24"))).configure(builder)
        val routes = ReflectionHelpers.getField<List<RouteInfo>>(builder, "mRoutes")
        val destination = IpPrefix(checkNotNull(IpRoutePrefix.parse("192.0.2.0/24")).address, 24)
        val excluded = routes.single { it.destination == destination }
        assertEquals(9, ReflectionHelpers.callInstanceMethod<Int>(excluded, "getType")) // RTN_THROW
        assertEquals(2, routes.size)
        assertEquals(2, config.peers.single().allowedIps.size)
        assertEquals(setOf("com.example.direct"), config.getInterface().excludedApplications)
    }

    @Test
    // API 28's RouteInfo uses Android-specific java.net fields that Robolectric's JVM lacks.
    @Config(sdk = [32])
    fun legacyAndroidBuilderReceivesOnlyTheRemainingIpv4AndIpv6Routes() {
        val config = profile("", "AllowedIPs = 0.0.0.0/0, ::/0").toBackendConfig()
        val plan = checkNotNull(config.ipRoutePlan(setOf("192.0.2.0/24", "::fffe:0:0/96")))
        val builder = VpnService().Builder()
        plan.configure(builder)
        val routes = ReflectionHelpers.getField<List<RouteInfo>>(builder, "mRoutes")
        fun includes(address: String): Boolean {
            val host = checkNotNull(IpRoutePrefix.parse(address)).address
            return routes.any { it.destination.contains(host) }
        }
        assertTrue(plan.direct.isEmpty())
        assertFalse(includes("192.0.2.7"))
        assertTrue(includes("198.51.100.7"))
        assertFalse(includes("::fffe:0:7"))
        assertTrue(includes("2001:db8::7"))
        assertFalse(routes.any { it.destination.prefixLength > 32 && it.destination.address.address.size == 4 })
    }

    @Test
    @Config(sdk = [32, 34])
    fun dnsHostsStayInsideTunnelDespiteBroadOrExactIpExclusions() {
        val config = profile("DNS = 192.0.2.53, 2001:db8::53",
            "AllowedIPs = 0.0.0.0/0, ::/0").toBackendConfig()
        for (bypass in listOf(setOf("192.0.2.0/24", "2001:db8::/48"),
            setOf("192.0.2.53/32", "2001:db8::53/128"))) {
            val plan = checkNotNull(config.ipRoutePlan(bypass))
            fun tunneled(ip: String): Boolean {
                val address = checkNotNull(IpRoutePrefix.parse(ip)).address
                val include = plan.tunnel.filter { it.contains(address) }.maxOfOrNull { it.length } ?: return false
                val exclude = plan.direct.filter { it.contains(address) }.maxOfOrNull { it.length }
                return exclude == null || include > exclude
            }
            assertTrue(tunneled("192.0.2.53"))
            assertTrue(tunneled("2001:db8::53"))
            if (bypass.contains("192.0.2.0/24")) assertFalse(tunneled("192.0.2.54"))
            if (bypass.contains("2001:db8::/48")) assertFalse(tunneled("2001:db8::54"))
            assertTrue(tunneled("198.51.100.7"))
        }
    }

    @Test
    fun routingPausePreservesSavedRules() {
        val context = RuntimeEnvironment.getApplication()
        val settings = SplitTunnelingSettings(context)
        settings.preferences.edit().clear().commit()
        try {
            settings.setExcluded("com.example.direct", true)
            settings.addIpRoutes(setOf("192.0.2.0/24"))
            val original = settings.snapshot()
            settings.setAppRoutingEnabled(false)
            assertTrue(settings.snapshot().apps.isEmpty())
            assertEquals(original.ipRoutes, settings.snapshot().ipRoutes)
            settings.setIpRoutingEnabled(false)
            assertEquals(VpnRoutingSettings(emptySet(), emptySet()), settings.snapshot())
            assertEquals(original.apps, settings.excludedPackages())
            assertEquals(original.ipRoutes, settings.manualIpRoutes())
            settings.setAppRoutingEnabled(true)
            settings.setIpRoutingEnabled(true)
            assertEquals(original, settings.snapshot())
        } finally {
            settings.preferences.edit().clear().commit()
        }
    }

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
    fun variablePaddingReachesNativeEngineAndCanBeReverted() {
        for (original in listOf(null, "0", "4-32")) {
            val imported = profile(original?.let { "ContentPaddingAddition = $it" } ?: "")
            val saved = imported.toPortableConfig()
            for (enabled in listOf(false, true, false)) {
                val lines = imported.toBackendConfig(variablePadding = enabled).toAwgUserspaceString()
                    .lineSequence().filter { it.startsWith("content_padding_addition=") }.toList()
                val expected = if (enabled) "0-64" else original
                assertEquals(listOfNotNull(expected?.let { "content_padding_addition=$it" }), lines)
                assertEquals(saved, imported.toPortableConfig())
            }
        }
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
    @Config(sdk = [28, 34])
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
    @Config(sdk = [28, 34])
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
