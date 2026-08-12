package xyz.sakulik.d20.app.ui.creation

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import xyz.sakulik.d20.app.data.local.CharacterDao
import xyz.sakulik.d20.app.data.local.CharacterEntity
import xyz.sakulik.d20.app.data.local.MessageDao
import xyz.sakulik.d20.app.data.local.MessageEntity
import xyz.sakulik.d20.app.data.local.ItemEntity
import kotlinx.serialization.json.*
import xyz.sakulik.d20.app.ui.base.BaseViewModel
import xyz.sakulik.d20.app.ui.base.UiEvent
import xyz.sakulik.d20.app.ui.base.UiState
import xyz.sakulik.d20.app.domain.rules.action.toSpellProfileOrNull
import xyz.sakulik.d20.app.domain.rules.action.toWeaponProfileOrNull

enum class AllocationMode {
    POINT_BUY,
    ROLLING,
    AI_GEN
}

data class CreationUiState(
    val campaignId: String = "",
    val rulesetId: String = "dnd_5e",
    val allocationMode: AllocationMode = AllocationMode.ROLLING,
    val characterName: String = "",
    val stats: Map<String, Any> = emptyMap(),
    val bio: String = "",
    val generatedItems: List<xyz.sakulik.d20.app.data.model.ItemGenModel> = emptyList(),
    val schema: xyz.sakulik.d20.app.domain.rules.dynamic.CreationSchema? = null,
    val visibleFields: List<xyz.sakulik.d20.app.domain.rules.dynamic.CreationField> = emptyList(),
    val remainingPoints: Int = 0,
    val totalPoints: Int = 0,
    val isSaving: Boolean = false,
    val isAiGenerating: Boolean = false,
    val error: String? = null,
    val validationErrors: List<String> = emptyList()
) : UiState

sealed class CreationUiEvent : UiEvent {
    object Success : CreationUiEvent()
    data class Notice(val message: String) : CreationUiEvent()
    data class Error(val message: String) : CreationUiEvent()
}

