package com.v2ray.ang.dpi

import android.content.Context
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Runtime wrapper around the official ciadpi local SOCKS proxy. */
data class ByeDpiSettings(
    val enabled: Boolean,
    val strategy: String,
    val splitPosition: String,
    val fakeTtl: Int,
    val fakeCount: Int,
    val delayMs: Int,
    val ports80And443Only: Boolean,
    val expertArgs: String,
) {
    companion object {
        fun load(): ByeDpiSettings = ByeDpiSettings(
            enabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_DPI_ENABLED, false),
            strategy = MmkvManager.decodeSettingsString(AppConfig.PREF_DPI_STRATEGY, "auto") ?: "auto",
            splitPosition = MmkvManager.decodeSettingsString(AppConfig.PREF_DPI_SPLIT_POSITION, "1+s") ?: "1+s",
            fakeTtl = MmkvManager.decodeSettingsString(AppConfig.PREF_DPI_FAKE_TTL, "8")?.toIntOrNull()?.coerceIn(1, 255) ?: 8,
            fakeCount = MmkvManager.decodeSettingsString(AppConfig.PREF_DPI_FAKE_COUNT, "1")?.toIntOrNull()?.coerceIn(1, 8) ?: 1,
            delayMs = MmkvManager.decodeSettingsString(AppConfig.PREF_DPI_DELAY_MS, "0")?.toIntOrNull()?.coerceIn(0, 5000) ?: 0,
            ports80And443Only = MmkvManager.decodeSettingsBool(AppConfig.PREF_DPI_PORTS_80_443_ONLY, true),
            expertArgs = MmkvManager.decodeSettingsString(AppConfig.PREF_DPI_EXPERT_ARGS, "") ?: "",
        )
    }
}

object ByeDpiManager {
    enum class State { STOPPED, STARTING, RUNNING, FAILED }
    enum class Owner { VPN_SERVICE, TEST_SERVICE }

    @Volatile var state: State = State.STOPPED
        private set

    @Volatile private var process: Process? = null
    @Volatile private var logThread: Thread? = null
    @Volatile private var waitThread: Thread? = null
    @Volatile private var stopping = false

    /** Invalidates callbacks belonging to an older process instance. */
    private val generation = AtomicLong(0)
    private val owners = EnumSet.noneOf(Owner::class.java)

    fun isRunning(): Boolean = state == State.RUNNING && process?.isAlive == true

    @Synchronized
    fun acquire(context: Context, owner: Owner): Boolean {
        val settings = ByeDpiSettings.load()
        if (!settings.enabled) {
            Log.i(AppConfig.ANG_PACKAGE, "ByeDPI acquire ignored: disabled owner=$owner")
            return false
        }

        if (isRunning()) {
            owners.add(owner)
            logOwners("acquire", owner)
            return true
        }

        stopLocked()
        stopping = false
        val myGeneration = generation.incrementAndGet()

        val binary = File(context.applicationInfo.nativeLibraryDir, "libciadpi.so")
        if (!binary.isFile) {
            state = State.FAILED
            Log.e(AppConfig.ANG_PACKAGE, "ByeDPI binary missing: ${binary.absolutePath}")
            return false
        }

        val command = mutableListOf(
            binary.absolutePath,
            "--ip", AppConfig.LOOPBACK,
            "--port", AppConfig.PORT_BYEDPI.toString(),
            "--max-conn", "1024",
            "--timeout", "3",
            "--cache-ttl", "86400",
            "--auto-mode", "3",
            "--proto", "http,tls",
        )
        if (settings.ports80And443Only) command += listOf("--pf", "80-443")
        command += presetArguments(settings)
        command += splitCommandLine(settings.expertArgs)

        return try {
            state = State.STARTING
            Log.i(AppConfig.ANG_PACKAGE, "ByeDPI command: ${command.joinToString(" ")}")

            val created = ProcessBuilder(command)
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .start()
            process = created

            logThread = Thread({ readProcessLog(created, myGeneration) }, "ByeDPI-log").apply {
                isDaemon = true
                start()
            }
            waitThread = Thread({ waitForExit(created, myGeneration) }, "ByeDPI-wait").apply {
                isDaemon = true
                start()
            }

            val ready = waitForPort(created, myGeneration, 2500)
            if (ready && process === created && created.isAlive) {
                state = State.RUNNING
                owners.add(owner)
                Log.i(AppConfig.ANG_PACKAGE, "ByeDPI started on ${AppConfig.LOOPBACK}:${AppConfig.PORT_BYEDPI}")
                logOwners("acquire", owner)
                true
            } else {
                if (process === created) state = State.FAILED
                stopLocked()
                false
            }
        } catch (e: Exception) {
            Log.e(AppConfig.ANG_PACKAGE, "ByeDPI start failed", e)
            state = State.FAILED
            stopLocked()
            false
        }
    }

