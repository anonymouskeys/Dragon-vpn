package com.v2ray.ang.dto

import java.io.Serializable

data class TestServiceMessage(
    val key: Int,
    val subscriptionId: String = "",
    val serverGuids: List<String> = emptyList(),
    val testMode: String = TEST_MODE_HANDSHAKE
) : Serializable {
    companion object {
        const val TEST_MODE_SMART = "SMART"
        const val TEST_MODE_TCP = "TCP"
        const val TEST_MODE_HANDSHAKE = "HANDSHAKE"
    }
}

