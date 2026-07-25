package io.nekohasekai.sagernet.fmt.dpi

import io.nekohasekai.sagernet.database.DataStore

/** Applies user-controlled fragmentation options to generated Dragon-core TLS objects. */
object AntiDpiManager {

    const val PACKETS_TLSHELLO = "tlshello"
    const val PACKETS_TLSRECORD = "tlsrecord"
    const val PACKETS_MIXED = "mixed"

    private const val MAX_FRAGMENT_LENGTH = 65535
    private val lengthRangePattern = Regex("^[1-9][0-9]*(-[1-9][0-9]*)?$")
    private val bareNumberPattern = Regex("^[0-9]+(?:\\.[0-9]+)?$")
    private val durationTokenPattern = Regex("^(0|[0-9]+(?:\\.[0-9]+)?(?:ns|us|µs|ms|s|m|h))$")

    fun normalizePackets(value: String): String? = when (value.trim().lowercase()) {
        PACKETS_TLSHELLO, PACKETS_TLSRECORD, PACKETS_MIXED -> value.trim().lowercase()
        else -> null
    }

    fun normalizeFallbackDelay(value: String): String? {
        if (value.isBlank()) return ""
        return normalizeDurationToken(value, allowBareMilliseconds = true)?.takeIf { it != "0" }
    }

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

    /**
     * Accepts both Go durations (0-5ms) and the familiar Neko syntax (0-1).
     * Unitless values are interpreted as milliseconds only when emitted to Dragon-core.
     */
    fun normalizeIntervalRange(value: String): String? {
        val normalized = value.trim().lowercase().replace(" ", "")
        if (normalized.isEmpty()) return ""
        val parts = normalized.split("-", limit = 2)
        if (parts.any { normalizeDurationToken(it, allowBareMilliseconds = true) == null }) return null
        val minimum = toCoreDuration(parts[0]) ?: return null
        val maximum = toCoreDuration(parts.getOrElse(1) { parts[0] }) ?: return null
        if (durationToNanoseconds(minimum) > durationToNanoseconds(maximum)) return null
        return normalized
    }

    private fun normalizeDurationToken(value: String, allowBareMilliseconds: Boolean): String? {
        val normalized = value.trim().lowercase().replace(" ", "")
        if (durationTokenPattern.matches(normalized)) return normalized
        return normalized.takeIf { allowBareMilliseconds && bareNumberPattern.matches(it) }
    }

    private fun toCoreDuration(value: String): String? {
        val normalized = normalizeDurationToken(value, allowBareMilliseconds = true) ?: return null
        if (normalized == "0") return "0"
        return if (bareNumberPattern.matches(normalized)) "${normalized}ms" else normalized
    }

    private fun intervalToCore(value: String): String? {
        val normalized = normalizeIntervalRange(value) ?: return null
        if (normalized.isEmpty()) return ""
        val parts = normalized.split("-", limit = 2)
        val minimum = toCoreDuration(parts[0]) ?: return null
        val maximum = toCoreDuration(parts.getOrElse(1) { parts[0] }) ?: return null
        return if (parts.size == 1) minimum else "$minimum-$maximum"
    }

    private fun durationToNanoseconds(value: String): Double {
        if (value == "0") return 0.0
        val unit = listOf("ns", "us", "µs", "ms", "s", "m", "h").first { value.endsWith(it) }
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
        if (!DataStore.antiDpiTlsFragment &&
            !DataStore.antiDpiTlsRecordFragment) return

        val packets = normalizePackets(DataStore.antiDpiFragmentPackets) ?: PACKETS_TLSHELLO
        val fallbackDelay = normalizeFallbackDelay(DataStore.antiDpiFragmentFallbackDelay)
            ?.let(::toCoreDuration)?.takeIf(String::isNotEmpty)
        val fragmentLength = normalizeLengthRange(DataStore.antiDpiFragmentLength)
            ?.takeIf(String::isNotEmpty)
        val fragmentInterval = intervalToCore(DataStore.antiDpiFragmentInterval)
            ?.takeIf(String::isNotEmpty)

        applyRecursively(config, packets, fallbackDelay, fragmentLength, fragmentInterval)
    }

    private fun applyRecursively(
        value: Any?,
        packets: String,
        fallbackDelay: String?,
        fragmentLength: String?,
        fragmentInterval: String?,
    ) {
        when (value) {
            is MutableMap<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = value as MutableMap<String, Any?>
                val tls = map["tls"]
                if (tls is Map<*, *>) {
                    val tlsMap = tls.entries.associate {
                        it.key.toString() to it.value
                    }.toMutableMap()
                    tlsMap["fragment"] = packets != PACKETS_TLSRECORD
                    tlsMap["record_fragment"] = packets != PACKETS_TLSHELLO
                    putOrRemove(tlsMap, "fragment_fallback_delay", fallbackDelay)
                    putOrRemove(tlsMap, "fragment_length", fragmentLength)
                    putOrRemove(tlsMap, "fragment_interval", fragmentInterval)
                    map["tls"] = tlsMap
                }
                map.values.toList().forEach { child ->
                    applyRecursively(child, packets, fallbackDelay, fragmentLength, fragmentInterval)
                }
            }
            is Iterable<*> -> value.forEach { child ->
                applyRecursively(child, packets, fallbackDelay, fragmentLength, fragmentInterval)
            }
            is Array<*> -> value.forEach { child ->
                applyRecursively(child, packets, fallbackDelay, fragmentLength, fragmentInterval)
            }
        }
    }

    private fun putOrRemove(map: MutableMap<String, Any?>, key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}
