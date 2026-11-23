package dev.kestrel.proxy.data.util

import dev.kestrel.proxy.data.model.ProxyProfile
import dev.kestrel.proxy.data.model.ProxyProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object ProxyUriParser {

    fun parseUri(raw: String): Result<ProxyProfile> = runCatching {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "Пустая строка прокси" }

        val uri = URI(trimmed)
        val scheme = uri.scheme?.lowercase()
            ?: throw IllegalArgumentException("Не указан протокол (socks5://, http://, socks4://)")

        val protocol = when (scheme) {
            "socks5", "socks5h", "socks" -> ProxyProtocol.SOCKS5
            "socks4" -> ProxyProtocol.SOCKS4
            "socks4a" -> ProxyProtocol.SOCKS4A
            "http", "https" -> ProxyProtocol.HTTP
            else -> throw IllegalArgumentException("Неподдерживаемый протокол: $scheme")
        }

        val host = uri.host?.trim('[', ']')?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Не указан адрес сервера")

        val port = when {
            uri.port in 1..65535 -> uri.port
            scheme == "https" -> 443
            scheme == "http" -> 8080
            else -> 1080
        }

        var username: String? = null
        var password: String? = null
        val userInfo = uri.rawUserInfo
        if (!userInfo.isNullOrEmpty()) {
            val parts = userInfo.split(':', limit = 2)
            username = URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name())
                .takeIf { it.isNotEmpty() }
            if (parts.size > 1) {
                password = URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name())
                    .takeIf { it.isNotEmpty() }
            }
        }

        val fragmentName = uri.rawFragment
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        ProxyProfile(
            name = fragmentName ?: "${protocol.displayName} $host:$port",
            protocol = protocol,
            host = host,
            port = port,
            username = username,
            password = password,
        )
    }

    fun toUri(profile: ProxyProfile): String {
        val scheme = when (profile.protocol) {
            ProxyProtocol.SOCKS5 -> "socks5"
            ProxyProtocol.SOCKS4 -> "socks4"
            ProxyProtocol.SOCKS4A -> "socks4a"
            ProxyProtocol.HTTP -> "http"
        }
        val auth = if (!profile.username.isNullOrEmpty()) {
            val userEnc = URLEncoder.encode(profile.username, StandardCharsets.UTF_8.name())
            val passEnc = profile.password?.takeIf { it.isNotEmpty() }
                ?.let { ":" + URLEncoder.encode(it, StandardCharsets.UTF_8.name()) }
                .orEmpty()
            "$userEnc$passEnc@"
        } else {
            ""
        }
        val hostPart = if (profile.host.contains(':') && !profile.host.startsWith('[')) {
            "[${profile.host}]"
        } else {
            profile.host
        }
        val frag = if (profile.name.isNotBlank()) {
            "#" + URLEncoder.encode(profile.name, StandardCharsets.UTF_8.name())
        } else {
            ""
        }
        return "$scheme://$auth$hostPart:${profile.port}$frag"
    }
}

object ProxyPinger {
    suspend fun measureLatencyMs(
        profile: ProxyProfile,
        timeoutMs: Int = 3000,
    ): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val startNs = System.nanoTime()
            Socket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(profile.host, profile.port), timeoutMs)
            }
            ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
        }.getOrNull()
    }
}
