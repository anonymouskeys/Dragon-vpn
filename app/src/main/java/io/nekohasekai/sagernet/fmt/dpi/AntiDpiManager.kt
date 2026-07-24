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
    private val lengthRangePattern = Regex("^[1-9][0-9]*(-[1-9][0-9]*)?$")
    private val intervalRangePattern = Regex("^(0|[1-9][0-9]*(ms|s|m))(-(0|[1-9][0-9]*(ms|s|m)))?$")

    /**
     * Returns a normalized sing-box duration, or null when the input is invalid.
     */
    fun normalizeFallbackDelay(value: String): String? {
        val normalized = value.trim().lowercase()
        return normalized.takeIf(durationPattern::matches)
    }

    fun normalizeLengthRange(value: String): String? {
        val normalized = value.trim().replace(" ", "")
        if (!lengthRangePattern.matches(normalized)) return null
        val values = normalized.split("-").map(String::toInt)
        return normalized.takeIf { values.size == 1 || values[0] <= values[1] }
    }

    fun normalizeIntervalRange(value: String): String? {
        val normalized = value.trim().lowercase().replace(" ", "")
        if (!intervalRangePattern.matches(normalized)) return null
        return normalized
    }

    fun apply(config: MutableMap<String, Any?>) {
        val fallbackDelay = normalizeFallbackDelay(DataStore.antiDpiFragmentFallbackDelay)
            ?: "500ms"
        val fragmentLength = normalizeLengthRange(DataStore.antiDpiFragmentLength) ?: "1-10"
        val fragmentInterval = normalizeIntervalRange(DataStore.antiDpiFragmentInterval) ?: "0-5ms"
        applyRecursively(config, fallbackDelay, fragmentLength, fragmentInterval)
    }

    private fun applyRecursively(value: Any?, fallbackDelay: String, fragmentLength: String, fragmentInterval: String) {
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
                    tlsMap["fragment_length"] = fragmentLength
                    tlsMap["fragment_interval"] = fragmentInterval
                }

                map.values.toList().forEach { child ->
                    applyRecursively(child, fallbackDelay, fragmentLength, fragmentInterval)
                }
            }

            is Iterable<*> -> value.forEach { child ->
                applyRecursively(child, fallbackDelay, fragmentLength, fragmentInterval)
            }

            is Array<*> -> value.forEach { child ->
                applyRecursively(child, fallbackDelay, fragmentLength, fragmentInterval)
            }
        }
    }
}
