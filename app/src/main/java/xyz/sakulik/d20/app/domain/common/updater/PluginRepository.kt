package xyz.sakulik.d20.app.domain.common.updater

import android.content.Context
import java.io.File
import java.io.IOException

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

    /**
     * 加载插件 JSON
     * @return Pair<JSON文本, 是否为沙盒版本>
     */
    fun loadPluginJson(type: PluginType, id: String): Pair<String, Boolean>? {
        val filename = "$id.json"
        
        // 1. 尝试沙盒
        val sandboxFile = File(getPluginDir(type), filename)
        if (isManagedInstallation(type, id) && sandboxFile.exists() && sandboxFile.canRead()) {
            try {
                val json = sandboxFile.readText()
                if (json.isNotBlank() && isValidJson(json)) {
                    return Pair(json, true)
                }
            } catch (_: Exception) {}
        }

        // 2. 降级 Assets
        return try {
            val json = context.assets.open("${type.dirName}/$filename").bufferedReader().use { it.readText() }
            Pair(json, false)
        } catch (e: IOException) {
            null
        }
    }

    fun hasPlugin(type: PluginType, id: String): Boolean {
        val filename = "$id.json"
        return (
            isManagedInstallation(type, id) && File(getPluginDir(type), filename).exists()
        ) || assetExists("${type.dirName}/$filename")
    }

    fun getSandboxFile(type: PluginType, id: String): File {
        return File(getPluginDir(type), "$id.json")
    }

    fun getTempFile(type: PluginType, id: String): File {
        return File(getPluginDir(type), "$id.tmp")
    }

    fun getBackupFile(type: PluginType, id: String): File {
        return File(getPluginDir(type), "$id.backup")
    }

    fun registerManagedInstallation(type: PluginType, id: String): Boolean {
        val updated = managedInstallationIds(type).toMutableSet().apply { add(id) }
        return installationPreferences.edit().putStringSet(preferenceKey(type), updated).commit()
    }

    fun isManagedInstallation(type: PluginType, id: String): Boolean {
        return id in managedInstallationIds(type)
    }

    /**
     * 列出所有可用的插件 ID
     */
    fun listPluginIds(type: PluginType): List<String> {
        val ids = mutableSetOf<String>()
        
        // 1. 只接纳下载器登记过的沙盒文件，不扫描任意 JSON
        managedInstallationIds(type).forEach { id ->
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

    private fun isValidJson(content: String): Boolean {
        val trimmed = content.trim()
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))
    }


    private fun managedInstallationIds(type: PluginType): Set<String> {
        return installationPreferences.getStringSet(preferenceKey(type), emptySet())?.toSet().orEmpty()
    }

    private fun preferenceKey(type: PluginType): String = "installed_${type.name.lowercase()}"
}
