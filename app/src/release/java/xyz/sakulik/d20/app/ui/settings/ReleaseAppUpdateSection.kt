package xyz.sakulik.d20.app.ui.settings

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import xyz.sakulik.d20.app.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val RELEASE_API_URL =
    "https://api.github.com/repos/sakulik2/d20-app/releases/latest"
private const val MAX_RELEASE_RESPONSE_BYTES = 1024 * 1024
private const val MAX_APK_BYTES = 250L * 1024 * 1024

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    @SerialName("content_type") val contentType: String? = null,
    val size: Long,
)

private data class AvailableAppUpdate(
    val version: String,
    val title: String,
    val notes: String?,
    val asset: GitHubAsset,
)

private sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data object UpToDate : AppUpdateState
    data class Available(val update: AvailableAppUpdate) : AppUpdateState
    data class Downloading(val update: AvailableAppUpdate, val progress: Int?) : AppUpdateState
    data class Downloaded(val update: AvailableAppUpdate, val uri: Uri) : AppUpdateState
    data class Error(val message: String, val update: AvailableAppUpdate? = null) : AppUpdateState
}

private class ReleaseAppUpdater(context: Context) {
    private val appContext = context.applicationContext
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(): AppUpdateState = withContext(Dispatchers.IO) {
        try {
            val connection = URL(RELEASE_API_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "d20-android/${BuildConfig.VERSION_NAME}")
            try {
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> Unit
                    HttpURLConnection.HTTP_NOT_FOUND -> return@withContext AppUpdateState.Error(
                        "GitHub 尚未发布可用的正式版本",
                    )
                    else -> return@withContext AppUpdateState.Error(
                        "GitHub 返回 HTTP ${connection.responseCode}",
                    )
                }
                if (connection.contentLengthLong > MAX_RELEASE_RESPONSE_BYTES) {
                    return@withContext AppUpdateState.Error("GitHub Release 信息超过大小限制")
                }
                val response = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    var total = 0
                    var count: Int
                    while (input.read(buffer).also { count = it } > 0) {
                        total += count
                        require(total <= MAX_RELEASE_RESPONSE_BYTES) {
                            "GitHub Release 信息超过大小限制"
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                }
                resolveUpdate(json.decodeFromString<GitHubRelease>(response))
            } finally {
                connection.disconnect()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: SerializationException) {
            AppUpdateState.Error("GitHub Release 返回了无法识别的数据")
        } catch (error: Exception) {
            AppUpdateState.Error(error.message ?: "无法连接 GitHub")
        }
    }

    suspend fun download(
        update: AvailableAppUpdate,
        onProgress: suspend (Int?) -> Unit,
    ): AppUpdateState = withContext(Dispatchers.IO) {
        try {
            val request = DownloadManager.Request(Uri.parse(update.asset.downloadUrl))
                .setTitle("d20 ${update.version}")
                .setDescription("正在下载应用更新")
                .setMimeType(APK_MIME_TYPE)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(
                    appContext,
                    Environment.DIRECTORY_DOWNLOADS,
                    safeApkName(update.version),
                )
            val downloadId = downloadManager.enqueue(request)
            while (true) {
                val snapshot = queryDownload(downloadId)
                    ?: return@withContext AppUpdateState.Error("系统下载任务已丢失", update)
                when (snapshot.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val uri = downloadManager.getUriForDownloadedFile(downloadId)
                            ?: return@withContext AppUpdateState.Error("无法读取已下载的 APK", update)
                        return@withContext AppUpdateState.Downloaded(update, uri)
                    }
                    DownloadManager.STATUS_FAILED -> return@withContext AppUpdateState.Error(
                        "系统下载失败（代码 ${snapshot.reason}）",
                        update,
                    )
                    DownloadManager.STATUS_RUNNING -> withContext(Dispatchers.Main.immediate) {
                        onProgress(snapshot.progress)
                    }
                }
                delay(500)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppUpdateState.Error(error.message ?: "无法创建系统下载任务", update)
        }
    }

    fun openInstaller(uri: Uri): String? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !appContext.packageManager.canRequestPackageInstalls()
            ) {
                appContext.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${appContext.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                "请允许此应用安装更新，然后再次点击安装"
            } else {
                appContext.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, APK_MIME_TYPE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                )
                null
            }
        }.getOrElse { error -> error.message ?: "无法打开系统安装界面" }
    }

    private fun resolveUpdate(release: GitHubRelease): AppUpdateState {
        require(!release.draft && !release.prerelease) { "GitHub 返回的不是正式版本" }
        val remoteVersion = parseVersion(release.tagName)
        val localVersion = parseVersion(BuildConfig.VERSION_NAME)
        if (compareVersions(remoteVersion, localVersion) <= 0) return AppUpdateState.UpToDate

        val asset = release.assets
            .asSequence()
            .filter { it.size in 1..MAX_APK_BYTES }
            .filter { asset ->
                asset.name.endsWith(".apk", ignoreCase = true) &&
                    (asset.contentType == null ||
                        asset.contentType == APK_MIME_TYPE ||
                        asset.contentType == "application/octet-stream")
            }
            .filter { isTrustedGitHubDownload(it.downloadUrl) }
            .sortedByDescending { it.name.contains("release", ignoreCase = true) }
            .firstOrNull()
            ?: return AppUpdateState.Error("新版本没有可下载的 APK 文件")

        return AppUpdateState.Available(
            AvailableAppUpdate(
                version = remoteVersion.joinToString("."),
                title = release.name?.takeIf { it.isNotBlank() } ?: release.tagName,
                notes = release.body?.trim()?.take(1_500)?.takeIf { it.isNotBlank() },
                asset = asset,
            ),
        )
    }

    private fun queryDownload(id: Long): DownloadSnapshot? {
        return downloadManager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val downloaded = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
            )
            val total = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
            )
            DownloadSnapshot(
                status = status,
                reason = reason,
                progress = if (total > 0) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else null,
            )
        }
    }

    private fun parseVersion(rawVersion: String): List<Int> {
        val normalized = rawVersion.trim().removePrefix("v").removePrefix("V")
        require(VERSION_PATTERN.matches(normalized)) {
            "无法识别版本号：$rawVersion"
        }
        return normalized.split('.').map(String::toInt)
    }

    private fun compareVersions(left: List<Int>, right: List<Int>): Int {
        for (index in 0..2) {
            val comparison = left[index].compareTo(right[index])
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun isTrustedGitHubDownload(rawUrl: String): Boolean {
        val url = URL(rawUrl)
        return url.protocol.equals("https", ignoreCase = true) &&
            url.host.equals("github.com", ignoreCase = true) &&
            url.path.startsWith("/sakulik2/d20-app/releases/download/", ignoreCase = true)
    }

    private fun safeApkName(version: String): String {
        return "d20-$version-${System.currentTimeMillis()}.apk"
    }

    private data class DownloadSnapshot(
        val status: Int,
        val reason: Int,
        val progress: Int?,
    )

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        val VERSION_PATTERN = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")
    }
}

