package xyz.sakulik.d20.app.domain.rules

import android.content.Context
import android.util.Log
import xyz.sakulik.d20.app.domain.rules.dynamic.IRuleset
import xyz.sakulik.d20.app.domain.rules.dynamic.RulesetProvider
import xyz.sakulik.d20.app.domain.common.updater.PluginRepository
import xyz.sakulik.d20.app.domain.common.updater.PluginType

/**
 * 规则系统元数据，用于 UI 展示
 */
data class SystemMetadata(
    val id: String,
    val name: String,
    val description: String,
    val iconResId: Int? = null 
)

/**
 * 规则系统注册表 (基于动态引擎)
 */
object RulesetRegistry {
    private val rulesetCache = mutableMapOf<String, IRuleset>()

    fun getMetadataList(context: Context): List<SystemMetadata> {
        val repository = PluginRepository(context)
        val allIds = repository.listPluginIds(PluginType.RULESET)
        return allIds.sorted().mapNotNull { id ->
            getRuleset(context, id)?.let { ruleset ->
                SystemMetadata(
                    id = id,
                    name = ruleset.name,
                    description = ruleset.description ?: "TRPG 规则系统 · 版本 ${ruleset.version}"
                )
            }
        }
    }

    /**
     * 动态加载规则集。只读取内置资源或由受控下载器登记的沙盒版本，解析后放入缓存。
     */
    fun getRuleset(context: Context, id: String): IRuleset? {
        if (rulesetCache.containsKey(id)) {
            return rulesetCache[id]
        }

        val baseId = when (id) {
            "coc_7e_dynamic", "coc_7e" -> "coc_7e"
            "dnd_5e_dynamic", "dnd_5e" -> "dnd_5e"
            else -> id
        }

        val repository = PluginRepository(context)
        return try {
            val loaded = repository.loadFirstValid(PluginType.RULESET, baseId) { json ->
                val result = RulesetProvider.parseManifestDetailed(json)
                (result as? RulesetProvider.ParseResult.Success)
                    ?.ruleset
                    ?.takeIf { ruleset -> ruleset.id == baseId }
            }
            val ruleset = loaded?.value
            if (ruleset != null) {
                rulesetCache[id] = ruleset
                // 兼容老映射
                if (id == "coc_7e") rulesetCache["coc_7e_dynamic"] = ruleset
                if (id == "dnd_5e") rulesetCache["dnd_5e_dynamic"] = ruleset
                ruleset
            } else {
                Log.e("RulesetRegistry", "Ruleset $id rejected: 没有通过完整契约的下载版或内置版")
                null
            }
        } catch (e: Exception) {
            Log.e("RulesetRegistry", "Error parsing ruleset $id", e)
            null
        }
    }

    /**
     * 清除内存缓存，供本地手动更新器完成原子替换后调用，以便后续强制读取最新文件
     */
    fun evictCache(id: String) {
        rulesetCache.remove(id)
        if (id == "coc_7e") rulesetCache.remove("coc_7e_dynamic")
        if (id == "dnd_5e") rulesetCache.remove("dnd_5e_dynamic")
    }

}