    /**
     * Reads merged stdout/stderr. Closing the process stream during normal VPN shutdown may throw
     * InterruptedIOException/IOException on Android. Those are expected and must never crash the daemon.
     */
    private fun readProcessLog(created: Process, myGeneration: Long) {
        try {
            created.inputStream.bufferedReader().use { reader ->
                while (generation.get() == myGeneration) {
                    val line = reader.readLine() ?: break
                    Log.i("ByeDPI", line)
                }
            }
        } catch (_: InterruptedIOException) {
            if (!stopping && generation.get() == myGeneration) {
                Log.w(AppConfig.ANG_PACKAGE, "ByeDPI log reader interrupted unexpectedly")
            }
        } catch (e: IOException) {
            if (!stopping && generation.get() == myGeneration && created.isAlive) {
                Log.w(AppConfig.ANG_PACKAGE, "ByeDPI log reader stopped", e)
            }
        } catch (e: Throwable) {
            // A background logging thread must never terminate the Android process.
            if (!stopping && generation.get() == myGeneration) {
                Log.e(AppConfig.ANG_PACKAGE, "Unexpected ByeDPI logger failure", e)
            }
        }
    }

    private fun waitForExit(created: Process, myGeneration: Long) {
        try {
            val code = created.waitFor()
            val intentional = stopping || generation.get() != myGeneration || process !== created
            if (intentional) {
                Log.i(AppConfig.ANG_PACKAGE, "ByeDPI stopped with code $code")
            } else {
                Log.w(AppConfig.ANG_PACKAGE, "ByeDPI exited unexpectedly with code $code")
            }

            synchronized(this) {
                if (generation.get() == myGeneration && process === created) {
                    process = null
                    state = if (intentional || code == 0) State.STOPPED else State.FAILED
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Throwable) {
            if (!stopping && generation.get() == myGeneration) {
                Log.e(AppConfig.ANG_PACKAGE, "ByeDPI wait thread failed", e)
            }
        }
    }

    @Synchronized
    fun release(owner: Owner) {
        val removed = owners.remove(owner)
        if (!removed) {
            logOwners("release-missing", owner)
            return
        }

        logOwners("release", owner)
        if (owners.isEmpty()) {
            stopLocked()
        }
    }

    @Synchronized
    fun hasOwner(owner: Owner): Boolean = owners.contains(owner)

    /** Compatibility wrapper for older callers. */
    fun start(context: Context): Boolean = acquire(context, Owner.VPN_SERVICE)

    /** Compatibility wrapper for older callers. */
    fun stop() = release(Owner.VPN_SERVICE)

    private fun logOwners(action: String, owner: Owner) {
        Log.i(
            AppConfig.ANG_PACKAGE,
            "ByeDPI $action owner=$owner owners=${owners.joinToString(prefix = "[", postfix = "]")}"
        )
    }

    /** Caller must hold this object's monitor. Owners are managed by acquire/release. */
    private fun stopLocked() {
        stopping = true
        generation.incrementAndGet()

        val current = process
        process = null
        state = State.STOPPED

        if (current != null) {
            try {
                current.destroy()
                if (!current.waitFor(700, TimeUnit.MILLISECONDS)) {
                    current.destroyForcibly()
                    current.waitFor(700, TimeUnit.MILLISECONDS)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                runCatching { current.destroyForcibly() }
            } catch (e: Exception) {
                Log.w(AppConfig.ANG_PACKAGE, "ByeDPI stop failed", e)
                runCatching { current.destroyForcibly() }
            } finally {
                runCatching { current.inputStream.close() }
                runCatching { current.errorStream.close() }
                runCatching { current.outputStream.close() }
            }
        }

        logThread?.interrupt()
        waitThread?.interrupt()
        logThread = null
        waitThread = null
    }

    private fun waitForPort(created: Process, myGeneration: Long, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (generation.get() != myGeneration || process !== created || !created.isAlive) return false
            try {
                Socket().use {
                    it.connect(InetSocketAddress(AppConfig.LOOPBACK, AppConfig.PORT_BYEDPI), 150)
                }
                return true
            } catch (_: IOException) {
                try {
                    Thread.sleep(75)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return false
    }

    /**
     * Presets are intentionally composed only from options supported by upstream ciadpi.
     * "auto" remains the safe default and now uses several fallback groups.
     */
    private fun presetArguments(s: ByeDpiSettings): List<String> {
        val pos = s.splitPosition.ifBlank { "1+s" }
        val ttl = s.fakeTtl.toString()

        fun repeatedFake(position: String = "-1", withRandomTls: Boolean = false): List<String> {
            val args = mutableListOf<String>()
            repeat(s.fakeCount) {
                args += listOf("--fake", position, "--ttl", ttl)
                if (withRandomTls) args += listOf("--fake-tls-mod", "rand")
            }
            return args
        }

        return when (s.strategy) {
            // Simple/compatible modes.
            "split" -> listOf("--split", pos)
            "split_sni" -> listOf("--split", "1+s")
            "split_sni_middle" -> listOf("--split", "0+sm")
            "split_multi" -> listOf("--split", "1+s", "--split", "3+s")
            "disorder" -> listOf("--disorder", pos)
            "disorder_linux" -> listOf("--disorder", "1")
            "disorder_sni" -> listOf("--split", "1+s", "--disorder", "3+s")

            // Fake packet modes. fake+disorder is useful when TTL alone is unreliable.
            "fake" -> repeatedFake()
            "fake_random" -> repeatedFake(withRandomTls = true)
            "fake_disorder" -> listOf("--disorder", "1") + repeatedFake()
            "fake_split" -> listOf("--split", "1+s", "--disorder", "3+s") + repeatedFake()

            // OOB/TLS record modes can confuse middleboxes, so separate presets are kept.
            "oob" -> listOf("--oob", pos, "--oob-data", "a")
            "disoob" -> listOf("--disoob", "3+s", "--disorder", "1")
            "tls_record_split" -> listOf("--tlsrec", pos)
            "tls_record_sni" -> listOf("--tlsrec", "3+s")

            // Compatibility-first automatic chain: no modification first, then progressively stronger groups.
            "auto_compat" -> listOf(
                "--auto=torst", "--split", "1+s",
                "--auto=torst", "--split", "1+s", "--disorder", "3+s",
                "--auto=ssl_err", "--tlsrec", "3+s",
                "--auto=torst", "--disorder", "1",
            )

            // Balanced default. Groups are cached by ciadpi per destination.
            "auto", "auto_balanced" -> listOf(
                "--split", "1+s", "--disorder", "3+s",
                "--auto=torst", "--disorder", "1", "--fake", "-1",
                "--auto=ssl_err", "--fake", "-1", "--ttl", ttl, "--fake-tls-mod", "rand",
                "--auto=torst", "--tlsrec", "3+s",
                "--auto=torst", "--oob", "3+s", "--oob-data", "a",
                "--auto=torst", "--split", "0+sm",
            )

            // Stronger chain for networks that reset several normal desync variants.
            "auto_aggressive" -> listOf(
                "--disorder", "1", "--fake", "-1",
                "--auto=torst", "--split", "1+s", "--disorder", "3+s", "--fake", "-1", "--ttl", ttl,
                "--auto=ssl_err", "--fake", "-1", "--ttl", ttl, "--fake-tls-mod", "rand",
                "--auto=torst", "--tlsrec", "3+s", "--disorder", "1",
                "--auto=torst", "--disoob", "3+s", "--disorder", "1",
                "--auto=torst", "--split", "1+s", "--split", "3+s", "--disorder", "5+s",
            )

            else -> listOf("--split", pos)
        }
    }

    /** Minimal shell-like tokenizer supporting quotes and backslash escapes. */
    private fun splitCommandLine(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var escaped = false
        for (c in raw) {
            when {
                escaped -> {
                    token.append(c)
                    escaped = false
                }
                c == '\\' -> escaped = true
                quote != null && c == quote -> quote = null
                quote == null && (c == '\'' || c == '"') -> quote = c
                quote == null && c.isWhitespace() -> if (token.isNotEmpty()) {
                    out += token.toString()
                    token.clear()
                }
                else -> token.append(c)
            }
        }
        if (escaped) token.append('\\')
        if (token.isNotEmpty()) out += token.toString()
        return out
    }
}
