package com.v2ray.ang.service

import android.content.Context
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dpi.ByeDpiManager
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * Runs one cancellable batch of real-ping tests.
 *
 * DPI testing uses a two-stage scheduler:
 * 1. a fast parallel pass for every profile;
 * 2. a narrow retry pass only for temporary transport failures.
 *
 * Retrying temporary failures immediately in the same busy pool caused a feedback loop: every
 * timeout created another native Xray instance while the first one was still closing, producing
 * EOF/closed-pipe storms and overwriting previously valid delays with -1. The second stage lets
 * the native runtime drain before rechecking doubtful profiles at low concurrency.
 */
class RealPingWorkerService(
    private val context: Context,
    private val guids: List<String>,
    private val testMode: String,
    private val onEvent: (RealPingEvent) -> Unit = {}
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO + CoroutineName("RealPingBatchWorker"))
    private val completedCount = AtomicInteger(0)

    private data class PingResult(
        val guid: String,
        val delayMillis: Long,
        val transientFailure: Boolean
    )

    fun start() {
        scope.launch {
            try {
                runBatch()
                onEvent(RealPingEvent.Finish("0"))
            } catch (_: CancellationException) {
                onEvent(RealPingEvent.Finish("-1"))
            }
        }
    }

    fun cancel() {
        job.cancel()
    }

    private suspend fun runBatch() = coroutineScope {
        if (guids.isEmpty()) return@coroutineScope

        val dpiRunning = testMode == TestServiceMessage.TEST_MODE_HANDSHAKE && ByeDpiManager.isRunning()
        val configuredConcurrency = SettingsManager.getRealPingConcurrency()
        val concurrency = when (testMode) {
            TestServiceMessage.TEST_MODE_TCP -> configuredConcurrency.coerceIn(24, 64)
            else -> if (dpiRunning) configuredConcurrency.coerceIn(8, 12) else configuredConcurrency.coerceIn(8, 32)
        }.coerceAtMost(guids.size)

        if (dpiRunning) delay(180L)

        val nextIndex = AtomicInteger(0)
        repeat(concurrency) { workerIndex ->
            launch {
                if (dpiRunning && workerIndex > 0) {
                    delay((workerIndex * 25L).coerceAtMost(220L))
                }
                while (true) {
                    coroutineContext.ensureActive()
                    val index = nextIndex.getAndIncrement()
                    if (index >= guids.size) break

                    val guid = guids[index]
                    val result = if (testMode == TestServiceMessage.TEST_MODE_TCP) {
                        testTcpProfile(guid)
                    } else {
                        testHandshakeProfile(guid, dpiRunning)
                    }
                    publishFinal(result)
                }
            }
        }
    }

    private fun testTcpProfile(guid: String): PingResult {
        val failed = PingResult(guid, -1L, transientFailure = false)
        val config = MmkvManager.decodeServerConfig(guid) ?: return failed
        if (config.configType.isComplexType()
            || config.configType == EConfigType.HYSTERIA2
            || config.configType == EConfigType.WIREGUARD
            || config.alpn?.startsWith("h3") == true
            || !config.server.isNotNullEmpty()
        ) return failed

        val port = config.serverPort?.toIntOrNull() ?: return failed
        val delay = SpeedtestManager.socketConnectTime(config.server.orEmpty(), port, TCP_TIMEOUT_MS)
        return PingResult(guid, delay, transientFailure = false)
    }

    private suspend fun testHandshakeProfile(guid: String, dpiRunning: Boolean): PingResult {
        val failed = PingResult(guid, -1L, transientFailure = false)
        val config = MmkvManager.decodeServerConfig(guid) ?: return failed

        // This is one definitive protocol attempt. Optional TCP pre-filtering belongs only to
        // Smart Test; standalone Handshake intentionally checks every selected profile.
        val configResult = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!configResult.status) return failed

        coroutineContext.ensureActive()
        val measurement = CoreNativeManager.measureOutboundDelayDetailed(
            configResult.content,
            SettingsManager.getDelayTestUrl()
        )
        return PingResult(
            guid = guid,
            delayMillis = measurement.delayMillis,
            transientFailure = false
        )
    }

    companion object {
        private const val TCP_TIMEOUT_MS = 1200

        /** Profiles without a plain TCP endpoint must bypass Smart Test's TCP stage. */
        fun supportsTcpPrecheck(guid: String): Boolean {
            val config = MmkvManager.decodeServerConfig(guid) ?: return false
            return !config.configType.isComplexType()
                && config.configType != EConfigType.HYSTERIA2
                && config.configType != EConfigType.WIREGUARD
                && config.alpn?.startsWith("h3") != true
                && config.server.isNotNullEmpty()
                && config.serverPort?.toIntOrNull() != null
        }
    }

    private fun publishFinal(result: PingResult) {
        onEvent(
            RealPingEvent.Result(
                guid = result.guid,
                delayMillis = result.delayMillis,
                transientFailure = result.transientFailure
            )
        )
        val done = completedCount.incrementAndGet()
        onEvent(RealPingEvent.Progress("$done / ${guids.size}"))
    }


}
