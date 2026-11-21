package com.kachur.vpn

import android.util.JsonReader
import android.util.JsonToken
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal enum class IpImportError(val message: Int) {
    FORMAT(R.string.ip_import_format), VERSION(R.string.ip_import_version),
    ENTRY(R.string.ip_import_entry), LIMIT(R.string.ip_import_limit), READ(R.string.ip_import_read),
}

internal class IpImportException(val error: IpImportError, val row: Int? = null) : Exception()

internal object IpRouteImport {
    const val MAX_ENTRIES = 8192
    private const val MAX_TEXT_LENGTH = 2_000_000
    private val hostnamePattern = Regex("[a-zA-Z0-9.-]{1,253}")

    fun read(input: InputStream): Set<String> {
        val source = StringBuilder()
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        val reader = InputStreamReader(input, decoder)
        val buffer = CharArray(4096)
        try {
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (source.length + count > MAX_TEXT_LENGTH) fail(IpImportError.LIMIT)
                source.append(buffer, 0, count)
            }
        } catch (_: CharacterCodingException) {
            fail(IpImportError.FORMAT)
        }

        try {
            JsonReader(StringReader(source.toString().removePrefix("\uFEFF"))).use { json ->
                val result = when (json.peek()) {
                    JsonToken.BEGIN_ARRAY -> readList(json, legacy = true)
                    JsonToken.BEGIN_OBJECT -> readVersioned(json)
                    else -> fail(IpImportError.FORMAT)
                }
                if (json.peek() != JsonToken.END_DOCUMENT) fail(IpImportError.FORMAT)
                return result
            }
        } catch (_: IOException) {
            fail(IpImportError.FORMAT)
        } catch (_: IllegalStateException) {
            fail(IpImportError.FORMAT)
        }
    }

    private fun readVersioned(json: JsonReader): Set<String> {
        var versionFound = false
        var bypass: Set<String>? = null
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "version" -> {
                    if (versionFound || json.peek() != JsonToken.NUMBER) fail(IpImportError.FORMAT)
                    if (json.nextString() != "1") fail(IpImportError.VERSION)
                    versionFound = true
                }
                "bypass" -> {
                    if (bypass != null || json.peek() != JsonToken.BEGIN_ARRAY) fail(IpImportError.FORMAT)
                    bypass = readList(json, legacy = false)
                }
                else -> json.skipValue()
            }
        }
        json.endObject()
        if (!versionFound || bypass == null) fail(IpImportError.FORMAT)
        return bypass
    }

    private fun readList(json: JsonReader, legacy: Boolean): Set<String> {
        val prefixes = linkedSetOf<String>()
        var row = 0
        json.beginArray()
        while (json.hasNext()) {
            row++
            if (row > MAX_ENTRIES) fail(IpImportError.LIMIT)
            val prefix = if (legacy) readLegacyEntry(json, row) else {
                if (json.peek() != JsonToken.STRING) fail(IpImportError.ENTRY, row)
                IpRoutePrefix.parse(json.nextString())
            }
            prefixes.add((prefix ?: fail(IpImportError.ENTRY, row)).toString())
        }
        json.endArray()
        return prefixes
    }

    private fun readLegacyEntry(json: JsonReader, row: Int): IpRoutePrefix? {
        if (json.peek() != JsonToken.BEGIN_OBJECT) fail(IpImportError.ENTRY, row)
        var hostname: String? = null
        var ip: String? = null
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "hostname" -> {
                    if (hostname != null) fail(IpImportError.ENTRY, row)
                    hostname = readField(json, row)
                }
                "ip" -> {
                    if (ip != null) fail(IpImportError.ENTRY, row)
                    ip = readField(json, row)
                }
                else -> json.skipValue()
            }
        }
        json.endObject()
        val explicitIp = ip?.takeIf { it.isNotEmpty() }?.let {
            IpRoutePrefix.parse(it) ?: fail(IpImportError.ENTRY, row)
        }
        if (hostname.isNullOrEmpty()) return explicitIp
        return IpRoutePrefix.parse(hostname) ?: explicitIp?.takeIf {
            hostnamePattern.matches(hostname) && hostname.any { it.isLetter() }
        }
    }

    private fun readField(json: JsonReader, row: Int): String = when (json.peek()) {
        JsonToken.STRING -> json.nextString().trim()
        JsonToken.NULL -> "".also { json.nextNull() }
        else -> fail(IpImportError.ENTRY, row)
    }

    private fun fail(error: IpImportError, row: Int? = null): Nothing = throw IpImportException(error, row)
}
