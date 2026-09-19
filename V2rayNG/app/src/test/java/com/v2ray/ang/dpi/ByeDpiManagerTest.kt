package com.v2ray.ang.dpi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ByeDpiManagerTest {

    @Test
    fun defaultAutoPresetUsesStableWebSocketSafeStrategy() {
        val settings = ByeDpiSettings(
            enabled = true,
            strategy = "auto",
            splitPosition = "1+s",
            fakeTtl = 8,
            fakeCount = 1,
            delayMs = 0,
            ports80And443Only = true,
            expertArgs = "",
        )

        val args = ByeDpiManager.presetArguments(settings)

        assertEquals(listOf("--disorder", "1", "--fake", "-1"), args)
        assertFalse(args.any { it.startsWith("--auto=") })
    }

    @Test
    fun maximumPresetKeepsFallbackCascadeSeparateFromAuto() {
        val automatic = ByeDpiSettings(
            enabled = true,
            strategy = "auto_balanced",
            splitPosition = "1+s",
            fakeTtl = 8,
            fakeCount = 1,
            delayMs = 0,
            ports80And443Only = false,
            expertArgs = "",
        )

        val maximum = automatic.copy(strategy = "auto_aggressive")

        val automaticArgs = ByeDpiManager.presetArguments(automatic)
        val maximumArgs = ByeDpiManager.presetArguments(maximum)

        assertEquals(automaticArgs, maximumArgs.take(automaticArgs.size))
        assertFalse(automaticArgs.any { it.startsWith("--auto=") })
        assertFalse(maximumArgs == automaticArgs)
    }
}
