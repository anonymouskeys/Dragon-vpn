package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.RulesetItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsManagerTest {

    @Test
    fun recognizesOnlyGeneratedUdp443BlockRule() {
        val generated = RulesetItem(
            remarks = "Block udp443",
            outboundTag = AppConfig.TAG_BLOCKED,
            port = "443",
            network = "udp",
        )

        assertTrue(SettingsManager.isLegacyUdp443Block(generated))
        assertFalse(SettingsManager.isLegacyUdp443Block(generated.copy(remarks = "My explicit rule")))
        assertFalse(SettingsManager.isLegacyUdp443Block(generated.copy(network = "tcp")))
        assertFalse(SettingsManager.isLegacyUdp443Block(generated.copy(port = "8443")))
    }
}
