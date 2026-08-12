package xyz.sakulik.d20.app.domain.common.updater

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream

/**
 * 通用插件更新检测器 (UpdateChecker)
 */
class UpdateChecker(
    private val repository: PluginRepository,
    private val getLocalVersionLogic: (PluginType, String) -> String?
) {
    private val jsonConfig = Json { ignoreUnknownKeys = true }

    fun checkUpdates(type: PluginType, indexUrl: String): Flow<UpdateCheckResult> = flow {
        try {
            val url = URL(indexUrl)
            require(url.protocol.equals("https", ignoreCase = true)) {
                "插件索引必须使用 HTTPS"
            }
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    emit(UpdateCheckResult.Error("服务器返回 HTTP ${connection.responseCode}"))
                    return@flow
                }
                val contentLength = connection.contentLengthLong
                if (contentLength > MAX_INDEX_BYTES) {
                    emit(UpdateCheckResult.Error("服务器索引超过大小限制"))
                    return@flow
                }
                val jsonStr = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    var total = 0
                    var read: Int
                    while (input.read(buffer).also { read = it } > 0) {
                        total += read
                        require(total <= MAX_INDEX_BYTES) { "服务器索引超过大小限制" }
                        output.write(buffer, 0, read)
                    }
                    output.toString(Charsets.UTF_8.name())
                }
                val remoteIndex = jsonConfig.decodeFromString<RemotePluginIndex>(jsonStr)
                val relevantEntries = remoteIndex.plugins.filter { it.type == type }
                validateEntries(relevantEntries)
                val states = relevantEntries.map { entry ->
                    val hasLocal = repository.hasPlugin(type, entry.id)
                    if (!hasLocal) {
                        PluginUpdateState.NotInstalled(entry.id, type, entry)
                    } else {
                        val localVersion = getLocalVersionLogic(type, entry.id)
                        if (localVersion == null) {
                            PluginUpdateState.UpdateAvailable(entry.id, type, null, entry.version, entry)
                        } else {
                            if (isVersionNewer(localVersion, entry.version)) {
                                PluginUpdateState.UpdateAvailable(entry.id, type, localVersion, entry.version, entry)
                            } else {
                                PluginUpdateState.UpToDate(entry.id, type, localVersion)
                            }
                        }
                    }
                }
                emit(UpdateCheckResult.Success(states))
            } finally {
                connection.disconnect()
            }
        } catch (e: SerializationException) {
            emit(UpdateCheckResult.Error("服务器索引格式无效"))
        } catch (e: IllegalArgumentException) {
            emit(UpdateCheckResult.Error(e.message ?: "服务器索引无效"))
        } catch (e: Exception) {
            emit(UpdateCheckResult.Error("无法连接规则包服务器：${e.message ?: "未知网络错误"}"))
        }
    }

    internal fun isVersionNewer(local: String, remote: String): Boolean {
        if (local == remote) return false
        val localParts = parseVersion(local, allowLegacy = true)
        val remoteParts = parseVersion(remote)
        for (i in localParts.indices) {
            val l = localParts[i]
            val r = remoteParts[i]
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    private fun validateEntries(entries: List<RemotePluginEntry>) {
        require(entries.map { it.id }.distinct().size == entries.size) {
            "服务器索引包含重复插件 ID"
        }
        entries.forEach { entry ->
            require(PLUGIN_ID.matches(entry.id)) { "规则包 ID 不合法：${entry.id}" }
            parseVersion(entry.version)
            require(SHA256.matches(entry.sha256)) { "${entry.id} 的 SHA-256 不合法" }
            val downloadUrl = URL(entry.downloadUrl)
            require(downloadUrl.protocol.equals("https", ignoreCase = true)) {
                "${entry.id} 的下载地址必须使用 HTTPS"
            }
        }
    }

    private fun parseVersion(version: String, allowLegacy: Boolean = false): List<Int> {
        val pattern = if (allowLegacy) LEGACY_VERSION else SEMANTIC_VERSION
        require(pattern.matches(version)) { "版本号必须为 MAJOR.MINOR.PATCH：$version" }
        return version.split('.').map { it.toInt() }.let { parts ->
            parts + List(3 - parts.size) { 0 }
        }
    }

    private companion object {
        const val MAX_INDEX_BYTES = 512 * 1024
        val PLUGIN_ID = Regex("^[a-z][a-z0-9_]{1,63}$")
        val SEMANTIC_VERSION = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")
        val LEGACY_VERSION = Regex("^(0|[1-9]\\d*)(\\.(0|[1-9]\\d*)){0,2}$")
        val SHA256 = Regex("^[a-fA-F0-9]{64}$")
    }
}
