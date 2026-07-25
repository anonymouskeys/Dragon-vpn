package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.Hysteria2Fmt
import com.v2ray.ang.fmt.HttpFmt
import com.v2ray.ang.fmt.ShadowsocksFmt
import com.v2ray.ang.fmt.SocksFmt
import com.v2ray.ang.fmt.TrojanFmt
import com.v2ray.ang.fmt.VlessFmt
import com.v2ray.ang.fmt.VmessFmt
import com.v2ray.ang.util.Utils

/**
 * Normalizes subscription responses and reads the most common Clash YAML proxy
 * entries without adding a heavy YAML dependency to the Android application.
 */
object SubscriptionContentParser {
    fun normalize(text: String?): String {
        if (text == null) return ""
        return text
            .removePrefix("\uFEFF")
            .replace("&amp;", "&")
            .replace("&#38;", "&")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }

    fun parseClash(text: String): List<ProfileItem> {
        if (!Regex("(?m)^\\s*proxies\\s*:").containsMatchIn(text)) return emptyList()

        val result = mutableListOf<ProfileItem>()
        val blocks = splitProxyBlocks(text)
        blocks.forEach { block ->
            parseBlock(block)?.let(result::add)
        }
        return result
    }

    private fun splitProxyBlocks(text: String): List<Map<String, String>> {
        val result = mutableListOf<Map<String, String>>()
        var insideProxies = false
        var current = linkedMapOf<String, String>()

        fun flush() {
            if (current.isNotEmpty()) result.add(current)
            current = linkedMapOf()
        }

        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore(" #").trimEnd()
            if (!insideProxies) {
                if (line.trim() == "proxies:") insideProxies = true
                return@forEach
            }
            if (line.isNotBlank() && !line.startsWith(" ") && !line.startsWith("-")) {
                flush()
                insideProxies = false
                return@forEach
            }

            val trimmed = line.trim()
            if (trimmed.startsWith("- {") && trimmed.endsWith("}")) {
                flush()
                result.add(parseInlineMap(trimmed.removePrefix("-").trim()))
                return@forEach
            }
            if (trimmed.startsWith("- ")) {
                flush()
                parseKeyValue(trimmed.removePrefix("- ").trim())?.let { current[it.first] = it.second }
            } else {
                parseKeyValue(trimmed)?.let { current[it.first] = it.second }
            }
        }
        flush()
        return result
    }

    private fun parseInlineMap(value: String): Map<String, String> {
        val body = value.removePrefix("{").removeSuffix("}")
        val map = linkedMapOf<String, String>()
        var quoted = false
        var quote = '\u0000'
        val token = StringBuilder()
        val tokens = mutableListOf<String>()
        body.forEach { ch ->
            when {
                quoted && ch == quote -> {
                    quoted = false
                    token.append(ch)
                }
                !quoted && (ch == '\'' || ch == '"') -> {
                    quoted = true
                    quote = ch
                    token.append(ch)
                }
                !quoted && ch == ',' -> {
                    tokens.add(token.toString())
                    token.clear()
                }
                else -> token.append(ch)
            }
        }
        tokens.add(token.toString())
        tokens.forEach { parseKeyValue(it.trim())?.let { pair -> map[pair.first] = pair.second } }
        return map
    }

    private fun parseKeyValue(line: String): Pair<String, String>? {
        if (line.isBlank() || !line.contains(":")) return null
        val key = line.substringBefore(":").trim()
        val value = unquote(line.substringAfter(":").trim())
        if (key.isEmpty()) return null
        return key to value
    }

    private fun unquote(value: String): String {
        return value.trim().removeSurrounding("\"").removeSurrounding("'")
    }

    private fun bool(value: String?): Boolean =
        value.equals("true", true) || value == "1"

    private fun parseBlock(map: Map<String, String>): ProfileItem? {
        val type = map["type"]?.lowercase() ?: return null
        val server = map["server"] ?: return null
        val port = map["port"]?.toIntOrNull() ?: return null
        val name = map["name"].orEmpty().ifBlank { "$server:$port" }

        return when (type) {
            "http", "https" -> HttpFmt.parse(buildUri(
                scheme = if (type == "https" || bool(map["tls"])) "https" else "http",
                user = map["username"],
                password = map["password"],
                server = server,
                port = port,
                name = name
            ))
            "socks", "socks5" -> SocksFmt.parse(buildUri(
                scheme = "socks5",
                user = map["username"],
                password = map["password"],
                server = server,
                port = port,
                name = name
            ))
            "ss" -> {
                val method = map["cipher"] ?: return null
                val password = map["password"] ?: return null
                ShadowsocksFmt.parse("ss://${Utils.encode("$method:$password", true)}@${hostPort(server, port)}#${Utils.encodeURIComponent(name)}")
            }
            "trojan" -> {
                val password = map["password"] ?: return null
                TrojanFmt.parse("trojan://${Utils.encodeURIComponent(password)}@${hostPort(server, port)}?security=tls&sni=${Utils.encodeURIComponent(map["sni"] ?: map["servername"].orEmpty())}&allowInsecure=${if (bool(map["skip-cert-verify"])) "1" else "0"}#${Utils.encodeURIComponent(name)}")
            }
            "vless" -> {
                val uuid = map["uuid"] ?: return null
                val security = when {
                    map["reality-opts"].orEmpty().isNotBlank() -> "reality"
                    bool(map["tls"]) -> "tls"
                    else -> "none"
                }
                VlessFmt.parse("vless://${Utils.encodeURIComponent(uuid)}@${hostPort(server, port)}?encryption=none&security=$security&type=${map["network"] ?: "tcp"}&sni=${Utils.encodeURIComponent(map["servername"] ?: map["sni"].orEmpty())}&allowInsecure=${if (bool(map["skip-cert-verify"])) "1" else "0"}#${Utils.encodeURIComponent(name)}")
            }
            "vmess" -> {
                val uuid = map["uuid"] ?: return null
                val item = ProfileItem.create(EConfigType.VMESS)
                item.remarks = name
                item.server = server
                item.serverPort = port.toString()
                item.password = uuid
                item.method = map["cipher"] ?: "auto"
                item.network = map["network"] ?: "tcp"
                item.security = if (bool(map["tls"])) AppConfig.TLS else ""
                item.sni = map["servername"] ?: map["sni"]
                item.insecure = bool(map["skip-cert-verify"])
                item.host = map["ws-opts.headers.Host"] ?: map["host"]
                item.path = map["ws-opts.path"] ?: map["path"]
                item
            }
            "hysteria2", "hy2" -> {
                val password = map["password"] ?: map["auth"] ?: return null
                val query = buildList {
                    add("security=tls")
                    (map["sni"] ?: map["servername"])?.let { add("sni=${Utils.encodeURIComponent(it)}") }
                    if (bool(map["skip-cert-verify"])) add("insecure=1")
                    map["obfs-password"]?.let { add("obfs=salamander"); add("obfs-password=${Utils.encodeURIComponent(it)}") }
                }.joinToString("&")
                Hysteria2Fmt.parse("hysteria2://${Utils.encodeURIComponent(password)}@${hostPort(server, port)}?$query#${Utils.encodeURIComponent(name)}")
            }
            else -> null
        }
    }

    private fun buildUri(
        scheme: String,
        user: String?,
        password: String?,
        server: String,
        port: Int,
        name: String
    ): String {
        val auth = if (!user.isNullOrBlank()) {
            "${Utils.encodeURIComponent(user)}:${Utils.encodeURIComponent(password.orEmpty())}@"
        } else ""
        return "$scheme://$auth${hostPort(server, port)}/#${Utils.encodeURIComponent(name)}"
    }

    private fun hostPort(server: String, port: Int): String =
        "${Utils.getIpv6Address(server)}:$port"
}
