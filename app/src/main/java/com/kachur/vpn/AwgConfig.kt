package com.kachur.vpn

import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.charset.CodingErrorAction
import java.util.Base64

// Deliberately use identity-based toString() for objects containing credentials.
internal class AwgConfig(
    val interfaceSection: ConfigSection,
    val peers: List<ConfigSection>,
)

internal class ConfigSection(private val fields: Map<String, String>) {
    operator fun get(field: String): String? = fields[field.lowercase()]
}

internal class ConfigFormatException(
    val line: Int? = null,
    val unsupportedOption: Boolean = false,
) : Exception("Invalid VPN configuration")

internal object AwgConfigParser {
    internal const val MAX_CHARS = 256 * 1024
    private const val UINT32_MAX = 4_294_967_295L
    private const val UINT16_MAX = 65_535L
    private val decimal = Regex("[0-9]+")
    private val base64Key = Regex("[A-Za-z0-9+/]{43}=")
    private val ipv6 = Regex("[0-9a-fA-F:.]+")
    private val dnsLabel = Regex("[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?")
    private val applicationId = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*")
    private val interfaceLists = setOf("address", "dns", "includedapplications", "excludedapplications")
    private val signatureFields = setOf("i1", "i2", "i3", "i4", "i5")
    private val optionalEmptyFields = signatureFields + "headerprotectionkey"
    private val headerFields = listOf("h1", "h2", "h3", "h4")
    private val paddingFields = listOf("s1", "s2", "s3", "s4")
    private val timingFields = setOf(
        "rekeyaftertime", "rekeytimeout", "rejectaftertime", "keepalivetimeout",
        "maxhandshakeattempts", "contentpaddingaddition",
    )

    fun parse(input: InputStream): AwgConfig {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val reader = InputStreamReader(input, decoder)
        val source = StringBuilder()
        val buffer = CharArray(4096)
        while (true) {
            val count = reader.read(buffer)
            if (count == -1) break
            if (source.length + count > MAX_CHARS) throw ConfigFormatException()
            source.append(buffer, 0, count)
        }

        var device: SectionBuilder? = null
        var current: SectionBuilder? = null
        val peers = mutableListOf<SectionBuilder>()
        source.lineSequence().forEachIndexed { index, raw ->
            val line = index + 1
            val content = raw.removePrefix(if (index == 0) "\uFEFF" else "")
                .substringBefore('#').trim()
            if (content.isEmpty() || content.startsWith(';')) return@forEachIndexed
            if (content.any { it.isISOControl() && it != '\t' }) throw ConfigFormatException(line)

            when (content.lowercase()) {
                "[interface]" -> {
                    if (device != null) throw ConfigFormatException(line)
                    current = SectionBuilder(line).also { device = it }
                }
                "[peer]" -> {
                    if (device == null) throw ConfigFormatException(line)
                    current = SectionBuilder(line).also { peers.add(it) }
                }
                else -> {
                    val section = current ?: throw ConfigFormatException(line)
                    val separator = content.indexOf('=')
                    if (separator < 1) throw ConfigFormatException(line)
                    val field = content.substring(0, separator).trim().lowercase()
                    val value = content.substring(separator + 1).trim()
                    val onInterface = section === device
                    val valid = (if (onInterface) validInterface(field, value) else validPeer(field, value))
                        ?: throw ConfigFormatException(line, unsupportedOption = true)
                    val previous = section.values[field]
                    val repeatable = if (onInterface) field in interfaceLists else field == "allowedips"
                    if (!valid || (previous != null && !repeatable)) throw ConfigFormatException(line)
                    if (previous == null) section.values[field] = StringBuilder(value)
                    else previous.append(", ").append(value)
                    section.lines[field] = line
                }
            }
        }

        val interfaceSection = device ?: throw ConfigFormatException()
        val interfaceFields = interfaceSection.fields()
        validateInterface(interfaceSection, interfaceFields)
        if (peers.isEmpty()) throw ConfigFormatException()
        val publicKeys = mutableSetOf<String>()
        for (peer in peers) {
            val publicKey = peer.values["publickey"]?.toString() ?: throw ConfigFormatException(peer.startLine)
            if (!publicKeys.add(publicKey)) throw ConfigFormatException(peer.lines["publickey"])
        }
        return AwgConfig(
            ConfigSection(interfaceFields.filterNot { (field, value) ->
                field in optionalEmptyFields && value.isEmpty()
            }),
            peers.map { ConfigSection(it.fields()) },
        )
    }

    private class SectionBuilder(val startLine: Int) {
        val values = mutableMapOf<String, StringBuilder>()
        val lines = mutableMapOf<String, Int>()

        fun fields(): Map<String, String> = values.mapValues { (_, value) -> value.toString() }
    }

