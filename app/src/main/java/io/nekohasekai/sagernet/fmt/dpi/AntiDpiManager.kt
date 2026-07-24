package io.nekohasekai.sagernet.fmt.dpi

import io.nekohasekai.sagernet.database.DataStore

/**
 * Applies the Anti-DPI options to generated sing-box TLS objects.
 *
 * The configuration tree is modified structurally; no regular-expression or
 * string replacement is used.
 */
object AntiDpiManager {

    private val durationPattern = Regex("^[1-9][0-9]*(ms|s|m)$")

    /**
     * Returns a normalized sing-box duration, or null when the input is invalid.
     */
    fun normalizeFallbackDelay(value: String): String? {
        val normalized = value.trim().lowercase()
        return normalized.takeIf(durationPattern::matches)
    }

    fun apply(config: MutableMap<String, Any?>) {
        val fallbackDelay = normalizeFallbackDelay(DataStore.antiDpiFragmentFallbackDelay)
            ?: "500ms"
        applyRecursively(config, fallbackDelay)
    }

    private fun applyRecursively(value: Any?, fallbackDelay: String) {
        when (value) {
            is MutableMap<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = value as MutableMap<String, Any?>

                val tls = map["tls"]
                if (tls is MutableMap<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val tlsMap = tls as MutableMap<String, Any?>
                    tlsMap["fragment"] = true
                    tlsMap["record_fragment"] = DataStore.antiDpiTlsRecordFragment
                    tlsMap["fragment_fallback_delay"] = fallbackDelay
                }

                map.values.toList().forEach { child ->
                    applyRecursively(child, fallbackDelay)
                }
            }

            is Iterable<*> -> value.forEach { child ->
                applyRecursively(child, fallbackDelay)
            }

            is Array<*> -> value.forEach { child ->
                applyRecursively(child, fallbackDelay)
            }
        }
    }
}
