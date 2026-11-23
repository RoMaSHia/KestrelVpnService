package dev.kestrel.proxy

import dev.kestrel.proxy.data.model.AppRoutingMode
import dev.kestrel.proxy.data.model.AppSettings
import dev.kestrel.proxy.data.model.ProxyProfile
import dev.kestrel.proxy.data.model.ProxyProtocol
import dev.kestrel.proxy.data.model.RoutingRule
import dev.kestrel.proxy.data.model.RuleAction
import dev.kestrel.proxy.data.model.RuleMatchType
import dev.kestrel.proxy.data.model.encodeCoreConfigJson
import dev.kestrel.proxy.data.util.ProxyUriParser
import dev.kestrel.proxy.data.util.RuleSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KestrelUnitTest {

    @Test
    fun proxyUriParserHandlesSocks5WithAuthAndFragment() {
        val parsed = ProxyUriParser
            .parseUri("socks5://alice:s3cr%40t@proxy.example.com:1080#Home%20Server")
            .getOrThrow()

        assertEquals(ProxyProtocol.SOCKS5, parsed.protocol)
        assertEquals("proxy.example.com", parsed.host)
        assertEquals(1080, parsed.port)
        assertEquals("alice", parsed.username)
        assertEquals("s3cr@t", parsed.password)
        assertEquals("Home Server", parsed.name)

        val roundtrip = ProxyUriParser.toUri(parsed)
        assertTrue(roundtrip.startsWith("socks5://alice:"))
        assertTrue(roundtrip.contains("proxy.example.com:1080"))
    }

    @Test
    fun proxyUriParserHandlesHttpAndIpv6() {
        val parsed = ProxyUriParser.parseUri("http://[2001:db8::1]:8080").getOrThrow()
        assertEquals(ProxyProtocol.HTTP, parsed.protocol)
        assertEquals("2001:db8::1", parsed.host)
        assertEquals(8080, parsed.port)
    }

    @Test
    fun ruleSerializerSupportsJsonAndPlainTextLists() {
        val text = """
            # Sample routing rules
            DIRECT domain:gosuslugi.ru
            BLOCK keyword:doubleclick
            DIRECT 192.168.10.0/24
            full:api.ipify.org
            443
        """.trimIndent()

        val rules = RuleSerializer.importText(text, defaultAction = RuleAction.PROXY)
        assertEquals(5, rules.size)
        assertEquals(RuleAction.DIRECT to RuleMatchType.DOMAIN_SUFFIX, rules[0].action to rules[0].matchType)
        assertEquals("gosuslugi.ru", rules[0].value)
        assertEquals(RuleAction.BLOCK to RuleMatchType.DOMAIN_KEYWORD, rules[1].action to rules[1].matchType)
        assertEquals(RuleAction.DIRECT to RuleMatchType.IP_CIDR, rules[2].action to rules[2].matchType)
        assertEquals(RuleAction.PROXY to RuleMatchType.DOMAIN_FULL, rules[3].action to rules[3].matchType)
        assertEquals(RuleAction.PROXY to RuleMatchType.PORT, rules[4].action to rules[4].matchType)

        val json = RuleSerializer.exportJson(rules)
        val restored = RuleSerializer.importJson(json).getOrThrow()
        assertEquals(rules.size, restored.size)
        assertEquals(rules.first().value, restored.first().value)
    }

    @Test
    fun coreConfigJsonMatchesRustSchemaFields() {
        val profile = ProxyProfile(
            id = "p1",
            name = "Test SOCKS5",
            protocol = ProxyProtocol.SOCKS5,
            host = "10.0.0.5",
            port = 1080,
            username = "u",
            password = "p",
        )
        val settings = AppSettings(
            activeProfileId = "p1",
            appRoutingMode = AppRoutingMode.BYPASS_SELECTED,
            selectedPackages = setOf("ru.sberbankmobile"),
            defaultAction = RuleAction.PROXY,
            bypassLan = true,
            useCustomDns = true,
            dnsUpstream = "1.1.1.1",
            dnsFallback = "8.8.8.8",
            routeDns = true,
        )
        val rules = listOf(
            RoutingRule(
                id = "r1",
                name = "Direct RU",
                matchType = RuleMatchType.DOMAIN_SUFFIX,
                value = "ru",
                action = RuleAction.DIRECT,
                enabled = true,
            ),
        )

        val encoded = encodeCoreConfigJson(profile, settings, rules)
        assertNotNull(encoded)
        assertTrue(encoded.contains(""""protocol":"socks5""""))
        assertTrue(encoded.contains(""""upstream":"1.1.1.1:53""""))
        assertTrue(encoded.contains(""""intercept_dns":true"""))
        assertTrue(encoded.contains(""""default_action":"PROXY""""))
        assertTrue(encoded.contains(""""bypass_lan":true"""))
        assertTrue(encoded.contains(""""match_type":"domain_suffix""""))
    }
}