    private fun validInterface(field: String, value: String): Boolean? = when (field) {
        "privatekey" -> validKey(value)
        "headerprotectionkey" -> value.isEmpty() || validKey(value)
        "address" -> validList(value, ::validCidr)
        "dns" -> validList(value) { ipVersion(it) != null || validHost(it) }
        "listenport" -> number(value, UINT16_MAX) != null
        "mtu" -> number(value, UINT16_MAX)?.let { it == 0L || it >= 68 } == true
        "includedapplications", "excludedapplications" ->
            validList(value) { it.length <= 255 && applicationId.matches(it) }
        "jc", "jmin", "jmax" -> number(value, UINT32_MAX) != null
        in paddingFields -> number(value, UINT16_MAX) != null
        in headerFields -> range(value) != null
        in timingFields -> range(value, allowOff = true) != null
        "randomtrailers", "disablecookies" -> validBoolean(value)
        in signatureFields -> true
        else -> null
    }

    private fun validPeer(field: String, value: String): Boolean? = when (field) {
        "publickey", "presharedkey" -> validKey(value)
        "allowedips" -> validList(value, ::validCidr)
        "endpoint" -> validEndpoint(value)
        "persistentkeepalive" -> range(value, allowOff = true) != null
        "advancedsecurity" -> validBoolean(value)
        else -> null
    }

    private fun validateInterface(section: SectionBuilder, values: Map<String, String>) {
        if ("privatekey" !in values) throw ConfigFormatException(section.startLine)
        if ("includedapplications" in values && "excludedapplications" in values) {
            val includedLine = section.lines.getValue("includedapplications")
            val excludedLine = section.lines.getValue("excludedapplications")
            throw ConfigFormatException(maxOf(includedLine, excludedLine))
        }
        val min = values["jmin"]?.toLong() ?: 0
        val max = values["jmax"]?.toLong() ?: 0
        if (min > max) throw ConfigFormatException(section.lines["jmin"])
        if (!values["headerprotectionkey"].isNullOrEmpty() && paddingFields.any { (values[it]?.toLong() ?: 0) < 12 }) {
            throw ConfigFormatException(section.lines["headerprotectionkey"])
        }
        val headers = headerFields.mapIndexed { index, field ->
            values[field]?.let { range(it) } ?: (index + 1L)..(index + 1L)
        }
        for ((index, header) in headers.withIndex()) {
            for (other in 0 until index) {
                if (header.first <= headers[other].last && headers[other].first <= header.last) {
                    throw ConfigFormatException(section.lines[headerFields[index]] ?: section.lines[headerFields[other]])
                }
            }
        }
    }

    private fun validKey(value: String): Boolean {
        if (!base64Key.matches(value)) return false
        val decoded = Base64.getDecoder().decode(value)
        return try {
            decoded.size == 32 && Base64.getEncoder().encodeToString(decoded) == value
        } finally {
            decoded.fill(0)
        }
    }

    private fun number(value: String, maximum: Long): Long? =
        value.takeIf { decimal.matches(it) }?.toLongOrNull()?.takeIf { it <= maximum }

    private fun range(value: String, allowOff: Boolean = false): LongRange? {
        if (allowOff && (value.equals("off", true) || value.equals("(off)", true))) return 0L..0L
        val separator = value.indexOf('-')
        val low = number(value.substringBefore('-').trim(), UINT32_MAX) ?: return null
        val high = if (separator < 0) low else
            number(value.substring(separator + 1).trim(), UINT32_MAX) ?: return null
        return (low..high).takeIf { low <= high }
    }

    private fun validBoolean(value: String): Boolean = when (value.lowercase()) {
        "true", "false", "t", "f", "on", "off", "1", "0" -> true
        else -> false
    }

    private fun validList(value: String, validItem: (String) -> Boolean): Boolean =
        value.split(',').all { validItem(it.trim()) }

    private fun validCidr(value: String): Boolean {
        val bits = ipVersion(value.substringBefore('/')) ?: return false
        return '/' !in value || number(value.substringAfter('/'), bits.toLong()) != null
    }

    private fun ipVersion(value: String): Int? {
        if (':' in value) {
            if (value.length > 45 || !ipv6.matches(value)) return null
            return try {
                // The character check and colon ensure the JVM only parses a numeric literal.
                InetAddress.getByName(value)
                128
            } catch (_: UnknownHostException) {
                null
            }
        }
        val octets = value.split('.')
        return 32.takeIf { octets.size == 4 && octets.all { it.length <= 3 && number(it, 255) != null } }
    }

    private fun validHost(value: String): Boolean {
        val host = value.removeSuffix(".")
        return host.length in 1..253 && host.split('.').all { dnsLabel.matches(it) }
    }

    private fun validEndpoint(value: String): Boolean {
        val separator = value.lastIndexOf(':')
        if (separator < 1) return false
        val port = number(value.substring(separator + 1), UINT16_MAX) ?: return false
        val host = value.substring(0, separator)
        val validHostPart = if (host.startsWith('[') && host.endsWith(']')) {
            ipVersion(host.substring(1, host.length - 1)) == 128
        } else {
            validHost(host)
        }
        return port > 0 && validHostPart
    }
}
