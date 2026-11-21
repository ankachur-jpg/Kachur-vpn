package com.kachur.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IpRouteImportTest {
    @Test
    fun readsLegacyHostnameCidrAndNumericIpFallback() {
        val imported = read("""
            [
              {"hostname":"192.0.2.7/24","ip":""},
              {"hostname":"192.0.2.0/24","ip":null},
              {"hostname":"test.example","ip":"198.51.100.4"},
              {"hostname":"203.0.113.9","ip":"203.0.113.10"},
              {"ip":"203.0.113.7"}
            ]
        """.trimIndent())
        assertEquals(setOf("192.0.2.0/24", "198.51.100.4/32", "203.0.113.9/32", "203.0.113.7/32"), imported)
    }

    @Test
    fun readsVersionedBypassAndDeduplicatesEquivalentIpv6() {
        val imported = read("\uFEFF" + """{
            "bypass":["192.0.2.7","192.0.2.7/32","2001:db8::/32","2001:0db8:abcd::1/32"], "version":1
        }""")
        assertEquals(setOf("192.0.2.7/32", IpRoutePrefix.parse("2001:db8::/32").toString()), imported)
        assertEquals(emptySet<String>(), read("[]"))
    }

    @Test
    fun rejectsInvalidEntriesWithoutExposingTheirValues() {
        val cases = listOf(
            """[{"hostname":"192.0.2.7"},{"hostname":"example.test","ip":""}]""" to 2,
            """[{"hostname":"999.0.0.0","ip":"192.0.2.7"}]""" to 1,
            """[{"hostname":"192.0.2.7","ip":"synthetic-sensitive-value"}]""" to 1,
            """{"version":1,"bypass":["192.0.2.1",25]}""" to 2,
            """{"version":1,"bypass":["192.0.2.1/33"]}""" to 1,
        )
        for ((source, row) in cases) {
            val failure = assertThrows(IpImportException::class.java) { read(source) }
            assertEquals(IpImportError.ENTRY, failure.error)
            assertEquals(row, failure.row)
            assertFalse(failure.toString().contains(source))
            assertFalse(failure.toString().contains("synthetic-sensitive-value"))
        }
    }

    @Test
    fun refusesMalformedUnknownVersionAndOversizedDocuments() {
        assertError(IpImportError.VERSION, """{"version":2,"bypass":[]}""")
        for (malformed in listOf("[][]", "{\"bypass\":[]}", "[", "[/* comment */]", """{"version":1,"bypass":{}}""")) {
            assertError(IpImportError.FORMAT, malformed)
        }
        assertError(IpImportError.LIMIT, " ".repeat(2 * 1024 * 1024) + "[]")
        val excessive = List(IpRouteImport.MAX_ENTRIES + 1) { "\"192.0.2.1\"" }.joinToString(",")
        assertError(IpImportError.LIMIT, """{"version":1,"bypass":[$excessive]}""")
    }

    private fun assertError(expected: IpImportError, json: String) {
        assertEquals(expected, assertThrows(IpImportException::class.java) { read(json) }.error)
    }

    private fun read(json: String) = json.byteInputStream().use(IpRouteImport::read)
}
