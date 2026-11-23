package dev.kestrel.proxy.data.model

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
enum class ProxyProtocol(val displayName: String) {
    @SerialName("socks5")
    SOCKS5("SOCKS5"),

    @SerialName("http")
    HTTP("HTTP"),

    @SerialName("socks4a")
    SOCKS4A("SOCKS4a"),

    @SerialName("socks4")
    SOCKS4("SOCKS4"),
}

@Serializable
data class ProxyProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: ProxyProtocol,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    val lastPingMs: Long? = null,
)

@Serializable
enum class AppRoutingMode {
    ALL,
    ONLY_SELECTED,
    BYPASS_SELECTED,
}

@Serializable
enum class RuleAction(val label: String) {
    PROXY("PROXY"),
    DIRECT("DIRECT"),
    BLOCK("BLOCK"),
}

@Serializable
enum class RuleMatchType(val prefix: String, val titleRu: String, val hintRu: String) {
    @SerialName("domain_suffix")
    DOMAIN_SUFFIX("domain:", "Домен и поддомены", "example.com"),

    @SerialName("domain_full")
    DOMAIN_FULL("full:", "Точный домен", "api.example.com"),

    @SerialName("domain_keyword")
    DOMAIN_KEYWORD("keyword:", "Ключевое слово", "tracker"),

    @SerialName("ip_cidr")
    IP_CIDR("ip:", "IP или подсеть (CIDR)", "192.168.1.0/24"),

    @SerialName("port")
    PORT("port:", "Целевой порт", "443"),
}

@Serializable
data class RoutingRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val matchType: RuleMatchType,
    val value: String,
    val action: RuleAction,
    val enabled: Boolean = true,
)

@Serializable
enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT,
}

@Serializable
data class AppSettings(
    val activeProfileId: String? = null,
    val appRoutingMode: AppRoutingMode = AppRoutingMode.ALL,
    val selectedPackages: Set<String> = emptySet(),
    val defaultAction: RuleAction = RuleAction.PROXY,
    val bypassLan: Boolean = true,
    val useCustomDns: Boolean = true,
    val dnsUpstream: String = "1.1.1.1",
    val dnsFallback: String = "8.8.8.8",
    val routeDns: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val mtu: Int = 1500,
)

enum class VpnStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val icon: ImageBitmap? = null,
)

@Serializable
data class TrafficStats(
    @SerialName("tx_bytes")
    val txBytes: Long = 0,
    @SerialName("rx_bytes")
    val rxBytes: Long = 0,
    @SerialName("tx_speed_bps")
    val txSpeedBps: Long = 0,
    @SerialName("rx_speed_bps")
    val rxSpeedBps: Long = 0,
    @SerialName("active_connections")
    val activeConnections: Long = 0,
    @SerialName("uptime_ms")
    val uptimeMs: Long = 0,
)

@Serializable
data class ConnectionLogEntry(
    val id: Long,
    @SerialName("timestamp_ms")
    val timestampMs: Long,
    val protocol: String,
    val host: String,
    @SerialName("dst_ip")
    val dstIp: String,
    @SerialName("dst_port")
    val dstPort: Int,
    val action: RuleAction,
    @SerialName("rule_matched")
    val ruleMatched: String,
)

// --- Rust CoreConfig DTOs ---

@Serializable
data class ProxyConfigDto(
    val protocol: ProxyProtocol,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
)

@Serializable
data class DnsConfigDto(
    val upstream: String = "1.1.1.1:53",
    val fallback: String = "8.8.8.8:53",
    @SerialName("intercept_dns")
    val interceptDns: Boolean = true,
    @SerialName("route_dns")
    val routeDns: Boolean = true,
)

@Serializable
data class RoutingRuleConfigDto(
    val id: String,
    val name: String = "",
    @SerialName("match_type")
    val matchType: RuleMatchType,
    val value: String,
    val action: RuleAction,
    val enabled: Boolean = true,
)

@Serializable
data class RoutingConfigDto(
    @SerialName("default_action")
    val defaultAction: RuleAction = RuleAction.PROXY,
    @SerialName("bypass_lan")
    val bypassLan: Boolean = true,
    val rules: List<RoutingRuleConfigDto> = emptyList(),
)

@Serializable
data class CoreConfigDto(
    val proxy: ProxyConfigDto? = null,
    val dns: DnsConfigDto = DnsConfigDto(),
    val routing: RoutingConfigDto = RoutingConfigDto(),
    val mtu: Int = 1500,
)

private val configJson = Json { encodeDefaults = true }

fun normalizeDnsAddress(raw: String, fallbackIp: String): String {
    val trimmed = raw.trim().ifEmpty { fallbackIp }
    return if (trimmed.contains(':') && !trimmed.startsWith('[')) {
        trimmed
    } else if (trimmed.startsWith('[') && trimmed.contains("]:")) {
        trimmed
    } else if (trimmed.count { it == ':' } > 1) {
        "[$trimmed]:53"
    } else {
        "$trimmed:53"
    }
}

fun buildCoreConfig(
    profile: ProxyProfile?,
    settings: AppSettings,
    rules: List<RoutingRule>,
): CoreConfigDto {
    val primaryDnsIp = if (settings.useCustomDns) settings.dnsUpstream else "8.8.8.8"
    val fallbackDnsIp = if (settings.useCustomDns) settings.dnsFallback else "1.1.1.1"

    return CoreConfigDto(
        proxy = profile?.let {
            ProxyConfigDto(
                protocol = it.protocol,
                host = it.host.trim(),
                port = it.port,
                username = it.username?.takeIf { u -> u.isNotEmpty() },
                password = it.password?.takeIf { p -> p.isNotEmpty() },
            )
        },
        dns = DnsConfigDto(
            upstream = normalizeDnsAddress(primaryDnsIp, "1.1.1.1"),
            fallback = normalizeDnsAddress(fallbackDnsIp, "8.8.8.8"),
            interceptDns = true,
            routeDns = settings.routeDns,
        ),
        routing = RoutingConfigDto(
            defaultAction = settings.defaultAction,
            bypassLan = settings.bypassLan,
            rules = rules.map {
                RoutingRuleConfigDto(
                    id = it.id,
                    name = it.name,
                    matchType = it.matchType,
                    value = it.value.trim(),
                    action = it.action,
                    enabled = it.enabled,
                )
            },
        ),
        mtu = settings.mtu.coerceIn(1280, 1500),
    )
}

fun encodeCoreConfigJson(
    profile: ProxyProfile?,
    settings: AppSettings,
    rules: List<RoutingRule>,
): String = configJson.encodeToString(buildCoreConfig(profile, settings, rules))
