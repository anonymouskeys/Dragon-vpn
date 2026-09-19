package com.v2ray.ang.dpi

import org.junit.Assert.assertEquals
import org.junit.Test

class ByeDpiManagerTest {
    @Test
    fun bothAutoAliasesMatchMaximumWithDefaultAndCustomSettings() {
        for (ttl in listOf(1, 8, 32)) {
            for (portsOnly in listOf(false, true)) {
                val maximum = ByeDpiSettings(
                    enabled = true,
                    strategy = "auto_aggressive",
                    splitPosition = "1+s",
                    fakeTtl = ttl,
                    fakeCount = 1,
                    delayMs = 0,
                    ports80And443Only = portsOnly,
                    expertArgs = "",
                )
                for (alias in listOf("auto", "auto_balanced")) {
                    assertEquals(
                        "Auto must retain every Maximum option and its order",
                        ByeDpiManager.presetArguments(maximum),
                        ByeDpiManager.presetArguments(maximum.copy(strategy = alias)),
                    )
                }
            }
        }
    }
}
