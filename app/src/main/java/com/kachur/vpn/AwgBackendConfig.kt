package com.kachur.vpn

import android.content.Context
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import androidx.annotation.StringRes
import org.amnezia.awg.config.BadConfigException
import org.amnezia.awg.config.BadConfigException.Location
import org.amnezia.awg.config.BadConfigException.Reason
import org.amnezia.awg.config.BadConfigException.Section
import org.amnezia.awg.config.Config

// Android cannot combine allowed and disallowed application lists on a VPN.
internal fun AwgConfig.includedAppsAfterExclusions(exclusions: Set<String>): List<String>? =
    interfaceSection["IncludedApplications"]?.split(',')?.map { it.trim() }?.filterNot(exclusions::contains)

internal class NoIncludedVpnAppsException : Exception("No applications remain in the VPN allowlist")

internal fun Config.ipRoutePlan(routes: Set<String>): IpRoutePlan? {
    if (routes.isEmpty()) return null
    val allowed = peers.flatMap { peer -> peer.allowedIps.map { IpRoutePrefix.from(it.address.address, it.mask) } }
    val bypass = routes.map { checkNotNull(IpRoutePrefix.parse(it)) }
    val plan = IpRoutePlan.build(allowed, bypass,
        nativeExclusion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
    // A DNS server reachable through the peer must not be sent outside by a bypass rule.
    // Do not expand the peer's AllowedIPs or change the user's configured resolver.
    val pinned = getInterface().dnsServers
        .filter { address -> !address.isLoopbackAddress && allowed.any { it.contains(address) } &&
            bypass.any { it.contains(address) } }
        .map { IpRoutePrefix.from(it.address, it.address.size * 8) }.toSet()
    val tunnel = (plan.tunnel + pinned).distinct()
    val direct = plan.direct.filterNot { it in pinned }
    if (tunnel.size + direct.size > IpRoutePlan.MAX_ROUTES) throw TooManyVpnRoutesException()
    return IpRoutePlan(tunnel, direct)
}

internal fun IpRoutePlan.configure(builder: VpnService.Builder) {
    for (route in tunnel) builder.addRoute(route.address, route.length)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        for (route in direct) builder.excludeRoute(IpPrefix(route.address, route.length))
    } else {
        check(direct.isEmpty())
    }
}

internal fun AwgConfig.toBackendConfig(exclusions: Set<String> = emptySet()): Config {
    val included = includedAppsAfterExclusions(exclusions)
    if (included?.isEmpty() == true) throw NoIncludedVpnAppsException()
    val importedExclusions = interfaceSection["ExcludedApplications"]
    val disallowed = if (included == null) {
        importedExclusions?.split(',')?.map { it.trim() }.orEmpty().toSet() + exclusions
    } else {
        emptySet()
    }

    return buildString {
        appendLine("[Interface]")
        interfaceSection.appendFields(this) { name, value ->
            when {
                name in AwgConfigParser.headerFields -> compactRange(value)
                name == "includedapplications" -> checkNotNull(included).joinToString(", ")
                name == "excludedapplications" -> disallowed.joinToString(", ")
                else -> value
            }
        }
        if (importedExclusions == null && disallowed.isNotEmpty()) {
            appendLine("ExcludedApplications = ${disallowed.joinToString(", ")}")
        }
        for (peer in peers) {
            appendLine("[Peer]")
            peer.appendFields(this) { name, value ->
                when {
                    name != "persistentkeepalive" -> value
                    value.isEmpty() || AwgConfigParser.isOff(value) -> "0"
                    else -> compactRange(value)
                }
            }
        }
    }.reader().buffered().use(Config::parse)
}

private fun compactRange(value: String): String = value.split('-', limit = 2).joinToString("-") { it.trim() }

internal data class VpnError(@StringRes val category: Int, val parameter: AwgParameter? = null) {
    fun display(context: Context): String {
        val message = context.getString(category)
        return parameter?.let { context.getString(R.string.vpn_config_error_parameter, message, it.label) } ?: message
    }
}