class CreationViewModel(
    private val context: android.content.Context,
    private val characterDao: CharacterDao,
    private val messageDao: MessageDao,
    private val repository: xyz.sakulik.d20.app.data.repository.LlmRepository,
    private val inventoryRepository: xyz.sakulik.d20.app.data.repository.InventoryRepository,
    private val keyManager: xyz.sakulik.d20.app.data.security.LlmKeyManager
) : BaseViewModel<CreationUiState, CreationUiEvent>(CreationUiState()) {

    private var aiGenerationJob: Job? = null
    private var aiGenerationRequestId: Long = 0

    /**
     * 初始化创卡界面
     * @param campaignId 所属剧本
     * @param rulesetId 规则系统
     */
    fun init(campaignId: String, rulesetId: String, mode: AllocationMode = AllocationMode.ROLLING) {
        val ruleset = xyz.sakulik.d20.app.domain.rules.RulesetRegistry.getRuleset(context, rulesetId)
        val schema = ruleset?.creationSchema
        
        // 解析 AST 中的默认模板
        val initialStats = ruleset?.getInitialCharacter()?.toMutableMap() ?: mutableMapOf()

        // 确保 schema 中定义的字段在 initialStats 中有占位符
        schema?.fields?.forEach { field ->
            if (!initialStats.containsKey(field.id)) {
                initialStats[field.id] = when (field) {
                    is xyz.sakulik.d20.app.domain.rules.dynamic.PointBuyField -> field.min
                    is xyz.sakulik.d20.app.domain.rules.dynamic.DiceRollField -> 0
                    is xyz.sakulik.d20.app.domain.rules.dynamic.DropdownField -> field.options.firstOrNull() ?: ""
                    else -> ""
                }
            }
        }

        val points = schema?.totalPoints ?: 0
        val visible = calculateVisibleFields(initialStats, schema, rulesetId)

        updateState { it.copy(
            campaignId = campaignId,
            rulesetId = rulesetId,
            allocationMode = mode, 
            stats = initialStats,
            schema = schema,
            visibleFields = visible,
            totalPoints = points,
            remainingPoints = points - calculateSpentPoints(initialStats, schema)
        ) }
    }

    private fun calculateVisibleFields(
        stats: Map<String, Any>,
        schema: xyz.sakulik.d20.app.domain.rules.dynamic.CreationSchema?,
        rulesetId: String
    ): List<xyz.sakulik.d20.app.domain.rules.dynamic.CreationField> {
        val allFields = schema?.fields ?: return emptyList()
        val ruleset = xyz.sakulik.d20.app.domain.rules.RulesetRegistry.getRuleset(context, rulesetId) as? xyz.sakulik.d20.app.domain.rules.dynamic.DynamicRulesetImpl ?: return allFields
        
        return allFields.filter { field ->
            ruleset.evaluateVisibility(field, stats)
        }
    }

    private fun calculateSpentPoints(stats: Map<String, Any>, schema: xyz.sakulik.d20.app.domain.rules.dynamic.CreationSchema?): Int {
        if (schema?.totalPoints == null) return 0
        var spent = 0
        val rid = uiState.value.rulesetId.lowercase()
        
        schema.fields.forEach { field ->
            if (field is xyz.sakulik.d20.app.domain.rules.dynamic.PointBuyField) {
                val value = (stats[field.id] as? Number)?.toInt() ?: field.min
                
                // D&D 5e 官方阶梯购点开销逻辑 (8->0, 9->1, 10->2, 11->3, 12->4, 13->5, 14->7, 15->9)
                if (rid.contains("dnd_5e")) {
                    spent += when (value) {
                        8 -> 0
                        9 -> 1
                        10 -> 2
                        11 -> 3
                        12 -> 4
                        13 -> 5
                        14 -> 7
                        15 -> 9
                        else -> if (value < 8) 0 else 9 + (value - 15) * 2 // 溢出惩罚计算
                    }
                } else {
                    // 通用线性逻辑
                    spent += (value - field.min).coerceAtLeast(0) * field.costMultiplier
                }
            }
        }
        return spent
    }

    private fun validateStats(stats: Map<String, Any>, schema: xyz.sakulik.d20.app.domain.rules.dynamic.CreationSchema?): List<String> {
        val errors = mutableListOf<String>()
        val currentState = uiState.value
        val rid = currentState.rulesetId.lowercase()
        
        // 1. 购点模式溢出检查
        if (currentState.allocationMode == AllocationMode.POINT_BUY) {
            val spent = calculateSpentPoints(stats, schema)
            val total = schema?.totalPoints ?: 0
            if (spent > total) {
                errors.add("购点过剩: 已用 $spent 点 / 上限 $total 点 (超出 ${spent - total} 点)")
            }

            schema?.fields?.forEach { field ->
                if (field is xyz.sakulik.d20.app.domain.rules.dynamic.PointBuyField) {
                    val value = (stats[field.id] as? Number)?.toInt() ?: field.min
                    val effectiveMax = if (rid.contains("dnd_5e")) 15 else field.max
                    if (value < field.min || value > effectiveMax) {
                        errors.add("${field.label} 超出购点法允许范围 (${field.min} ~ $effectiveMax)")
                    }
                }
            }
        }

        // 2. 掷骰模式及手动输入越界严密检查
        if (currentState.allocationMode == AllocationMode.ROLLING || currentState.allocationMode == AllocationMode.AI_GEN) {
            schema?.fields?.forEach { field ->
                when (field) {
                    is xyz.sakulik.d20.app.domain.rules.dynamic.PointBuyField -> {
                        val value = (stats[field.id] as? Number)?.toInt() ?: field.min
                        val (allowedMin, allowedMax) = when {
                            rid.contains("dnd_5e") -> 3 to 18 // DND 5e 4d6kh3 官方区间 3~18
                            rid.contains("coc_7e") -> {
                                if (field.id in listOf("siz", "int", "edu")) 40 to 90
                                else 15 to 90
                            }
                            else -> field.min to field.max
                        }

                        if (value < allowedMin || value > allowedMax) {
                            errors.add("${field.label} 超出规则掷骰有效范围 ($allowedMin ~ $allowedMax)，当前为 $value")
                        }
                    }
                    is xyz.sakulik.d20.app.domain.rules.dynamic.DiceRollField -> {
                        val value = (stats[field.id] as? Number)?.toInt() ?: 0
                        val (formulaMin, formulaMax) = calculateFormulaBounds(field.formula)
                        if (value < formulaMin || value > formulaMax) {
                            errors.add("${field.label} 超出配方 [${field.formula}] 允许范围 ($formulaMin ~ $formulaMax)，当前为 $value")
                        }
                    }
                    else -> {}
                }
            }
        }
        
        return errors
    }

    /**
     * 计算公式的最大与最小有效边界
     */
    private fun calculateFormulaBounds(formula: String): Pair<Int, Int> {
        val f = formula.lowercase().replace(" ", "")
        return try {
            when {
                f.contains("4d6kh3") -> 3 to 18
                f.contains("3d6*5") -> 15 to 90
                f.contains("2d6+6*5") || f.contains("(2d6+6)*5") -> 40 to 90
                f.contains("d") -> {
                    val dicePart = f.substringBefore("+").substringBefore("-").substringBefore("*").trim()
                    val parts = dicePart.split("d")
                    val n = parts[0].toIntOrNull() ?: 1
                    val m = parts[1].filter { it.isDigit() }.toIntOrNull() ?: 6
                    
                    var minVal = n * 1
                    var maxVal = n * m
                    
                    if (f.contains("+")) {
                        val bonus = f.substringAfter("+").substringBefore("*").filter { it.isDigit() }.toIntOrNull() ?: 0
                        minVal += bonus
                        maxVal += bonus
                    } else if (f.contains("-")) {
                        val sub = f.substringAfter("-").substringBefore("*").filter { it.isDigit() }.toIntOrNull() ?: 0
                        minVal = (minVal - sub).coerceAtLeast(0)
                        maxVal = (maxVal - sub).coerceAtLeast(0)
                    }
                    
                    if (f.contains("*")) {
                        val mult = f.substringAfter("*").filter { it.isDigit() }.toIntOrNull() ?: 1
                        minVal *= mult
                        maxVal *= mult
                    }
                    
                    minVal to maxVal
                }
                else -> {
                    val v = f.toIntOrNull() ?: 0
                    v to v
                }
            }
        } catch (e: Exception) {
            0 to 100
        }
    }

    /**
     * 切换分配模式
     */
    fun setAllocationMode(mode: AllocationMode) {
        val currentState = uiState.value
        // 切换模式时重置属性，刷新可用点数与审查状态
        if (mode == AllocationMode.POINT_BUY) {
            init(currentState.campaignId, currentState.rulesetId, mode)
        } else {
            updateState { it.copy(
                allocationMode = mode,
                validationErrors = validateStats(it.stats, it.schema)
            ) }
        }
    }

    /**
     * 随机掷骰生成属性
     */
    fun rollStats() {
        val currentState = uiState.value
        val schema = currentState.schema
        val newStats = currentState.stats.toMutableMap()
        val rid = currentState.rulesetId
        
        // 1. 根据 Schema 滚动特定字段 (如 HP)
        schema?.fields?.forEach { field ->
            if (field is xyz.sakulik.d20.app.domain.rules.dynamic.DiceRollField) {
                newStats[field.id] = evaluateFormula(field.formula)
            }
        }
        
        // 2. 针对已知规则系统的核心属性重掷 (D&D 5e / CoC 7e)
        android.util.Log.d("CreationVM", "Rerolling for $rid")
        val lowerRid = rid.lowercase()
        when {
            lowerRid.contains("dnd_5e") -> {
                listOf("str", "dex", "con", "int", "wis", "cha").forEach { stat ->
                    // 4d6 取最高 3 个 (4d6kh3)
                    newStats[stat] = (1..4).map { (1..6).random() }.sorted().takeLast(3).sum()
                }
            }
            lowerRid.contains("coc_7e") -> {
                listOf("str", "dex", "con", "app", "pow", "siz", "int", "edu").forEach { stat ->
                    newStats[stat] = when (stat) {
                        "siz", "int", "edu" -> ((1..2).map { (1..6).random() }.sum() + 6) * 5
                        else -> (1..3).map { (1..6).random() }.sum() * 5
                    }
                }
            }
        }

        updateState { it.copy(
            stats = newStats, 
            remainingPoints = it.totalPoints - calculateSpentPoints(newStats, schema),
            allocationMode = AllocationMode.ROLLING,
            validationErrors = validateStats(newStats, schema)
        ) } 
    }

    private fun evaluateFormula(formula: String): Int {
        android.util.Log.d("CreationVM", "Evaluating formula: $formula")
        return try {
            if (formula.contains("d")) {
                val dicePart = formula.substringBefore("+").substringBefore("*").trim()
                val parts = dicePart.split("d")
                val n = parts[0].toIntOrNull() ?: 1
                val m = parts[1].filter { it.isDigit() }.toIntOrNull() ?: 6
                
                val base = (1..n).map { (1..m).random() }.sum()
                
                if (formula.contains("+")) {
                    val bonus = formula.substringAfter("+").trim().toIntOrNull() ?: 0
                    base + bonus
                } else if (formula.contains("*")) {
                    val multiplier = formula.substringAfter("*").trim().toIntOrNull() ?: 1
                    base * multiplier
                } else {
                    base
                }
            } else {
                formula.toIntOrNull() ?: 0
            }
        } catch (e: Exception) {
            0
        }
    }

    /**
     * AI 辅助生成
     */
    fun generateWithAi(description: String) {
        if (uiState.value.isAiGenerating) return
        val baseUrl = keyManager.getBaseUrl()
        val currentState = uiState.value
        val rid = currentState.rulesetId
        val narrativeFields = currentState.schema?.fields.orEmpty().filter { field ->
            field is xyz.sakulik.d20.app.domain.rules.dynamic.StringInputField ||
                field is xyz.sakulik.d20.app.domain.rules.dynamic.DropdownField
        }
        val narrativeFieldIds = narrativeFields.mapTo(mutableSetOf()) { it.id }
        val promptInjection = narrativeFields.joinToString(", ") { field ->
            if (field is xyz.sakulik.d20.app.domain.rules.dynamic.DropdownField) {
                "${field.label} (${field.id}；可选：${field.options.joinToString("/")})"
            } else {
                "${field.label} (${field.id})"
            }
        }.let { fieldDescription ->
            "stats 只可使用这些叙事字段：$fieldDescription。" +
                "只填写适用于该角色的字段；不要输出点数、掷骰、生命值或其他数值字段。"
        }
        val requestId = ++aiGenerationRequestId
        updateState { it.copy(isAiGenerating = true, error = null) }
        aiGenerationJob = viewModelScope.launch {
            try {
                repository.generateCharacter(baseUrl, description, rid, promptInjection).collect { state ->
                    when (state) {
                        is xyz.sakulik.d20.app.data.model.CharacterGenState.Loading -> Unit
                        is xyz.sakulik.d20.app.data.model.CharacterGenState.Success -> {
                            val narrativeStats = state.data.stats.filterKeys {
                                it in narrativeFieldIds
                            }.mapValues { (_, value) ->
                                if (value is JsonPrimitive) value.content else value.toString().removeSurrounding("\"")
                            }.toMutableMap()

                            if (!narrativeStats.containsKey("name")) {
                                narrativeStats["name"] = state.data.name
                            }
                            val generatedItems = state.data.items.take(MAX_AI_GENERATED_ITEMS)
                                .mapNotNull(::sanitizeGeneratedItem)
                            val rejectedItemCount = state.data.items.size - generatedItems.size

                            updateState { prev ->
                                val baseStats = prev.stats.toMutableMap()
                                baseStats.putAll(narrativeStats)

                                prev.copy(
                                    allocationMode = AllocationMode.AI_GEN,
                                    characterName = state.data.name,
                                    stats = baseStats,
                                    bio = state.data.bio,
                                    generatedItems = generatedItems,
                                    remainingPoints = 0
                                )
                            }

                            if (rejectedItemCount > 0) {
                                sendEvent(
                                    CreationUiEvent.Notice(
                                        "AI 返回的 $rejectedItemCount 件规则装备不可用，已自动忽略"
                                    )
                                )
                            }

                            rollStats()
                            updateState { it.copy(allocationMode = AllocationMode.ROLLING) }
                        }
                        is xyz.sakulik.d20.app.data.model.CharacterGenState.Error -> {
                            updateState { it.copy(error = state.throwable.message) }
                            sendEvent(CreationUiEvent.Error("AI 生成失败: ${state.throwable.message}"))
                        }
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } finally {
                if (aiGenerationRequestId == requestId) {
                    updateState { it.copy(isAiGenerating = false) }
                    aiGenerationJob = null
                }
            }
        }
    }

    fun cancelAiGeneration() {
        aiGenerationRequestId += 1
        aiGenerationJob?.cancel()
        aiGenerationJob = null
        updateState { it.copy(isAiGenerating = false, error = null) }
    }

    private fun sanitizeGeneratedItem(
        item: xyz.sakulik.d20.app.data.model.ItemGenModel
    ): xyz.sakulik.d20.app.data.model.ItemGenModel? {
        val sanitized = item.copy(
            name = item.name.trim().take(40),
            description = item.description.trim().take(120),
            category = item.category.trim().take(20).ifBlank { "道具" },
            modifiers = item.modifiers.entries.take(10).associate { it.toPair() }
        )
        if (sanitized.name.isBlank()) return null
        val draft = ItemEntity(
            id = "ai-draft",
            campaignId = uiState.value.campaignId,
            name = sanitized.name,
            description = sanitized.description,
            category = sanitized.category,
            modifiers = sanitized.modifiers.mapValues { (_, value) ->
                if (value is JsonPrimitive) value.content else value.toString().removeSurrounding("\"")
            },
            isEquipped = true
        )
        val isWeapon = sanitized.category.contains("武器", ignoreCase = true) ||
            sanitized.category.contains("weapon", ignoreCase = true)
        val isSpell = sanitized.category.contains("法术", ignoreCase = true) ||
            sanitized.category.contains("spell", ignoreCase = true)
        return when {
            isWeapon && draft.toWeaponProfileOrNull() == null -> null
            isSpell -> null
            else -> sanitized
        }
    }

    /**
     * 字段值变更逻辑 (用于步进器、文本输入等)
     */
    fun updateField(fieldId: String, newValue: Any) {
        val currentState = uiState.value
        val schema = currentState.schema
        val field = schema?.fields?.find { it.id == fieldId }

        val processedValue: Any = if (field is xyz.sakulik.d20.app.domain.rules.dynamic.PointBuyField || field is xyz.sakulik.d20.app.domain.rules.dynamic.DiceRollField) {
            newValue.toString().toIntOrNull() ?: 0
        } else {
            newValue
        }

        val newStats = currentState.stats.toMutableMap().apply { 
            put(fieldId, processedValue) 
        }

        val spent = calculateSpentPoints(newStats, schema)
        val remaining = (schema?.totalPoints ?: 0) - spent

        updateState { it.copy(
            stats = newStats,
            visibleFields = calculateVisibleFields(newStats, schema, currentState.rulesetId),
            remainingPoints = remaining,
            validationErrors = validateStats(newStats, schema)
        ) }
    }

    /**
     * 属性调整逻辑 (Legacy Adapter)
     */
    fun updateStat(statName: String, delta: Int) {
        val currentState = uiState.value
        val currentVal = (currentState.stats[statName] as? Number)?.toInt() ?: return
        updateField(statName, currentVal + delta)
    }

    /**
     * 手动输入数值 (Legacy Adapter)
     */
    fun setStatValue(statName: String, newValue: Int) {
        updateField(statName, newValue)
    }


    /**
     * 最终保存角色卡
     */
    fun saveCharacter(currentName: String) {
        val name = currentName.ifBlank { uiState.value.stats["name"]?.toString() ?: "" }
        if (name.isBlank()) {
            sendEvent(CreationUiEvent.Error("请输入角色姓名"))
            return
        }

        val state = uiState.value
        // 数值审查未通过，拒绝保存
        if (state.validationErrors.isNotEmpty()) {
            sendEvent(CreationUiEvent.Error("请先修正数值审查错误: ${state.validationErrors.first()}"))
            return
        }
        
        viewModelScope.launch {
            updateState { it.copy(isSaving = true) }
            try {
                // 1. 保存到数据库
                val charId = java.util.UUID.randomUUID().toString()
                val char = CharacterEntity(
                    id = charId,
                    campaignId = state.campaignId,
                    name = name,
                    stats = state.stats.mapValues { (_, value) ->
                        if (value is JsonPrimitive) value.content else value.toString().removeSurrounding("\"")
                    },
                    activeSystem = state.rulesetId
                )
                characterDao.insertCharacter(char)

                // 2. 保存初始装备
                state.generatedItems.forEach { item ->
                    inventoryRepository.addItem(xyz.sakulik.d20.app.data.local.ItemEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        campaignId = state.campaignId,
                        name = item.name,
                        description = item.description,
                        category = item.category,
                        modifiers = item.modifiers.mapValues { (_, v) -> 
                            if (v is JsonPrimitive) v.content else v.toString().removeSurrounding("\"")
                        },
                        isEquipped = true
                    ))
                }

                // 3. 发送系统通知消息给 LLM 背景
                val statsDesc = state.stats.entries.joinToString(", ") { "${it.key}:${it.value}" }
                messageDao.insertMessage(MessageEntity(
                    campaignId = state.campaignId,
                    role = "assistant",
                    content = "[系统提示：玩家已完成创卡。姓名：$name，初始属性：$statsDesc]",
                    isHidden = true
                ))

                sendEvent(CreationUiEvent.Success)
            } catch (e: Exception) {
                sendEvent(CreationUiEvent.Error(e.message ?: "保存失败"))
            } finally {
                updateState { it.copy(isSaving = false) }
            }
        }
    }

    fun addCustomItem(
        name: String,
        category: String,
        description: String,
        ruleParameters: String = ""
    ): Boolean {
        if (name.isBlank()) return false
        val modifiers = mutableMapOf<String, JsonElement>()
        ruleParameters.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) {
                    sendEvent(CreationUiEvent.Error("规则参数格式错误：$line；请使用 key=value"))
                    return false
                }
                val key = line.take(separator).trim()
                val value = line.drop(separator + 1).trim()
                if (key.isBlank() || value.isBlank()) {
                    sendEvent(CreationUiEvent.Error("规则参数格式错误：$line；键和值都不能为空"))
                    return false
                }
                modifiers[key] = JsonPrimitive(value)
            }
        if (uiState.value.rulesetId.contains("dnd_5e", ignoreCase = true)) {
            val stringModifiers = modifiers.mapValues { (_, value) -> value.jsonPrimitive.content }
            val draft = ItemEntity(
                id = "draft",
                campaignId = uiState.value.campaignId,
                name = name.trim(),
                description = description.trim(),
                category = category,
                modifiers = stringModifiers,
                isEquipped = true
            )
            val isWeapon = category.contains("武器", ignoreCase = true) ||
                category.contains("weapon", ignoreCase = true)
            val isSpell = category.contains("法术", ignoreCase = true) ||
                category.contains("spell", ignoreCase = true)
            if (isWeapon && draft.toWeaponProfileOrNull() == null) {
                sendEvent(CreationUiEvent.Error("武器规则不完整：至少需要 damage_formula 和 damage_type"))
                return false
            }
            if (isSpell && draft.toSpellProfileOrNull() == null) {
                sendEvent(CreationUiEvent.Error("法术规则不完整：请检查 resolution_type、环级及对应效果字段"))
                return false
            }
        }
        val newItem = xyz.sakulik.d20.app.data.model.ItemGenModel(
            name = name.trim(),
            category = category.ifBlank { "道具" },
            description = description.trim(),
            modifiers = modifiers
        )
        val updatedList = uiState.value.generatedItems + newItem
        updateState { it.copy(generatedItems = updatedList) }
        return true
    }

    fun removeCustomItem(item: xyz.sakulik.d20.app.data.model.ItemGenModel) {
        val updatedList = uiState.value.generatedItems - item
        updateState { it.copy(generatedItems = updatedList) }
    }

    private companion object {
        const val MAX_AI_GENERATED_ITEMS = 5
    }
}
