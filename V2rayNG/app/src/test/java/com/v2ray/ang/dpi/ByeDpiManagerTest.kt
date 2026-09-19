package com.v2ray.ang.dpi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ByeDpiManagerTest {

    @Test
    fun defaultAutoPresetUsesAndroidCompatibleDisorder() {
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

        assertEquals(listOf("--disorder", "1"), args)
        assertFalse(args.contains("3+s"))
    }
}
