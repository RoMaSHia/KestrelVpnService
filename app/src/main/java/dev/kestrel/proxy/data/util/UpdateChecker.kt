package dev.kestrel.proxy.data.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

@Serializable
private data class GitHubAssetDto(
    val name: String,
    @SerialName("browser_download_url")
    val browserDownloadUrl: String,
)

@Serializable
private data class GitHubReleaseDto(
    @SerialName("tag_name")
    val tagName: String,
    @SerialName("html_url")
    val htmlUrl: String = UpdateChecker.RELEASES_URL,
    val body: String = "",
    val assets: List<GitHubAssetDto> = emptyList(),
)

sealed class UpdateCheckResult {
    data class UpToDate(val currentVersion: String) : UpdateCheckResult()
    data class UpdateAvailable(
        val latestVersion: String,
        val downloadUrl: String,
        val releasePageUrl: String,
        val releaseNotes: String,
    ) : UpdateCheckResult()

    data class Unavailable(
        val message: String,
        val releasesUrl: String = UpdateChecker.RELEASES_URL,
    ) : UpdateCheckResult()
}

object UpdateChecker {

    const val RELEASES_URL = "https://github.com/RoMaSHia/KestrelVpnService/releases"
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/RoMaSHia/KestrelVpnService/releases/latest"
    private const val DEFAULT_APK_URL =
        "https://github.com/RoMaSHia/KestrelVpnService/releases/latest/download/Kestrel-universal.apk"

    private val json = Json { ignoreUnknownKeys = true }

    fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = parseNumericParts(latest)
        val currentParts = parseNumericParts(current)
        if (latestParts.isEmpty() || currentParts.isEmpty()) return false
        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    private fun parseNumericParts(raw: String): List<Int> =
        raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
            .split('.')
            .mapNotNull { it.toIntOrNull() }

    suspend fun checkLatestRelease(currentVersion: String): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Kestrel-Android")
                }
                try {
                    val code = conn.responseCode
                    if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                        return@withContext UpdateCheckResult.Unavailable(
                            "Релиз не найден через API (если репозиторий приватный, скачайте APK вручную через браузер).",
                        )
                    }
                    if (code !in 200..299) {
                        return@withContext UpdateCheckResult.Unavailable(
                            "GitHub API вернул код $code",
                        )
                    }
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val release = json.decodeFromString<GitHubReleaseDto>(body)
                    val latestClean = release.tagName.trim().removePrefix("v").removePrefix("V")
                    if (!isNewerVersion(latestClean, currentVersion)) {
                        return@withContext UpdateCheckResult.UpToDate(currentVersion)
                    }
                    val apkAsset = release.assets.firstOrNull {
                        it.name.equals("Kestrel-universal.apk", ignoreCase = true)
                    } ?: release.assets.firstOrNull {
                        it.name.endsWith(".apk", ignoreCase = true) && !it.name.contains("debug", ignoreCase = true)
                    } ?: release.assets.firstOrNull {
                        it.name.endsWith(".apk", ignoreCase = true)
                    }

                    UpdateCheckResult.UpdateAvailable(
                        latestVersion = latestClean,
                        downloadUrl = apkAsset?.browserDownloadUrl ?: DEFAULT_APK_URL,
                        releasePageUrl = release.htmlUrl.ifBlank { RELEASES_URL },
                        releaseNotes = release.body,
                    )
                } finally {
                    conn.disconnect()
                }
            }.getOrElse { err ->
                UpdateCheckResult.Unavailable(
                    err.message ?: "Нет подключения для проверки обновлений",
                )
            }
        }
}
