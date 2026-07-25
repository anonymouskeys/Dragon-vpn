package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.util.Utils
import java.net.URI

/**
 * Parser for standalone HTTP and HTTPS proxy links.
 *
 * A web URL is treated as a proxy only when it contains an explicit port and
 * has no meaningful path. This keeps ordinary subscription URLs as subscriptions.
 */
object HttpFmt : FmtBase() {
    fun parse(str: String): ProfileItem? {
        val uri = URI(Utils.fixIllegalUrl(str.trim()))
        if (uri.scheme != "http" && uri.scheme != "https") return null
        if (uri.idnHost.isEmpty() || uri.port <= 0) return null
        if (!uri.path.isNullOrEmpty() && uri.path != "/") return null

        val config = ProfileItem.create(EConfigType.HTTP)
        config.remarks = Utils.decodeURIComponent(uri.fragment.orEmpty())
            .ifEmpty { "${uri.idnHost}:${uri.port}" }
        config.server = uri.idnHost
        config.serverPort = uri.port.toString()

        uri.userInfo?.takeIf { it.isNotBlank() }?.let { userInfo ->
            val credentials = userInfo.split(":", limit = 2)
            config.username = Utils.decodeURIComponent(credentials[0])
            if (credentials.size == 2) {
                config.password = Utils.decodeURIComponent(credentials[1])
            }
        }

        if (uri.scheme == "https") {
            config.security = AppConfig.TLS
            config.sni = uri.idnHost.takeIf { Utils.isDomainName(it) }
        }
        return config
    }

    fun toUri(config: ProfileItem): String {
        val scheme = if (config.security == AppConfig.TLS) AppConfig.HTTPS else AppConfig.HTTP
        val credentials = if (config.username.isNotNullEmpty()) {
            "${Utils.encodeURIComponent(config.username.orEmpty())}:${Utils.encodeURIComponent(config.password.orEmpty())}@"
        } else {
            ""
        }
        return buildString {
            append(scheme)
            append(credentials)
            append(Utils.getIpv6Address(config.server))
            append(":")
            append(config.serverPort)
            append("/#")
            append(Utils.encodeURIComponent(config.remarks))
        }
    }
}
