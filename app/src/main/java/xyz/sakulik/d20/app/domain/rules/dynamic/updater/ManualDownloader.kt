package xyz.sakulik.d20.app.domain.common.updater

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import xyz.sakulik.d20.app.domain.rules.dynamic.RulesetProvider
import xyz.sakulik.d20.app.domain.worldview.WorldviewProvider

sealed class DownloadState {
    data class Progress(val percent: Float) : DownloadState()
    object Success : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/**
 * 规则包与设定集的通用插件下载器
 */
class ManualDownloader(private val repository: PluginRepository) {

    fun downloadPlugin(
        entry: RemotePluginEntry,
        onSuccess: (String) -> Unit = {}
    ): Flow<DownloadState> = flow {
        emit(DownloadState.Progress(0f))

        if (!isSafePluginId(entry.id)) {
            emit(DownloadState.Error("插件 ID 不合法：${entry.id}"))
            return@flow
        }

        val tempFile = repository.getTempFile(entry.type, entry.id)
        val finalFile = repository.getSandboxFile(entry.type, entry.id)
        val backupFile = repository.getBackupFile(entry.type, entry.id)
        var installationStarted = false

        try {
            recoverInterruptedInstallation(finalFile, backupFile)
            tempFile.delete()
            val expectedSha256 = Sha256Digest.parseHex(entry.sha256)
                ?: throw IllegalArgumentException("${entry.id} 的 SHA-256 不合法")
            val url = URL(entry.downloadUrl)
            require(url.protocol.equals("https", ignoreCase = true)) { "下载地址必须使用 HTTPS" }
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    emit(DownloadState.Error("服务器返回 HTTP ${connection.responseCode}"))
                    return@flow
                }

                val fileLength = connection.contentLengthLong
                if (fileLength > MAX_PLUGIN_BYTES) {
                    emit(DownloadState.Error("下载文件超过 2 MiB 限制"))
                    return@flow
                }
                val input: InputStream = connection.inputStream
                val output = FileOutputStream(tempFile)

                val data = ByteArray(4096)
                val sha256 = Sha256Accumulator()
                var total = 0L
                var count: Int
                var lastPercent = 0f

                input.use { inStream ->
                    output.use { outStream ->
                        while (inStream.read(data).also { count = it } != -1) {
                            total += count.toLong()
                            if (total > MAX_PLUGIN_BYTES) {
                                throw IllegalArgumentException("下载文件超过 2 MiB 限制")
                            }
                            sha256.update(data, 0, count)
                            outStream.write(data, 0, count)
                            if (fileLength > 0) {
                                val percent = (total * 100f / fileLength).coerceAtMost(99f)
                                if (percent - lastPercent >= 1f) {
                                    emit(DownloadState.Progress(percent / 100f))
                                    lastPercent = percent
                                }
                            }
                        }
                    }
                }
                if (!expectedSha256.matches(sha256.finish())) {
                    throw IllegalArgumentException("SHA-256 校验失败：下载内容与索引摘要不一致")
                }
            } finally {
                connection.disconnect()
            }

            val manifestError = validateManifest(entry, tempFile.readText())
            if (manifestError != null) {
                tempFile.delete()
                emit(DownloadState.Error(manifestError))
                return@flow
            }

            installationStarted = true
            installValidatedFile(tempFile, finalFile, backupFile)
            if (!repository.registerManagedInstallation(entry.type, entry.id)) {
                restoreBackup(finalFile, backupFile)
                emit(DownloadState.Error("无法登记受控安装，已恢复原版本"))
                return@flow
            }
            repository.getRejectedFile(entry.type, entry.id).delete()
            backupFile.delete()

            runCatching { onSuccess(entry.id) }
                .onFailure { error ->
                    Log.w(TAG, "插件已安装，但安装后刷新失败：${entry.type.name}/${entry.id}", error)
                }
            emit(DownloadState.Success)

        } catch (error: CancellationException) {
            if (tempFile.exists()) tempFile.delete()
            if (installationStarted && backupFile.exists()) restoreBackup(finalFile, backupFile)
            throw error
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            if (installationStarted && backupFile.exists()) restoreBackup(finalFile, backupFile)
            emit(DownloadState.Error(e.message ?: "下载或安装失败"))
        }
    }.flowOn(Dispatchers.IO)

    private fun validateManifest(entry: RemotePluginEntry, json: String): String? {
        val manifest = when (entry.type) {
            PluginType.RULESET -> {
                val result = RulesetProvider.parseManifestDetailed(json)
                when (result) {
                    is RulesetProvider.ParseResult.Success -> result.ruleset.id to result.ruleset.version
                    is RulesetProvider.ParseResult.Invalid -> return "规则包无效：" +
                        result.errors.joinToString { it.message }
                }
            }
            PluginType.WORLDVIEW -> {
                val worldview = WorldviewProvider.parseManifest(json)
                    ?: return "世界观包格式无效"
                worldview.id to worldview.version
            }
        }
        if (manifest.first != entry.id) {
            return "包内 ID ${manifest.first} 与索引 ID ${entry.id} 不一致"
        }
        if (manifest.second != entry.version) {
            return "包内版本 ${manifest.second} 与索引版本 ${entry.version} 不一致"
        }
        return null
    }

    private fun installValidatedFile(tempFile: File, finalFile: File, backupFile: File) {
        backupFile.delete()
        if (finalFile.exists()) moveReplacing(finalFile, backupFile)
        try {
            moveReplacing(tempFile, finalFile)
        } catch (e: Exception) {
            restoreBackup(finalFile, backupFile)
            throw e
        }
    }

    private fun recoverInterruptedInstallation(finalFile: File, backupFile: File) {
        if (!backupFile.exists()) return
        if (finalFile.exists()) {
            backupFile.delete()
        } else {
            restoreBackup(finalFile, backupFile)
        }
    }

    private fun restoreBackup(finalFile: File, backupFile: File) {
        if (!backupFile.exists()) {
            finalFile.delete()
            return
        }
        finalFile.delete()
        moveReplacing(backupFile, finalFile)
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val TAG = "ManualDownloader"
        const val MAX_PLUGIN_BYTES = 2L * 1024 * 1024
    }
}
