package xyz.sakulik.d20.app.domain.common.updater

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class LoadedPlugin<T>(
    val value: T,
    val fromManagedInstallation: Boolean
)

/**
 * 通用插件仓库 (Ruleset & Worldview)
 * 三级读取策略：FilesDir (Sandbox) -> Assets -> Error
 */
class PluginRepository(private val context: Context) {

    private val installationPreferences = context.getSharedPreferences(
        "managed_plugin_installations",
        Context.MODE_PRIVATE
    )

    private fun getPluginDir(type: PluginType): File {
        return File(context.filesDir, type.dirName).apply {
            if (!exists()) mkdirs()
        }
    }

    fun <T> loadFirstValid(
        type: PluginType,
        id: String,
        parse: (String) -> T?
    ): LoadedPlugin<T>? {
        if (!isSafePluginId(id)) return null
        val sandboxFile = getSandboxFile(type, id)
        if (isManagedInstallation(type, id)) {
            val managedValue = if (sandboxFile.isFile && sandboxFile.canRead()) {
                runCatching { parse(sandboxFile.readText()) }.getOrNull()
            } else {
                null
            }
            if (managedValue != null) {
                return LoadedPlugin(managedValue, fromManagedInstallation = true)
            }
            quarantineManagedInstallation(type, id)
        }

        val assetValue = runCatching {
            context.assets.open("${type.dirName}/$id.json")
                .bufferedReader()
                .use { reader -> parse(reader.readText()) }
        }.getOrNull()
        return assetValue?.let { LoadedPlugin(it, fromManagedInstallation = false) }
    }

    fun hasPlugin(type: PluginType, id: String): Boolean {
        if (!isSafePluginId(id)) return false
        val filename = "$id.json"
        return (
            isManagedInstallation(type, id) && File(getPluginDir(type), filename).isFile
        ) || assetExists("${type.dirName}/$filename")
    }

    fun getSandboxFile(type: PluginType, id: String): File {
        requireSafePluginId(id)
        return File(getPluginDir(type), "$id.json")
    }

    fun getTempFile(type: PluginType, id: String): File {
        requireSafePluginId(id)
        return File(getPluginDir(type), "$id.tmp")
    }

    fun getBackupFile(type: PluginType, id: String): File {
        requireSafePluginId(id)
        return File(getPluginDir(type), "$id.backup")
    }

    fun getRejectedFile(type: PluginType, id: String): File {
        requireSafePluginId(id)
        return File(getPluginDir(type), "$id.rejected")
    }

    fun registerManagedInstallation(type: PluginType, id: String): Boolean {
        requireSafePluginId(id)
        val updated = managedInstallationIds(type).toMutableSet().apply { add(id) }
        return installationPreferences.edit().putStringSet(preferenceKey(type), updated).commit()
    }

    fun unregisterManagedInstallation(type: PluginType, id: String): Boolean {
        requireSafePluginId(id)
        val updated = managedInstallationIds(type).toMutableSet().apply { remove(id) }
        return installationPreferences.edit().putStringSet(preferenceKey(type), updated).commit()
    }

    fun isManagedInstallation(type: PluginType, id: String): Boolean {
        if (!isSafePluginId(id)) return false
        return id in managedInstallationIds(type)
    }

    /**
     * 列出所有可用的插件 ID
     */
    fun listPluginIds(type: PluginType): List<String> {
        val ids = mutableSetOf<String>()
        
        // 1. 只接纳下载器登记过的沙盒文件，不扫描任意 JSON
        managedInstallationIds(type).filter(::isSafePluginId).forEach { id ->
            if (File(getPluginDir(type), "$id.json").isFile) {
                ids.add(id)
            }
        }
        
        // 2. 扫描 Assets
        try {
            context.assets.list(type.dirName)?.forEach { name ->
                if (name.endsWith(".json")) {
                    ids.add(name.removeSuffix(".json"))
                }
            }
        } catch (e: IOException) {}
        
        return ids.toList()
    }

    private fun assetExists(path: String): Boolean {
        return try {
            val folder = path.substringBeforeLast('/', "")
            val file = path.substringAfterLast('/')
            val list = context.assets.list(folder) ?: emptyArray()
            list.contains(file)
        } catch (e: IOException) {
            false
        }
    }

    private fun quarantineManagedInstallation(type: PluginType, id: String) {
        val sandboxFile = getSandboxFile(type, id)
        val rejectedFile = getRejectedFile(type, id)
        if (sandboxFile.isFile) {
            runCatching {
                Files.move(
                    sandboxFile.toPath(),
                    rejectedFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }.onFailure { error ->
                Log.e("PluginRepository", "无法隔离损坏的受控包 ${type.name}/$id", error)
                sandboxFile.delete()
            }
        } else if (sandboxFile.exists()) {
            Log.e("PluginRepository", "受控包路径不是普通文件 ${type.name}/$id")
        }
        if (!unregisterManagedInstallation(type, id)) {
            Log.e("PluginRepository", "无法撤销损坏受控包登记 ${type.name}/$id")
        }
    }


    private fun managedInstallationIds(type: PluginType): Set<String> {
        return installationPreferences.getStringSet(preferenceKey(type), emptySet())?.toSet().orEmpty()
    }

    private fun preferenceKey(type: PluginType): String = "installed_${type.name.lowercase()}"
}
