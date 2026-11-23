package dev.kestrel.proxy.data.util

import dev.kestrel.proxy.data.model.RoutingRule
import dev.kestrel.proxy.data.model.RuleAction
import dev.kestrel.proxy.data.model.RuleMatchType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object RuleSerializer {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val ipv4OrCidrRegex = Regex("""^\d{1,3}(\.\d{1,3}){3}(/\d{1,2})?$""")
    private val portRegex = Regex("""^\d{1,5}$""")

    fun exportJson(rules: List<RoutingRule>): String = json.encodeToString(rules)

    fun importJson(raw: String): Result<List<RoutingRule>> = runCatching {
        json.decodeFromString<List<RoutingRule>>(raw.trim())
    }

    fun exportText(rules: List<RoutingRule>): String = buildString {
        appendLine("# Kestrel routing rules")
        appendLine("# Format: ACTION [full:|domain:|keyword:|ip:|port:]VALUE")
        for (rule in rules) {
            if (!rule.enabled) {
                append("# disabled: ")
            }
            append(rule.action.name)
            append(' ')
            append(rule.matchType.prefix)
            appendLine(rule.value.trim())
        }
    }

    fun importText(
        raw: String,
        defaultAction: RuleAction = RuleAction.DIRECT,
    ): List<RoutingRule> {
        val parsed = mutableListOf<RoutingRule>()
        for (rawLine in raw.lineSequence()) {
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) continue

            val tokens = line.split(Regex("""\s+"""), limit = 2)
            val (action, expr) = if (tokens.size == 2) {
                when (tokens[0].uppercase()) {
                    "PROXY" -> RuleAction.PROXY to tokens[1].trim()
                    "DIRECT" -> RuleAction.DIRECT to tokens[1].trim()
                    "BLOCK" -> RuleAction.BLOCK to tokens[1].trim()
                    else -> defaultAction to line
                }
            } else {
                defaultAction to line
            }

            val (matchType, value) = parsePattern(expr)
            if (value.isNotEmpty()) {
                parsed += RoutingRule(
                    name = value,
                    matchType = matchType,
                    value = value,
                    action = action,
                    enabled = true,
                )
            }
        }
        return parsed
    }

    private fun parsePattern(expr: String): Pair<RuleMatchType, String> {
        val lower = expr.lowercase()
        return when {
            lower.startsWith("full:") -> RuleMatchType.DOMAIN_FULL to expr.drop(5).trim()
            lower.startsWith("domain:") -> RuleMatchType.DOMAIN_SUFFIX to expr.drop(7).trim()
            lower.startsWith("domain-suffix:") -> RuleMatchType.DOMAIN_SUFFIX to expr.drop(14).trim()
            lower.startsWith("keyword:") -> RuleMatchType.DOMAIN_KEYWORD to expr.drop(8).trim()
            lower.startsWith("ip:") -> RuleMatchType.IP_CIDR to expr.drop(3).trim()
            lower.startsWith("ip-cidr:") -> RuleMatchType.IP_CIDR to expr.drop(8).trim()
            lower.startsWith("port:") -> RuleMatchType.PORT to expr.drop(5).trim()
            ipv4OrCidrRegex.matches(expr) || (expr.contains(':') && !expr.startsWith("http")) ->
                RuleMatchType.IP_CIDR to expr.trim()
            portRegex.matches(expr) && (expr.toIntOrNull() in 1..65535) ->
                RuleMatchType.PORT to expr.trim()
            else -> RuleMatchType.DOMAIN_SUFFIX to expr.trim().removePrefix("*.")
        }
    }
}
