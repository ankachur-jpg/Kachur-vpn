package com.kachur.vpn

import java.nio.charset.CharacterCodingException
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class AwgConfigParserTest {
    // Deterministic dummy bytes for testing encoding; no operational credentials.
    private val dummyPrivate = dummyKey(0)
    private val dummyPublic = dummyKey(1)
    private val dummyHeader = dummyKey(2)

    @Test
    fun reportsTheReasonForMissingSectionsKeysAndOversizedInput() {
        fun assertProblem(problem: ConfigProblem, content: String, line: Int? = null) {
            val error = assertThrows(ConfigFormatException::class.java) { parse(content) }
            assertEquals(problem, error.problem)
            assertEquals(line, error.line)
        }

        assertProblem(ConfigProblem.MISSING_INTERFACE, "# Empty export")
        assertProblem(ConfigProblem.MISSING_PRIVATE_KEY, "[Interface]", 1)
        assertProblem(ConfigProblem.MISSING_PEER, "[Interface]\nPrivateKey = ${dummyPrivate}")
        assertProblem(
            ConfigProblem.MISSING_PUBLIC_KEY, "[Interface]\nPrivateKey = ${dummyPrivate}\n[Peer]", 3,
        )
        assertProblem(ConfigProblem.TOO_LARGE, " ".repeat(AwgConfigParser.MAX_CHARS + 1))
    }

    @Test
    fun importsMultiplePeersAndAwgOptions() {
        val config = parse(
            "\uFEFF" + example(
                device = """
                    Address = 192.0.2.2/32, 2001:db8::2/128
                    DNS = 192.0.2.53, example.test
                    ListenPort = 51820
                    MTU = 1420
                    HeaderProtectionKey = $dummyHeader
                    RekeyAfterTime = 100 - 120
                    RekeyTimeout = 5-8
                    RejectAfterTime = 180
                    KeepaliveTimeout = off
                    MaxHandshakeAttempts = 15-20
                    RandomTrailers = on
                    DisableCookies = false
                    Jc = 4
                    Jmin = 20
                    Jmax = 80
                    S1 = 12
                    S2 = 13
                    S3 = 14
                    S4 = 15
                    H1 = 1000-1100
                    H2 = 1101
                    H3 = 1200-1300
                    H4 = 4294967295 # uint32 maximum
                    I1 = <b 0x1234><r 4>
                """.trimIndent(),
                peer = """
                    Endpoint = [2001:db8::1]:51820
                    AllowedIPs = 0.0.0.0/0, ::/0
                    PersistentKeepalive = 22-30
                    PresharedKey = ${dummyKey(3)}
                    [pEeR]
                    PublicKey = ${dummyKey(4)}
                    Endpoint = vpn.example.test:51820
                    PersistentKeepalive = (off)
                """.trimIndent(),
            ).replace("\n", "\r\n"),
        )
        val expected = mapOf(
            "HeaderProtectionKey" to dummyHeader,
            "RekeyAfterTime" to "100 - 120",
            "RekeyTimeout" to "5-8",
            "RejectAfterTime" to "180",
            "KeepaliveTimeout" to "off",
            "MaxHandshakeAttempts" to "15-20",
            "RandomTrailers" to "on",
            "DisableCookies" to "false",
            "Jc" to "4", "Jmin" to "20", "Jmax" to "80",
            "S1" to "12", "S2" to "13", "S3" to "14", "S4" to "15",
            "H1" to "1000-1100", "H2" to "1101", "H3" to "1200-1300", "H4" to "4294967295",
        )
        expected.forEach { (field, value) -> assertEquals(value, config.interfaceSection[field]) }
        assertEquals(2, config.peers.size)
        assertEquals("22-30", config.peers[0]["PersistentKeepalive"])
        assertEquals("(off)", config.peers[1]["PersistentKeepalive"])
        assertFalse(config.toString().contains(dummyPrivate))
        assertFalse(config.interfaceSection.toString().contains(dummyHeader))
        assertFalse(config.peers[0].toString().contains(dummyPublic))
    }

    @Test
    fun importsAndroidExportsWithRepeatedListsAndUnsetOptionalFields() {
        val config = parse(example(
            device = """
                Address = 192.0.2.2/32
                Address = 2001:db8::2/128
                DNS = 192.0.2.53
                DNS = example.test
                ExcludedApplications = com.example.one, com.example.two
                HeaderProtectionKey =
                I1 =
                MTU = 0
            """.trimIndent(),
            peer = "AllowedIPs = 0.0.0.0/0\nAllowedIPs = ::/0",
        ))
        assertEquals("192.0.2.2/32, 2001:db8::2/128", config.interfaceSection["Address"])
        assertEquals("192.0.2.53, example.test", config.interfaceSection["DNS"])
        assertEquals("com.example.one, com.example.two", config.interfaceSection["ExcludedApplications"])
        val included = parse(example(device = "IncludedApplications = com.example.three"))
        assertEquals("com.example.three", included.interfaceSection["IncludedApplications"])
        assertEquals("0", config.interfaceSection["MTU"])
        assertEquals(null, config.interfaceSection["HeaderProtectionKey"])
        assertEquals(null, config.interfaceSection["I1"])
        assertEquals("0.0.0.0/0, ::/0", config.peers.single()["AllowedIPs"])
    }

    @Test
    fun rejectsInvalidAwgValuesAndConflicts() {
        listOf(
            "H1 = 4294967296",
            "H1 = 700-600",
            "H1 = 10-20\nH2 = 20-30",
            "H1 = 2", // Overlaps the default H2.
            "H1 = 10-20-30",
            "Jc = -1",
            "Jmin = 100\nJmax = 90",
            "S4 = 65536",
            "HeaderProtectionKey = $dummyHeader\nS1 = 11\nS2 = 12\nS3 = 12\nS4 = 12",
            "HeaderProtectionKey = invalid",
            "RekeyTimeout = 8-",
            "MaxHandshakeAttempts = 4294967296",
            "DisableCookies = maybe",
            "IncludedApplications = com.example.one\nExcludedApplications = com.example.two",
        ).forEach { device ->
            assertThrows(ConfigFormatException::class.java) { parse(example(device = device)) }
        }
    }

    @Test
    fun rejectsMisplacedAndDuplicateFieldsWithoutEchoingTheirContents() {
        val secret = "synthetic-sensitive-value"
        val error = assertThrows(ConfigFormatException::class.java) {
            parse(example(device = "pRivAtEKey = $secret"))
        }
        assertEquals(3, error.line)
        assertFalse(error.toString().contains(secret))

        listOf(
            example(device = "[Interface]"),
            example(device = "PostUp = echo $secret"),
            example(peer = "RandomTrailers = true"),
            example(peer = "[Peer]\nPublicKey = $dummyPublic"),
            "[Interface]\nJc = 3\n[Peer]\nPublicKey = $dummyPublic",
            "[Interface]\nPrivateKey = $dummyPrivate",
            "[Peer]\nPublicKey = $dummyPublic",
            example(peer = "[Peer]"),
            example(peer = "Endpoint = example.test:65536"),
            example(peer = "AllowedIPs = 2001:db8::/129"),
        ).forEach { source ->
            assertThrows(ConfigFormatException::class.java) { parse(source) }
        }
    }

    @Test
    fun rejectsOversizedAndMalformedInput() {
        assertThrows(ConfigFormatException::class.java) {
            parse("#".repeat(AwgConfigParser.MAX_CHARS + 1))
        }
        assertThrows(CharacterCodingException::class.java) {
            AwgConfigParser.parse(byteArrayOf(0xc3.toByte(), 0x28).inputStream())
        }
    }

    private fun parse(source: String): AwgConfig =
        source.byteInputStream(Charsets.UTF_8).use(AwgConfigParser::parse)

    private fun example(device: String = "", peer: String = "") =
        "[Interface]\nPrivateKey = $dummyPrivate\n$device\n[Peer]\nPublicKey = $dummyPublic\n$peer"

    private fun dummyKey(byte: Int) = Base64.getEncoder().encodeToString(ByteArray(32) { byte.toByte() })
}
