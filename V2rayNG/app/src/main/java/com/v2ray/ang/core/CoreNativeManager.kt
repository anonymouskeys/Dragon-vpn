package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V2Ray Native Library Manager
 *
 * Thread-safe singleton wrapper for Libv2ray native methods.
 * Provides initialization protection and unified API for V2Ray core operations.
 */
object CoreNativeManager {
    private val initialized = AtomicBoolean(false)

    /**
     * Initialize V2Ray core environment.
     * This method is thread-safe and ensures initialization happens only once.
     * Subsequent calls will be ignored silently.
     *
     */
    fun initCoreEnv(context: Context?) {
        if (initialized.compareAndSet(false, true)) {
            try {
                Seq.setContext(context?.applicationContext)
                val assetPath = Utils.userAssetPath(context)
                val deviceId = Utils.getDeviceIdForXUDPBaseKey()
                Libv2ray.initCoreEnv(assetPath, deviceId)
                LogUtil.i(AppConfig.TAG, "V2Ray core environment initialized successfully")
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to initialize V2Ray core environment", e)
                initialized.set(false)
                throw e
            }
        } else {
            LogUtil.d(AppConfig.TAG, "V2Ray core environment already initialized, skipping")
        }
    }

    fun reconcileBrowserDialer(dialerAddr: String) {
        try {
            Libv2ray.reconcileBrowserDialer(dialerAddr)
            LogUtil.i(AppConfig.TAG, "Browser dialer reconciled successfully with address: $dialerAddr")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to reconcile browser dialer with address: $dialerAddr", e)
        }
    }


    /**
     * Get V2Ray core version.
     *
     * @return Version string of the V2Ray core
     */
    fun getLibVersion(): String {
        return try {
            Libv2ray.checkVersionX()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to check V2Ray version", e)
            "Unknown"
        }
    }

    /**
     * Measure outbound connection delay.
     *
     * @param config The configuration JSON string
     * @param testUrl The URL to test against
     * @return Delay in milliseconds, or -1 if test failed
     */
    data class DelayMeasurement(
        val delayMillis: Long,
        val reason: String = "",
        val isTransient: Boolean = false
    )

    fun measureOutboundDelay(config: String, testUrl: String): Long {
        return measureOutboundDelayDetailed(config, testUrl).delayMillis
    }

    /**
     * Same native measurement with failure classification for the batch scheduler.
     * Permanent TLS/configuration failures are not retried; short-lived transport shutdowns and
     * timeouts are retried with reduced concurrency.
     */
    fun measureOutboundDelayDetailed(config: String, testUrl: String): DelayMeasurement {
        return try {
            DelayMeasurement(Libv2ray.measureOutboundDelay(config, testUrl))
        } catch (e: Exception) {
            val reason = e.message?.lineSequence()?.firstOrNull().orEmpty().ifBlank { e.javaClass.simpleName }
            val normalized = reason.lowercase()
            val transient = normalized.contains("closed pipe")
                || normalized.contains("eof")
                || normalized.contains("timeout")
                || normalized.contains("deadline exceeded")
                || normalized.contains("context canceled")
                || normalized.contains("connection reset")
                || normalized.contains("temporarily unavailable")
            LogUtil.w(AppConfig.TAG, "Outbound delay test failed: $reason")
            DelayMeasurement(-1L, reason, transient)
        }
    }

    /**
     * Create a new core controller instance.
     *
     * @param handler The callback handler for core events
     * @return A new CoreController instance
     */
    fun newCoreController(handler: CoreCallbackHandler): CoreController {
        return try {
            Libv2ray.newCoreController(handler)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to create core controller", e)
            throw e
        }
    }
}