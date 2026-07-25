package io.nekohasekai.sagernet.fmt.dpi

import io.nekohasekai.sagernet.database.DataStore

/**
 * Applies user-controlled Anti-DPI options to generated sing-box TLS objects.
 *
 * Empty custom values are deliberately preserved as "not configured". In that
 * case Dragon-core keeps its native SNI-aware fragmentation behavior instead
 * of silently forcing a country-specific or hard-coded preset.
 */
object AntiDpiManager {

    private const val MAX_FRAGMENT_LENGTH = 65535
    private val lengthRangePattern = Regex("^[1-9][0-9]*(-[1-9][0-9]*)?$")
    private val durationTokenPattern = Regex("^(0|[0-9]+(?:\\.[0-9]+)?(?:ns|us|µs|ms|s|m|h))$")

    fun normalizeFallbackDelay(value: String): String? {
        if (value.isBlank()) return ""
        val normalized = normalizeDurationToken(value)
        return normalized?.takeIf { it != "0" }
    }

    /** Empty means: use Dragon-core's native SNI-aware split positions. */
    fun normalizeLengthRange(value: String): String? {
        val normalized = value.trim().replace(" ", "")
        if (normalized.isEmpty()) return ""
        if (!lengthRangePattern.matches(normalized)) return null
        val values = normalized.split("-").map(String::toIntOrNull)
        if (values.any { it == null }) return null
        val minimum = values[0]!!
        val maximum = if (values.size == 1) minimum else values[1]!!
        if (minimum > maximum || maximum > MAX_FRAGMENT_LENGTH) return null
        return normalized
    }

    /** Empty means: use fragment_fallback_delay between packet fragments. */
    fun normalizeIntervalRange(value: String): String? {
        val normalized = value.trim().lowercase().replace(" ", "")
        if (normalized.isEmpty()) return ""
        val parts = normalized.split("-", limit = 2)
        val minimum = normalizeDurationToken(parts[0]) ?: return null
        val maximum = normalizeDurationToken(parts.getOrElse(1) { parts[0] }) ?: return null
        if (durationToNanoseconds(minimum) > durationToNanoseconds(maximum)) return null
        return if (parts.size == 1) minimum else "$minimum-$maximum"
    }

    private fun normalizeDurationToken(value: String): String? {
        val normalized = value.trim().lowercase().replace(" ", "")
        return normalized.takeIf(durationTokenPattern::matches)
    }

    private fun durationToNanoseconds(value: String): Double {
        if (value == "0") return 0.0
        val unit = listOf("ns", "us", "µs", "ms", "s", "m", "h")
            .first { value.endsWith(it) }
        val number = value.removeSuffix(unit).toDouble()
        val multiplier = when (unit) {
            "ns" -> 1.0
            "us", "µs" -> 1_000.0
            "ms" -> 1_000_000.0
            "s" -> 1_000_000_000.0
            "m" -> 60_000_000_000.0
            "h" -> 3_600_000_000_000.0
            else -> error("Unsupported duration unit")
        }
        return number * multiplier
    }

    fun apply(config: MutableMap<String, Any?>) {
        if (!DataStore.antiDpiTlsFragment) return

        val fallbackDelay = normalizeFallbackDelay(DataStore.antiDpiFragmentFallbackDelay)
            ?.takeIf(String::isNotEmpty)
        val fragmentLength = normalizeLengthRange(DataStore.antiDpiFragmentLength)
        val fragmentInterval = normalizeIntervalRange(DataStore.antiDpiFragmentInterval)

        applyRecursively(config, fallbackDelay, fragmentLength, fragmentInterval)
    }

    private fun applyRecursively(
        value: Any?,
        fallbackDelay: String?,
        fragmentLength: String?,
        fragmentInterval: String?,
    ) {
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
                    putOrRemove(tlsMap, "fragment_fallback_delay", fallbackDelay)
                    putOrRemove(tlsMap, "fragment_length", fragmentLength?.takeIf(String::isNotEmpty))
                    putOrRemove(tlsMap, "fragment_interval", fragmentInterval?.takeIf(String::isNotEmpty))
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

    private fun putOrRemove(map: MutableMap<String, Any?>, key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}
