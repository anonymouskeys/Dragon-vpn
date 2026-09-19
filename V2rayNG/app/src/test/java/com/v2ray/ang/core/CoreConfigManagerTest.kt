package com.v2ray.ang.core

import com.v2ray.ang.dto.V2rayConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoreConfigManagerTest {

    @Test
    fun applyDialerProxy_migratesLegacyProxySettingsToSockopt() {
        val outbound = V2rayConfig.OutboundBean(
            protocol = "vless",
            streamSettings = V2rayConfig.OutboundBean.StreamSettingsBean(),
            proxySettings = V2rayConfig.OutboundBean.ProxySettingsBean("legacy", true),
        )

        CoreConfigManager.applyDialerProxy(outbound, "byedpi-local")

        assertEquals("byedpi-local", outbound.streamSettings?.sockopt?.dialerProxy)
        assertNull(outbound.proxySettings)
    }
}
