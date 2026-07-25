package io.nekohasekai.sagernet.xray

import android.content.Context
import go.Seq
import libv2ray.Libv2ray
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal, side-by-side bridge for AndroidLibXrayLite.
 *
 * Stage 1 deliberately does not replace the running sing-box instance. It makes the Xray AAR a
 * first-class build dependency and provides a single initialization point for the next migration
 * stages. Keeping startup behind this bridge prevents Xray-specific calls from leaking into the
 * existing DragonVPN UI and service code.
 */
object XrayCoreBridge {
    private val initialized = AtomicBoolean(false)

    @JvmStatic
    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return

        try {
            val applicationContext = context.applicationContext
            Seq.setContext(applicationContext)

            val assetDirectory = File(applicationContext.filesDir, "xray-assets").apply {
                mkdirs()
            }

            // The key only needs to be stable for this installation. AndroidLibXrayLite uses it as
            // the XUDP base key; it is not a profile credential and never leaves the device.
            val xudpBaseKey = "dragonvpn:${applicationContext.packageName}"
            Libv2ray.initCoreEnv(assetDirectory.absolutePath, xudpBaseKey)
        } catch (error: Throwable) {
            initialized.set(false)
            throw IllegalStateException("Unable to initialize AndroidLibXrayLite", error)
        }
    }

    @JvmStatic
    fun version(context: Context): String {
        initialize(context)
        return Libv2ray.checkVersionX()
    }
}
