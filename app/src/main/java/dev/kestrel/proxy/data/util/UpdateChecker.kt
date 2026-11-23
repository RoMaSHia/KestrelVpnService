package dev.kestrel.proxy.data.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
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

enum class InstallLaunchOutcome {
    LAUNCHED_INSTALLER,
    OPENED_PERMISSION_SETTINGS,
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

    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: (Float) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val tmpFile = File(updatesDir, "Kestrel-update.apk.part")
            val finalFile = File(updatesDir, "Kestrel-update.apk")
            tmpFile.delete()
            finalFile.delete()

            val conn = openFollowingRedirects(downloadUrl)
            try {
                val code = conn.responseCode
                check(code in 200..299) { "Ошибка загрузки APK (HTTP $code)" }
                val totalBytes = conn.contentLengthLong
                var downloaded = 0L

                conn.inputStream.use { input ->
                    tmpFile.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0) {
                                onProgress((downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            check(tmpFile.length() > 100_000L) { "Загруженный файл повреждён или пуст" }
            check(tmpFile.renameTo(finalFile)) { "Не удалось сохранить файл обновления" }
            onProgress(1f)
            finalFile
        }
    }

    private fun openFollowingRedirects(startUrl: String): HttpURLConnection {
        var currentUrl = startUrl
        repeat(6) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Kestrel-Android")
            }
            val code = conn.responseCode
            if (code in listOf(
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308,
                )
            ) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                check(!location.isNullOrBlank()) { "Пустой редирект при скачивании APK" }
                currentUrl = URL(URL(currentUrl), location).toString()
            } else {
                return conn
            }
        }
        throw IllegalStateException("Слишком много редиректов при загрузке APK")
    }

    fun canInstallPackages(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun launchInstaller(context: Context, apkFile: File): InstallLaunchOutcome {
        if (!canInstallPackages(context)) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
            return InstallLaunchOutcome.OPENED_PERMISSION_SETTINGS
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        return InstallLaunchOutcome.LAUNCHED_INSTALLER
    }
}