// The parser's Location labels aren't always .conf names. Only these fixed labels reach the UI.
internal enum class AwgParameter(
    val label: String,
    val location: Location? = null,
    val unsupportedSection: Section? = null,
) {
    ADDRESS("Address", Location.ADDRESS),
    ALLOWED_IPS("AllowedIPs", Location.ALLOWED_IPS),
    DNS("DNS", Location.DNS),
    ENDPOINT("Endpoint", Location.ENDPOINT),
    EXCLUDED_APPLICATIONS("ExcludedApplications", Location.EXCLUDED_APPLICATIONS),
    INCLUDED_APPLICATIONS("IncludedApplications", Location.INCLUDED_APPLICATIONS),
    LISTEN_PORT("ListenPort", Location.LISTEN_PORT),
    MTU("MTU", Location.MTU),
    PERSISTENT_KEEPALIVE("PersistentKeepalive", Location.PERSISTENT_KEEPALIVE),
    PRE_SHARED_KEY("PresharedKey", Location.PRE_SHARED_KEY),
    PRIVATE_KEY("PrivateKey", Location.PRIVATE_KEY),
    PUBLIC_KEY("PublicKey", Location.PUBLIC_KEY),
    JC("Jc", Location.JUNK_PACKET_COUNT),
    JMIN("Jmin", Location.JUNK_PACKET_MIN_SIZE),
    JMAX("Jmax", Location.JUNK_PACKET_MAX_SIZE),
    S1("S1", Location.INIT_PACKET_JUNK_SIZE),
    S2("S2", Location.RESPONSE_PACKET_JUNK_SIZE),
    S3("S3", Location.COOKIE_REPLY_PACKET_JUNK_SIZE),
    S4("S4", Location.TRANSPORT_PACKET_JUNK_SIZE),
    H1("H1", Location.INIT_PACKET_MAGIC_HEADER),
    H2("H2", Location.RESPONSE_PACKET_MAGIC_HEADER),
    H3("H3", Location.UNDERLOAD_PACKET_MAGIC_HEADER),
    H4("H4", Location.TRANSPORT_PACKET_MAGIC_HEADER),
    I1("I1", Location.SPECIAL_JUNK_I1),
    I2("I2", Location.SPECIAL_JUNK_I2),
    I3("I3", Location.SPECIAL_JUNK_I3),
    I4("I4", Location.SPECIAL_JUNK_I4),
    I5("I5", Location.SPECIAL_JUNK_I5),
    HEADER_PROTECTION_KEY("HeaderProtectionKey", Location.HEADER_PROTECTION_KEY),
    REKEY_AFTER_TIME("RekeyAfterTime", Location.REKEY_AFTER_TIME),
    REKEY_TIMEOUT("RekeyTimeout", Location.REKEY_TIMEOUT),
    REJECT_AFTER_TIME("RejectAfterTime", Location.REJECT_AFTER_TIME),
    KEEPALIVE_TIMEOUT("KeepaliveTimeout", Location.KEEPALIVE_TIMEOUT),
    MAX_HANDSHAKE_ATTEMPTS("MaxHandshakeAttempts", Location.MAX_HANDSHAKE_ATTEMPTS),
    CONTENT_PADDING_ADDITION("ContentPaddingAddition", Location.CONTENT_PADDING_ADDITION),
    RANDOM_TRAILERS("RandomTrailers", Location.RANDOM_TRAILERS),
    DISABLE_COOKIES("DisableCookies", Location.DISABLE_COOKIES),
    ADVANCED_SECURITY("AdvancedSecurity", unsupportedSection = Section.PEER),
}

internal fun BadConfigException.toSafeVpnError(): VpnError {
    val category = when (reason) {
        Reason.INVALID_KEY -> R.string.vpn_config_error_invalid_key
        Reason.INVALID_NUMBER -> R.string.vpn_config_error_invalid_number
        Reason.INVALID_VALUE -> R.string.vpn_config_error_invalid_value
        Reason.MISSING_ATTRIBUTE -> R.string.vpn_config_error_missing_parameter
        Reason.MISSING_SECTION -> R.string.vpn_config_error_missing_section
        Reason.SYNTAX_ERROR -> R.string.vpn_config_error_syntax
        Reason.UNKNOWN_ATTRIBUTE -> R.string.vpn_config_error_unknown_parameter
        Reason.UNKNOWN_SECTION -> R.string.vpn_config_error_unknown_section
    }
    val parameter = if (reason == Reason.UNKNOWN_ATTRIBUTE) {
        // The pinned parser stores the attribute name for this reason. Match it against fixed
        // labels in the matching section, and never return the original exception text.
        val attribute = text?.toString()
        AwgParameter.entries.firstOrNull {
            it.unsupportedSection == section && it.label.equals(attribute, ignoreCase = true)
        }
    } else {
        AwgParameter.entries.firstOrNull { it.location == location }
    }
    return VpnError(category, parameter)
}