@Composable
internal fun ReleaseAppUpdateSection() {
    val context = LocalContext.current
    val updater = remember(context.applicationContext) { ReleaseAppUpdater(context) }
    val scope = rememberCoroutineScope()
    var state: AppUpdateState by remember { mutableStateOf(AppUpdateState.Idle) }
    var actionMessage by remember { mutableStateOf<String?>(null) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "应用更新",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "当前版本 ${BuildConfig.VERSION_NAME} · 仅正式版提供 GitHub Release 更新",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            actionMessage?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
            }

            when (val current = state) {
                AppUpdateState.Idle -> Text("按需连接 GitHub 检查正式版本，不会在启动时自动请求。")
                AppUpdateState.Checking -> ProgressRow("正在检查 GitHub Release…")
                AppUpdateState.UpToDate -> Text("当前已经是最新正式版。")
                is AppUpdateState.Available -> UpdateDescription(current.update)
                is AppUpdateState.Downloading -> ProgressRow(
                    current.progress?.let { "正在下载更新：$it%" } ?: "正在下载更新…",
                )
                is AppUpdateState.Downloaded -> Text("APK 已下载，等待系统验证签名并安装。")
                is AppUpdateState.Error -> Text(
                    current.message,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            when (val current = state) {
                AppUpdateState.Checking, is AppUpdateState.Downloading -> Unit
                is AppUpdateState.Available -> Button(
                    onClick = {
                        state = AppUpdateState.Downloading(current.update, null)
                        scope.launch {
                            state = updater.download(current.update) { progress ->
                                state = AppUpdateState.Downloading(current.update, progress)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("一键下载 ${current.update.version}")
                }
                is AppUpdateState.Downloaded -> Button(
                    onClick = {
                        actionMessage = updater.openInstaller(current.uri)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("安装更新")
                }
                is AppUpdateState.Error -> {
                    current.update?.let { update ->
                        OutlinedButton(
                            onClick = { state = AppUpdateState.Available(update) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("重试下载或安装")
                        }
                    }
                    CheckButton(updater) { state = it }
                }
                AppUpdateState.Idle, AppUpdateState.UpToDate -> CheckButton(updater) { state = it }
            }
        }
    }
}

@Composable
private fun CheckButton(updater: ReleaseAppUpdater, onState: (AppUpdateState) -> Unit) {
    val scope = rememberCoroutineScope()
    OutlinedButton(
        onClick = {
            onState(AppUpdateState.Checking)
            scope.launch { onState(updater.check()) }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("检查应用更新")
    }
}

@Composable
private fun UpdateDescription(update: AvailableAppUpdate) {
    Text("发现 ${update.title}（${update.version}）", fontWeight = FontWeight.Bold)
    update.notes?.let { notes ->
        Spacer(Modifier.height(2.dp))
        Text(notes, style = MaterialTheme.typography.bodySmall)
    }
    Text(
        "APK：${update.asset.name} · ${formatBytes(update.asset.size)}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ProgressRow(message: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
        Text(message)
    }
}

private fun formatBytes(bytes: Long): String {
    return if (bytes >= 1024 * 1024) {
        "%.1f MiB".format(bytes.toDouble() / (1024 * 1024))
    } else {
        "%.1f KiB".format(bytes.toDouble() / 1024)
    }
}
