package dev.kestrel.proxy.core

import dev.kestrel.proxy.data.model.ConnectionLogEntry
import dev.kestrel.proxy.data.model.TrafficStats
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CoreInfo(
    val name: String,
    val version: String,
    val arch: String,
    val profile: String,
    val status: String,
)

/**
 * Callback interface invoked from Rust via JNI before outbound `connect()` / `bind()`
 * so sockets bypass the VPN TUN interface and never enter a routing loop.
 */
interface SocketProtector {
    fun protect(fd: Int): Boolean
}

/**
 * Thread-safe JNI entry point into the Rust `libproxy_core.so` native library.
 */
object NativeBridge {

    private val json = Json { ignoreUnknownKeys = true }
    private val loadError: Throwable?

    init {
        loadError = runCatching {
            System.loadLibrary("proxy_core")
        }.exceptionOrNull()
    }

    private external fun nativeGetCoreInfo(): String

    private external fun nativePing(message: String): String

    private external fun nativeStart(
        tunFd: Int,
        configJson: String,
        protector: SocketProtector,
    ): Int

    private external fun nativeStop(): Int

    private external fun nativeUpdateConfig(configJson: String): Int

    private external fun nativeGetStats(): String

    private external fun nativePollLogs(sinceId: Long, limit: Int): String

    fun getCoreInfo(): Result<CoreInfo> {
        loadError?.let { return Result.failure(it) }
        return runCatching {
            val rawJson = nativeGetCoreInfo()
            json.decodeFromString<CoreInfo>(rawJson)
        }
    }

    fun ping(message: String): Result<String> {
        loadError?.let { return Result.failure(it) }
        return runCatching {
            nativePing(message)
        }
    }

    fun start(
        tunFd: Int,
        configJson: String,
        protector: SocketProtector,
    ): Result<Unit> {
        loadError?.let { return Result.failure(it) }
        return runCatching {
            val code = nativeStart(tunFd, configJson, protector)
            check(code == 0) { "Не удалось запустить ядро Rust (код $code)" }
        }
    }

    fun stop(): Result<Unit> {
        loadError?.let { return Result.failure(it) }
        return runCatching {
            val code = nativeStop()
            check(code == 0) { "Ошибка остановки ядра Rust (код $code)" }
        }
    }

    fun updateConfig(configJson: String): Result<Unit> {
        loadError?.let { return Result.failure(it) }
        return runCatching {
            val code = nativeUpdateConfig(configJson)
            check(code == 0) { "Не удалось обновить правила ядра (код $code)" }
        }
    }

    fun getStats(): Result<TrafficStats> {
        loadError?.let { return Result.failure(it) }
        return runCatching {
            val raw = nativeGetStats()
            if (raw.isBlank() || raw == "{}") {
                TrafficStats()
            } else {
                json.decodeFromString<TrafficStats>(raw)
            }
        }
    }

    fun pollLogs(sinceId: Long, limit: Int = 100): Result<List<ConnectionLogEntry>> {
        loadError?.let { return Result.failure(it) }
        return runCatching {
            val raw = nativePollLogs(sinceId, limit)
            if (raw.isBlank() || raw == "[]") {
                emptyList()
            } else {
                json.decodeFromString<List<ConnectionLogEntry>>(raw)
            }
        }
    }
}
