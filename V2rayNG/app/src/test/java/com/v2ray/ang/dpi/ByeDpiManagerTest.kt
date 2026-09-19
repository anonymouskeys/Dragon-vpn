package com.v2ray.ang.dpi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ByeDpiManagerTest {

    @Test
    fun defaultAutoPresetUsesAndroidSafeFallbackCascade() {
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

        assertEquals(listOf("--disorder", "1"), args.take(2))
        assertFalse(args.contains("--fake"))
        assertTrue(args.contains("--auto=torst"))
        assertTrue(args.contains("--auto=ssl_err"))
        assertTrue(args.contains("--tlsrec"))
    }

    @Test
    fun maximumPresetRemainsSeparateFromAutomaticPreset() {
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

        assertFalse(automaticArgs == maximumArgs)
        assertFalse(automaticArgs.contains("--fake"))
        assertTrue(maximumArgs.contains("--fake"))
    }
}
