package xyz.sakulik.d20.app.domain.common.validator

import android.content.Context
import android.util.Log
import xyz.sakulik.d20.app.data.local.*
import xyz.sakulik.d20.app.domain.rules.RulesetRegistry
import xyz.sakulik.d20.app.domain.rules.dynamic.PointBuyField
import xyz.sakulik.d20.app.domain.rules.dynamic.DropdownField

sealed class IntegrityStatus {
    object Healthy : IntegrityStatus()
    data class Repaired(val repairLog: List<String>) : IntegrityStatus()
    data class Critical(val reason: String) : IntegrityStatus()
}

/**
 * 存档完整性校验与多规则兼容修复器
 */
class CampaignIntegrityValidator(
    private val context: Context,
    private val campaignDao: CampaignDao,
    private val characterDao: CharacterDao,
    private val messageDao: MessageDao,
    private val itemDao: ItemDao
) {

    /**
     * 对指定剧本执行多维度完整性校验与无感自动修复
     */
    suspend fun validateAndRepair(campaignId: String): IntegrityStatus {
        val campaignWithData = campaignDao.getCampaignWithData(campaignId)
            ?: return IntegrityStatus.Critical("存档不存在")

        val character = campaignWithData.character
        val repairLogs = mutableListOf<String>()

        // 1. 没有角色表示创建流程尚未完成，绝不能由完整性检查删除草稿。
        if (character == null) {
            return IntegrityStatus.Critical("存档尚未完成角色创建")
        }

        // 2. 动态读取关联的规则系统契约 (支持 DND, CoC, 自定义 JSON 规则)
        val ruleset = RulesetRegistry.getRuleset(context, character.activeSystem)
        if (ruleset == null) {
            repairLogs.add("提示: 规则系统 [${character.activeSystem}] 插件未找到，使用通用降级防护模式")
        }

        val schema = ruleset?.creationSchema
        val defaultStats = ruleset?.getInitialCharacter() ?: emptyMap()
        val mutableStats = character.stats.toMutableMap()
        var statRepaired = false

        // 3. 核心属性补全 (基于 ruleset 初始契约)
        defaultStats.forEach { (key, defaultValue) ->
            val defaultStr = defaultValue.toString()
            if (!mutableStats.containsKey(key) || mutableStats[key].isNullOrBlank()) {
                mutableStats[key] = defaultStr
                statRepaired = true
                repairLogs.add("自动补全缺省基础属性 [$key: $defaultStr]")
            }
        }

        // 4. Schema 必填属性补全
        schema?.fields?.forEach { field ->
            if (!mutableStats.containsKey(field.id) || mutableStats[field.id].toString().isBlank()) {
                val fallbackVal = when (field) {
                    is PointBuyField -> field.min.toString()
                    is DropdownField -> field.options.firstOrNull() ?: ""
                    else -> "0"
                }
                mutableStats[field.id] = fallbackVal
                statRepaired = true
                repairLogs.add("自动补全 Schema 必填字段 [${field.label}: $fallbackVal]")
            }
        }

        // 5. 规则特定推导智能修补 (如 CoC 理智与血量推导)
        val sysId = character.activeSystem.lowercase()
        if (sysId.contains("coc_7e")) {
            val pow = mutableStats["pow"]?.toIntOrNull() ?: 50
            if (!mutableStats.containsKey("san") || (mutableStats["san"]?.toIntOrNull() ?: 0) <= 0) {
                mutableStats["san"] = pow.toString()
                statRepaired = true
                repairLogs.add("根据 意志 (POW=$pow) 自动智能恢复理智 (SAN=$pow)")
            }
        }

        // 6. 如果写回有变更，保存回数据库
        if (statRepaired) {
            val updatedChar = character.copy(stats = mutableStats)
            characterDao.updateCharacter(updatedChar)
            Log.d("IntegrityValidator", "Repaired character stats for $campaignId: ${repairLogs.joinToString("; ")}")
        }

        return if (repairLogs.isNotEmpty()) {
            IntegrityStatus.Repaired(repairLogs)
        } else {
            IntegrityStatus.Healthy
        }
    }
}
