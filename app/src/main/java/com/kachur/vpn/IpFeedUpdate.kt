package com.kachur.vpn

import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONException
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import javax.net.ssl.HttpsURLConnection

private const val RIPESTAT_RU = "https://stat.ripe.net/data/country-resource-list/data.json?resource=ru&v4_format=prefix"
private const val MAX_BATCH_PREFIXES = 32_768
private const val SOURCE_TIMEOUT = 25_000L

internal class IpFeedException(val result: IpUpdateResult) : Exception()

internal object RipeIpFeed {
    /** Use exact larger allocations first, with capacity left for manual rules and JSON sources. */
    fun read(input: InputStream, nativeExclusion: Boolean): Set<String> {
        val text = IpRouteImport.readText(input)
        val prefixes = ArrayList<IpRoutePrefix>()
        try {
            val document = JSONObject(text)
            if (document.optString("status") != "ok") invalidFeed()
            val resources = document.getJSONObject("data").getJSONObject("resources")
            for ((family, width) in listOf("ipv4" to 4, "ipv6" to 16)) {
                val values = resources.getJSONArray(family)
                if (values.length() == 0) invalidFeed()
                if (prefixes.size + values.length() > MAX_BATCH_PREFIXES) throw IpFeedException(IpUpdateResult.LIMIT)
                for (index in 0 until values.length()) {
                    val value = values.get(index) as? String ?: invalidFeed()
                    val prefix = IpRoutePrefix.parse(value) ?: invalidFeed()
                    if ('/' !in value || prefix.address.address.size != width || prefix.length == 0) invalidFeed()
                    prefixes.add(prefix)
                }
            }
        } catch (_: JSONException) {
            invalidFeed()
        }
        val collapsed = collapseIpPrefixes(prefixes)
        if (collapsed.any { it.length == 0 }) throw IpFeedException(IpUpdateResult.UNSAFE)
        val limitPerFamily = IpRoutePlan.MAX_ROUTES * 3 / 8 - 1
        return collapsed.groupBy { it.address.address.size }.values.flatMap { family ->
            var size = 0
            family.sortedBy { it.length }.takeWhile { prefix ->
                // Without excludeRoute each excluded prefix can expand the positive plan by its depth.
                size += if (nativeExclusion) 1 else prefix.length
                size <= limitPerFamily
            }
        }.mapTo(linkedSetOf(), IpRoutePrefix::toString)
    }

    private fun invalidFeed(): Nothing = throw IpFeedException(IpUpdateResult.INVALID)
}

internal class IpFeedUpdater(
    context: Context,
    private val fetch: (IpUpdateSource) -> Set<String> = ::downloadIpFeed,
    private val nativeExclusion: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
) {
    private val settings = IpUpdateSettings(context)

    // Fetch and validate the entire batch before replacing any last-known-good snapshot.
    fun refresh(cancelled: () -> Boolean = { false }): IpUpdateResult? {
        val configuration = settings.configuration()
        val manual = settings.manualRoutes()
        if (configuration.sources.isEmpty()) return IpUpdateResult.EMPTY
        var current: IpUpdateSource? = null
        val snapshots = linkedMapOf<IpUpdateSource, Set<String>>()
        val result = try {
            for (source in configuration.sources) {
                if (cancelled()) return null
                current = source
                val routes = fetch(source)
                if (routes.isEmpty()) throw IpFeedException(IpUpdateResult.INVALID)
                snapshots[source] = routes
            }
            current = null
            validateDownloadedRoutes(manual, snapshots.values, nativeExclusion)
            if (cancelled()) return null
            settings.save(configuration, manual, snapshots, System.currentTimeMillis())
        } catch (error: IpFeedException) {
            error.result
        } catch (error: IpImportException) {
            if (error.error == IpImportError.LIMIT) IpUpdateResult.LIMIT else IpUpdateResult.INVALID
        } catch (_: TooManyVpnRoutesException) {
            IpUpdateResult.LIMIT
        } catch (_: IOException) {
            IpUpdateResult.NETWORK
        } catch (_: RuntimeException) {
            IpUpdateResult.INVALID
        }
        if (cancelled()) return null
        if (result != IpUpdateResult.SUCCESS) settings.recordError(configuration, result, System.currentTimeMillis(), current?.key)
        return result
    }

}

internal fun validateDownloadedRoutes(manual: Set<String>, groups: Collection<Set<String>>, nativeExclusion: Boolean) {
    val downloaded = groups.flatten().toSet()
    if (downloaded.size > MAX_BATCH_PREFIXES) throw IpFeedException(IpUpdateResult.LIMIT)
    fun prefix(value: String) = IpRoutePrefix.parse(value) ?: throw IpFeedException(IpUpdateResult.INVALID)
    val blocks = collapseIpPrefixes(downloaded.map(::prefix))
    if (blocks.any { it.length == 0 }) throw IpFeedException(IpUpdateResult.UNSAFE)
    val fullTunnel = listOf(prefix("0.0.0.0/0"), prefix("::/0"))
    IpRoutePlan.build(fullTunnel, blocks + manual.map(::prefix), nativeExclusion)
}

private fun downloadIpFeed(source: IpUpdateSource): Set<String> {
    var uri = source.url ?: URI(RIPESTAT_RU)
    val deadline = SystemClock.elapsedRealtime() + SOURCE_TIMEOUT
    repeat(4) {
        checkDownloadDeadline(deadline)
        val connection = uri.toURL().openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 7_000
        connection.readTimeout = 7_000
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        try {
            when (connection.responseCode) {
                200 -> return connection.inputStream.use { raw ->
                    val input = object : FilterInputStream(raw) {
                        override fun read(): Int {
                            checkDownloadDeadline(deadline)
                            return super.read()
                        }

                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            checkDownloadDeadline(deadline)
                            return super.read(buffer, offset, length)
                        }
                    }
                    if (source.url == null) RipeIpFeed.read(input, Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    else IpRouteImport.read(input)
                }
                301, 302, 303, 307, 308 -> {
                    val location = connection.getHeaderField("Location") ?: throw IOException()
                    uri = IpUpdateSource.custom(uri.resolve(location).toASCIIString())?.url ?: throw IOException()
                }
                else -> throw IOException()
            }
        } finally {
            connection.disconnect()
        }
    }
    throw IOException()
}

private fun checkDownloadDeadline(deadline: Long) {
    if (Thread.currentThread().isInterrupted || SystemClock.elapsedRealtime() >= deadline) throw IOException()
}
