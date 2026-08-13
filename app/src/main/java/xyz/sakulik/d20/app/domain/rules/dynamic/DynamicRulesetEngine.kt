@file:OptIn(kotlinx.serialization.InternalSerializationApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)

package xyz.sakulik.d20.app.domain.rules.dynamic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xyz.sakulik.d20.app.domain.worldview.WorldviewManifest
import xyz.sakulik.d20.app.domain.worldview.WorldviewProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.ExperimentalSerializationApi

// ==========================================
// 模块 1：云端规则包协议定义
// ==========================================

@Serializable
data class RulesetManifest(
    val id: String,
    val name: String,
    val description: String? = null,
    val version: String,
    val systemPromptInjection: SystemPromptInjection,
    val characterTemplate: CharacterTemplate,
    val uiBlueprint: UiBlueprint,
    val creationSchema: CreationSchema? = null,
    val worldviewPresets: List<WorldviewManifest> = emptyList(),
    val quickActions: List<QuickActionDefinition> = emptyList(),
    val checkRules: CheckRules = CheckRules(),
    val combatRules: CombatRules = CombatRules(),
    val mechanicsPipeline: MechanicsPipeline
)

@Serializable
data class QuickActionDefinition(
    val id: String,
    val label: String,
    val description: String = "",
    val kind: QuickActionKind,
    val availability: QuickActionAvailability = QuickActionAvailability.ALWAYS,
    val payload: String = ""
)

@Serializable
enum class QuickActionKind {
    NARRATIVE,
    LOCAL_RULE,
    END_TURN
}

@Serializable
enum class QuickActionAvailability {
    ALWAYS,
    OUT_OF_COMBAT,
    IN_COMBAT,
    PLAYER_TURN
}

fun QuickActionDefinition.isAvailable(combatActive: Boolean, isPlayerTurn: Boolean): Boolean {
    return when (availability) {
        QuickActionAvailability.ALWAYS -> true
        QuickActionAvailability.OUT_OF_COMBAT -> !combatActive
        QuickActionAvailability.IN_COMBAT -> combatActive
        QuickActionAvailability.PLAYER_TURN -> combatActive && isPlayerTurn
    }
}

@Serializable
data class CheckRules(
    val targetSource: String = "EVENT",
    val modifierSource: String = "EVENT",
    val equipmentBonusAppliesTo: String = "MODIFIER",
    val equipmentBonusActionIds: List<String> = emptyList(),
    val defaultActionId: String = "dynamic_roll",
    val requiredTargetActionIds: List<String> = emptyList(),
    val allowedDiceExpressions: Map<String, List<String>> = emptyMap(),
    val statAliases: Map<String, List<String>> = emptyMap(),
    val targetLabel: String = "DC"
)

@Serializable
data class CombatRules(
    val initiative: InitiativeRules? = null,
    val encounterProfiles: Map<String, EncounterProfile> = emptyMap(),
    val turnResources: Map<String, Int> = emptyMap(),
    val turnResourceLabels: Map<String, String> = emptyMap(),
    val actionCosts: Map<String, Map<String, Int>> = emptyMap(),
    val actionTimings: Map<String, String> = emptyMap(),
    val primaryActionResource: String? = null,
    val lifePolicy: String = "NONE",
    val localActionHandler: String = "NONE",
    val defeatAtZeroHp: Boolean = false
)

@Serializable
data class EncounterProfile(
    val initiative: Int = 0,
    val ac: Int = 0,
    val hp: Int = 1,
    val maxHp: Int = hp,
    val resistances: List<String> = emptyList(),
    val vulnerabilities: List<String> = emptyList(),
    val immunities: List<String> = emptyList(),
    val savingThrows: Map<String, Int> = emptyMap(),
    val attributes: Map<String, String> = emptyMap()
)

@Serializable
data class InitiativeRules(
    val diceExpression: String? = null,
    val statKey: String? = null,
    val statTransform: String = "NONE",
    val defaultValue: Int = 0,
    val label: String = "先攻"
)

// WorldviewPreset refined into WorldviewManifest in its own file

@Serializable
data class SystemPromptInjection(
    val prompt: String
)

@Serializable
data class CharacterTemplate(
    val defaultStats: Map<String, JsonElement>
)

@Serializable
data class UiBlueprint(
    val dicePanelType: String,
    val localizationTermMap: Map<String, String>
)

@Serializable
data class MechanicsPipeline(
    val entryNodeId: String,
    val nodes: Map<String, LogicNode>
)

// ==========================================
// 模块 1.1：创卡蓝图模型
// ==========================================

@Serializable
data class CreationSchema(
    val totalPoints: Int? = null,
    val fields: List<CreationField>
)

@Serializable
sealed class CreationField {
    abstract val id: String
    abstract val label: String
    abstract val required: Boolean
    abstract val visibilityCondition: String?
}

@Serializable
@SerialName("string")
data class StringInputField(
    override val id: String,
    override val label: String,
    override val required: Boolean = false,
    override val visibilityCondition: String? = null,
    val hint: String? = null
) : CreationField()

@Serializable
@SerialName("point_buy")
data class PointBuyField(
    override val id: String,
    override val label: String,
    override val required: Boolean = true,
    override val visibilityCondition: String? = null,
    val min: Int,
    val max: Int,
    val costMultiplier: Int = 1
) : CreationField()

@Serializable
@SerialName("dice_roll")
data class DiceRollField(
    override val id: String,
    override val label: String,
    override val required: Boolean = true,
    override val visibilityCondition: String? = null,
    val formula: String
) : CreationField()

@Serializable
@SerialName("dropdown")
data class DropdownField(
    override val id: String,
    override val label: String,
    override val required: Boolean = false,
    override val visibilityCondition: String? = null,
    val options: List<String>
) : CreationField()

// ==========================================
// 模块 2：逻辑流水线解析器
// ==========================================

@Serializable
sealed class LogicNode {
    abstract val nextNodeId: String?
}

@Serializable
@SerialName("roll")
data class RollNode(
    val diceFormula: String? = null,
    val count: Int = 1,
    val faces: Int = 20,
    val keep: String = "KEEP_ALL", // KEEP_ALL, KEEP_HIGHEST, KEEP_LOWest
    val explodeThreshold: Int? = null,
    val outputVariable: String = "roll_result",
    override val nextNodeId: String? = null
) : LogicNode()

@Serializable
@SerialName("switch")
data class SwitchNode(
    val variable: String,
    val cases: Map<String, String>,
    val defaultNodeId: String? = null,
    override val nextNodeId: String? = null // Usually unused in SwitchNode, as cases handle routing
) : LogicNode()

@Serializable
@SerialName("death_save")
data class DeathSaveNode(
    val rollVariable: String = "death_save_roll",
    val hpKey: String = "hp",
    val deathSavesKey: String = "deathSaves",
    val successNodeId: String? = null,
    val failureNodeId: String? = null,
    val stableNodeId: String? = null,
    val unconsciousNodeId: String? = null,
    override val nextNodeId: String? = null
) : LogicNode()

@Serializable
@SerialName("recover")
data class RecoveryNode(
    val copyValues: Map<String, String> = emptyMap(),
    val setValues: Map<String, String> = emptyMap(),
    val taggedResourceKeys: List<String> = emptyList(),
    val resetTags: List<String> = emptyList(),
    override val nextNodeId: String? = null
) : LogicNode()

@Serializable
@SerialName("consume_resource")
data class ConsumeResourceNode(
    val resourcePath: String, // e.g., "spell_slots.level_1"
    val amountSource: String = "constant:1",
    val failNodeId: String? = null,
    override val nextNodeId: String? = null
) : LogicNode()

@Serializable
@SerialName("targeted_attack")
data class TargetedAttackNode(
    val attackerId: String = "self",
    val targetIdSource: String, // intent:target_id
    val attackBonusSource: String, // stat:str_mod + stat:prof_bonus
    val damageFormula: String, // 1d8
    val damageType: String, // fire, slashing
    val outputVariable: String = "attack_result",
    val missNodeId: String? = null,
    val hitNodeId: String? = null,
    override val nextNodeId: String? = null
) : LogicNode()

@Serializable
@SerialName("condition")
data class ConditionNode(
    val leftOperandSource: String,
    val operator: String,
    val rightOperandSource: String,
    val trueNodeId: String?,
    val falseNodeId: String?,
    override val nextNodeId: String? = null
) : LogicNode()

@Serializable
@SerialName("math")
data class MathNode(
    val leftOperandSource: String,
    val operator: String,
    val rightOperandSource: String,
    val outputVariable: String,
    override val nextNodeId: String? = null
) : LogicNode()

@Serializable
@SerialName("effect")
data class EffectNode(
    val targetType: String, // "stat", "flag", "result_state"
    val targetKey: String,
    val operation: String, // "set", "add", "subtract"
    val valueSource: String,
    override val nextNodeId: String? = null
) : LogicNode()

// ==========================================
// 模块 3：双向协议模型
// ==========================================

data class CheckIntent(
    val actionId: String,
    val meta: Map<String, String>,
    val diceSubmission: DiceSubmission? = null
)

enum class ResultState {
    CRITICAL_SUCCESS,
    EXTREME_SUCCESS,
    HARD_SUCCESS,
    REGULAR_SUCCESS,
    SUCCESS,
    FAILURE,
    CRITICAL_FAILURE,
    UNKNOWN
}

data class EvaluationResult(
    val state: ResultState,
    val modifiedCharacterData: Map<String, Any>,
    val logs: List<String>,
    val diceTraces: Map<String, List<Int>> = emptyMap(),
    val resolvedValues: Map<String, Any> = emptyMap(),
    val errors: List<RuleError> = emptyList()
) {
    val isValid: Boolean
        get() = errors.isEmpty() && state != ResultState.UNKNOWN
}

data class DicePanelUiState(
    val type: String,
    val terms: Map<String, String>
)

// ==========================================
// 模块 4：核心接口 IRuleset 与 动态执行引擎
// ==========================================

interface IRuleset {
    val id: String
    val name: String
    val description: String?
    val version: String
    val creationSchema: CreationSchema?
    val worldviewPresets: List<WorldviewManifest>
    val quickActions: List<QuickActionDefinition>
    val checkRules: CheckRules
    val combatRules: CombatRules
    fun getLlmContext(): String
    fun getInitialCharacter(): Map<String, Any>
    fun buildUiState(intent: CheckIntent): DicePanelUiState
    fun executePipeline(intent: CheckIntent, character: Map<String, Any>): EvaluationResult
}

class DynamicRulesetImpl(private val manifest: RulesetManifest) : IRuleset {
    override val id: String = manifest.id
    override val name: String = manifest.name
    override val description: String? = manifest.description
    override val version: String = manifest.version
    override val creationSchema: CreationSchema? = manifest.creationSchema
    override val worldviewPresets: List<WorldviewManifest> = manifest.worldviewPresets
    override val quickActions: List<QuickActionDefinition> = manifest.quickActions
    override val checkRules: CheckRules = manifest.checkRules
    override val combatRules: CombatRules = manifest.combatRules

    override fun getLlmContext(): String = manifest.systemPromptInjection.prompt

    override fun getInitialCharacter(): Map<String, Any> {
        // 安全地将 JSON 元素反序列化为宽泛的 Map
        return manifest.characterTemplate.defaultStats.mapValues { 
            it.value.toString().removeSurrounding("\"") 
        }
    }

    override fun buildUiState(intent: CheckIntent): DicePanelUiState {
        return DicePanelUiState(
            type = manifest.uiBlueprint.dicePanelType,
            terms = manifest.uiBlueprint.localizationTermMap
        )
    }

    /**
     * 判定某个字段是否可见
     */
    fun evaluateVisibility(field: CreationField, character: Map<String, Any>): Boolean {
        val condition = field.visibilityCondition ?: return true
        
        // 简单语法解析：目前仅支持 "key == 'value'" 或 "key != 'value'"
        val regex = Regex("(\\w+)\\s*(==|!=)\\s*'([^']*)'")
        val match = regex.find(condition) ?: return true
        
        val varName = match.groupValues[1]
        val operator = match.groupValues[2]
        val expectedValue = match.groupValues[3]
        
        val actualValue = character[varName]?.toString() ?: ""
        
        return if (operator == "==") {
            actualValue == expectedValue
        } else {
            actualValue != expectedValue
        }
    }

    /**
     * 【核心】AST 流水线执行引擎
     */
    override fun executePipeline(intent: CheckIntent, character: Map<String, Any>): EvaluationResult {
        // 1. 初始化上下文环境
        val workingMemory = mutableMapOf<String, Any>()
        val modifiedCharacter = character.toMutableMap()
        val logs = mutableListOf<String>()
        val errors = mutableListOf<RuleError>()
        var currentState = ResultState.UNKNOWN
        var submissionConsumed = false

        val submission = intent.diceSubmission ?: intent.meta["raw_roll_injected"]
            ?.toIntOrNull()
            ?.let { legacyValue ->
                val expression = intent.meta["expression"].orEmpty().ifBlank { "1d20" }
                DiceSubmission.legacy(expression, legacyValue)
            }
        val expectedExpression = intent.meta["expression"].orEmpty().ifBlank { submission?.expression.orEmpty() }
        submission?.let { submitted ->
            if (expectedExpression.isBlank()) submitted.validate() else submitted.validateAgainst(expectedExpression)
        }?.let { error ->
            return EvaluationResult(
                state = ResultState.UNKNOWN,
                modifiedCharacterData = character,
                logs = listOf("规则输入无效: ${error.message}"),
                errors = listOf(error)
            )
        }

        // 2. 指向入口节点
        var currentNodeId: String? = manifest.mechanicsPipeline.entryNodeId
        
        // 防死循环守卫 (容错机制)
        var loopGuard = 0
        val MAX_ITERATIONS = 100

        logs.add("启动动态规则引擎：${manifest.id}，动作：${intent.actionId}")

        // 3. 遍历执行 AST
        while (currentNodeId != null && loopGuard < MAX_ITERATIONS) {
            loopGuard++
            val node = manifest.mechanicsPipeline.nodes[currentNodeId]
            
            if (node == null) {
                val error = RuleError(
                    code = "MISSING_RULE_NODE",
                    message = "找不到规则节点 $currentNodeId",
                    nodeId = currentNodeId
                )
                errors.add(error)
                logs.add("规则错误: ${error.message}")
                break
            }

            logs.add("-> 正在执行节点: $currentNodeId [${node::class.simpleName}]")

            try {
                when (node) {
                is RollNode -> {
                    val submittedRoll = if (!submissionConsumed) {
                        submission
                    } else {
                        null
                    } ?: throw RuleEvaluationException(
                        "MISSING_DICE_SUBMISSION",
                        "规则节点 $currentNodeId 缺少玩家骰子结果"
                    )
                    val nodeExpression = node.diceFormula ?: buildString {
                        append(node.count)
                        append('d')
                        append(node.faces)
                        when (node.keep) {
                            "KEEP_HIGHEST" -> append("kh1")
                            "KEEP_LOWEST" -> append("kl1")
                        }
                    }
                    submittedRoll.validateAgainst(nodeExpression)?.let { validationError ->
                        throw RuleEvaluationException(validationError.code, validationError.message)
                    }
                    submissionConsumed = true
                    val rolls = submittedRoll.terms.map { it.value }
                        .ifEmpty { listOf(submittedRoll.total) }
                    val result = submittedRoll.total.toFloat()
                    
                    workingMemory[node.outputVariable] = result
                    val traceKey = "${currentNodeId}_trace"
                    workingMemory[traceKey] = rolls
                    
                    logs.add("   使用玩家骰点: $rolls -> 结果: $result (保存到 ${node.outputVariable})")
                    currentNodeId = node.nextNodeId
                }
                
                is SwitchNode -> {
                    val value = resolveValue(node.variable, workingMemory, modifiedCharacter, intent).toString()
                    val targetId = node.cases[value] ?: node.defaultNodeId
                    logs.add("   分支跳转: 变量 [${node.variable}] 值为 \"$value\" -> 跳转至 [$targetId]")
                    currentNodeId = targetId
                }
                
                is MathNode -> {
                    val left = resolveValue(node.leftOperandSource, workingMemory, modifiedCharacter, intent)
                    val right = resolveValue(node.rightOperandSource, workingMemory, modifiedCharacter, intent)
                    val mathResult = evaluateMath(left, node.operator, right)
                    workingMemory[node.outputVariable] = mathResult
                    logs.add("   数学运算: $left ${node.operator} $right = $mathResult (保存到 ${node.outputVariable})")
                    currentNodeId = node.nextNodeId
                }
                
                is ConditionNode -> {
                    val left = resolveValue(node.leftOperandSource, workingMemory, modifiedCharacter, intent)
                    val right = resolveValue(node.rightOperandSource, workingMemory, modifiedCharacter, intent)
                    val conditionMet = evaluateCondition(left, node.operator, right)
                    
                    val branchPath = if (conditionMet) node.trueNodeId else node.falseNodeId
                    logs.add("   状态判定: $left ${node.operator} $right -> 结果为 $conditionMet (即将跳转至 $branchPath)")
                    currentNodeId = branchPath
                }
                
                is DeathSaveNode -> {
                    val savedState = runCatching {
                        Json.parseToJsonElement(
                            modifiedCharacter[node.deathSavesKey]?.toString().orEmpty()
                        ).jsonObject
                    }.getOrNull()
                    var successes = savedState?.get("successes")
                        ?.jsonPrimitive?.intOrNull?.coerceIn(0, 3) ?: 0
                    var failures = savedState?.get("failures")
                        ?.jsonPrimitive?.intOrNull?.coerceIn(0, 3) ?: 0
                    var isStable = savedState?.get("isStable")
                        ?.jsonPrimitive?.booleanOrNull ?: false

                    if (isStable || successes >= 3) {
                        modifiedCharacter[node.deathSavesKey] =
                            "{\"successes\":3,\"failures\":0,\"isStable\":true}"
                        currentNodeId = node.stableNodeId
                        logs.add("   角色已经稳定，不再进行死亡豁免。")
                        continue
                    }
                    if (failures >= 3) {
                        currentNodeId = node.failureNodeId
                        logs.add("   角色已经死亡，不再进行死亡豁免。")
                        continue
                    }

                    val submittedRoll = if (!submissionConsumed) {
                        submission
                    } else {
                        null
                    } ?: throw RuleEvaluationException(
                        "MISSING_DICE_SUBMISSION",
                        "死亡豁免缺少玩家骰子结果"
                    )
                    submittedRoll.validateAgainst("1d20")?.let { validationError ->
                        throw RuleEvaluationException(validationError.code, validationError.message)
                    }
                    submissionConsumed = true
                    val rollValue = submittedRoll.total
                    val traceKey = "${currentNodeId}_trace"
                    workingMemory[traceKey] = listOf(rollValue)
                    var revived = false
                    
                    when (rollValue) {
                        20 -> {
                            modifiedCharacter[node.hpKey] = "1"
                            successes = 0
                            failures = 0
                            isStable = false
                            revived = true
                            logs.add("   自然 20！奇迹苏醒，HP 恢复为 1。")
                            logs.add("[SYSTEM_REPORT: 角色在死亡豁免中掷出自然 20，伤口奇迹般止血，咳出一口积血后睁开眼睛。当前 HP: 1]")
                        }
                        1 -> {
                            failures += 2
                            currentState = ResultState.FAILURE
                            logs.add("   自然 1！伤势严重恶化，增加 2 次失败。")
                        }
                        in 10..19 -> {
                            successes += 1
                            currentState = ResultState.SUCCESS
                            logs.add("   成功！距离稳定又近了一步 ($successes/3)。")
                        }
                        else -> {
                            failures += 1
                            currentState = ResultState.FAILURE
                            logs.add("   失败！生命正在流逝 ($failures/3)。")
                        }
                    }
                    
                    val newDS = "{\"successes\":$successes,\"failures\":$failures,\"isStable\":$isStable}"
                    modifiedCharacter[node.deathSavesKey] = newDS
                    
                    if (revived) {
                        currentNodeId = node.stableNodeId
                    } else if (isStable) {
                        currentNodeId = node.stableNodeId
                    } else if (successes >= 3) {
                        modifiedCharacter[node.deathSavesKey] = "{\"successes\":3,\"failures\":0,\"isStable\":true}"
                        logs.add("   伤势稳定。")
                        currentNodeId = node.stableNodeId
                    } else if (failures >= 3) {
                         logs.add("   判定死亡。")
                         logs.add("[SYSTEM_REPORT: 角色死亡豁免累计 3 次失败，已彻底死亡。请描述英雄壮烈牺牲的最后一幕。]")
                         currentNodeId = node.failureNodeId
                    } else {
                        currentNodeId = node.nextNodeId
                    }
                }

                is ConsumeResourceNode -> {
                    val amount = resolveValue(node.amountSource, workingMemory, modifiedCharacter, intent).toString().toFloatOrNull()?.toInt() ?: 1
                    val resourcesJson = modifiedCharacter["resources"]?.toString() ?: "{}"
                    
                    try {
                        // 简单的路径提取逻辑 (针对示例格式)
                        val levelPart = node.resourcePath.substringAfterLast(".")
                        val regex = Regex("\"$levelPart\":\\s*\\{[^}]*\"current\":\\s*(\\d+)")
                        val match = regex.find(resourcesJson)
                        val curVal = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        
                        if (curVal >= amount) {
                            val newVal = curVal - amount
                            val targetSection = match?.value ?: ""
                            val currentValuePattern = Regex("\"current\"\\s*:\\s*$curVal")
                            val updatedSection = currentValuePattern.replaceFirst(
                                targetSection,
                                "\"current\":$newVal"
                            )
                            modifiedCharacter["resources"] = resourcesJson.replace(targetSection, updatedSection)
                            
                            logs.add("   资源消耗: [${node.resourcePath}] -$amount -> 残余: $newVal")
                            currentNodeId = node.nextNodeId
                        } else {
                            logs.add("   资源不足: [${node.resourcePath}] 剩余 $curVal，需 $amount")
                            logs.add("[SYSTEM_WARNING: 角色资源不足！由于 [${node.resourcePath}] (剩余 $curVal) 无法支付本次动作成本 ($amount)，法术施放失败。请以此结果进行叙事。]")
                            currentNodeId = node.failNodeId
                        }
                    } catch (e: Exception) {
                        logs.add("   资源路径解析错误: ${node.resourcePath}")
                        currentNodeId = node.failNodeId
                    }
                }

                is RecoveryNode -> {
                    node.copyValues.forEach { (targetKey, sourceKey) ->
                        modifiedCharacter[sourceKey]?.let { value ->
                            modifiedCharacter[targetKey] = value
                        }
                    }
                    node.setValues.forEach { (targetKey, value) ->
                        modifiedCharacter[targetKey] = value
                    }
                    node.taggedResourceKeys.forEach { resourceKey ->
                        modifiedCharacter[resourceKey]?.toString()?.let { resources ->
                            modifiedCharacter[resourceKey] = resetTaggedResources(
                                rawJson = resources,
                                resetTags = node.resetTags
                            )
                        }
                    }
                    logs.add("   规则包声明的恢复效果已应用。")
                    currentNodeId = node.nextNodeId
                }
                
                is TargetedAttackNode -> {
                    // 1. 命中判定
                    val bonus = resolveValue(node.attackBonusSource, workingMemory, modifiedCharacter, intent).toString().toFloatOrNull() ?: 0f
                    val submittedRoll = if (!submissionConsumed) {
                        submission
                    } else {
                        null
                    } ?: throw RuleEvaluationException(
                        "MISSING_DICE_SUBMISSION",
                        "攻击检定缺少玩家骰子结果"
                    )
                    submittedRoll.validateAgainst("1d20")?.let { validationError ->
                        throw RuleEvaluationException(validationError.code, validationError.message)
                    }
                    submissionConsumed = true
                    val d20 = submittedRoll.total
                    workingMemory["${currentNodeId}_trace"] = submittedRoll.terms
                        .map { it.value }
                        .ifEmpty { listOf(d20) }
                    val isCrit = d20 == 20
                    val isNaturalOne = d20 == 1
                    val totalHit = d20 + bonus
                    
                    val targetId = resolveValue(node.targetIdSource, workingMemory, modifiedCharacter, intent).toString()
                    val targetAc = intent.meta["target_ac"]?.toIntOrNull()
                    val targetResistances = intent.meta["target_resistances"].toDamageTypes()
                    val targetVulnerabilities = intent.meta["target_vulnerabilities"].toDamageTypes()
                    val targetImmunities = intent.meta["target_immunities"].toDamageTypes()
                    val normalizedDamageType = node.damageType.trim().lowercase()

                    if (targetId.isBlank() || targetAc == null) {
                        logs.add("   攻击缺少有效目标或目标 AC，拒绝使用模拟数据。")
                        currentState = ResultState.FAILURE
                        currentNodeId = node.missNodeId ?: node.nextNodeId
                        continue
                    }
                    
                    logs.add("   攻击判定 [$targetId]: 1d20($d20) + $bonus = $totalHit (目标 AC: $targetAc)")
                    
                    if (!isNaturalOne && (totalHit >= targetAc || isCrit)) {
                        val critMsg = if (isCrit) " (暴击！)" else ""
                        logs.add("   命中！$critMsg")
                        
                        // 2. 伤害掷骰
                        val counts = if (isCrit) 2 else 1
                        // 简化解析 D&D 5E 伤害公式 e.g., 1d8
                        val diceFaces = node.damageFormula.substringAfter("d").toIntOrNull() ?: 0
                        val rawDamageRolls = List(counts) { (1..diceFaces).random() }
                        val modifier = resolveValue("stat:str_mod", workingMemory, modifiedCharacter, intent).toString().toFloatOrNull() ?: 0f
                        var finalDamage = rawDamageRolls.sum() + modifier
                        
                        logs.add("   伤害掷骰 [${node.damageFormula}]: $rawDamageRolls + $modifier = $finalDamage")
                        
                        // 3. 抗性/免疫/易伤结算
                        if (targetImmunities.contains(normalizedDamageType)) {
                            finalDamage = 0f
                            logs.add("   目标对 [${node.damageType}] 免疫！伤害降为 0。")
                        } else if (targetResistances.contains(normalizedDamageType)) {
                            finalDamage = (finalDamage / 2).toInt().toFloat()
                            logs.add("   目标对 [${node.damageType}] 有抗性！伤害减半 -> $finalDamage")
                        } else if (targetVulnerabilities.contains(normalizedDamageType)) {
                            finalDamage *= 2
                            logs.add("   目标对 [${node.damageType}] 易伤！伤害翻倍 -> $finalDamage")
                        }
                        
                        workingMemory[node.outputVariable] = finalDamage
                        logs.add("[SYSTEM_REPORT: 攻击命中 [$targetId]！造成 $finalDamage 点 [${node.damageType}] 伤害。]")
                        currentNodeId = node.hitNodeId ?: node.nextNodeId
                    } else {
                        logs.add("   未命中。")
                        logs.add("[SYSTEM_REPORT: 攻击未命中 [$targetId]。]")
                        currentNodeId = node.missNodeId ?: node.nextNodeId
                    }
                }
                
                is EffectNode -> {
                    val value = resolveValue(node.valueSource, workingMemory, modifiedCharacter, intent)
                    when (node.targetType) {
                        "stat" -> {
                            val current = modifiedCharacter[node.targetKey].toString().toFloatOrNull() ?: 0f
                            val maxHp = modifiedCharacter["max_hp"].toString().toFloatOrNull() ?: 100f
                            val numVal = value.toString().toFloatOrNull() ?: 0f
                            
                            val newVal = when (node.operation) {
                                "add" -> current + numVal
                                "subtract" -> {
                                    val res = current - numVal
                                    // 即死检测
                                    if (res <= -maxHp) {
                                        logs.add("   💀 遭受巨量伤害 (${numVal} > ${current + maxHp})：角色当场毙命（即死）！")
                                        logs.add("[SYSTEM_REPORT: 遭受即死伤害，角色已彻底死亡。]")
                                    } else if (current > 0 && res <= 0) {
                                        logs.add("   昏迷：HP 降至 0。进入濒死状态。")
                                         modifiedCharacter["deathSaves"] = "{\"successes\":0,\"failures\":0,\"isStable\":false}"
                                    }
                                    res
                                }
                                else -> numVal
                            }
                            modifiedCharacter[node.targetKey] = if (newVal % 1 == 0f) newVal.toInt().toString() else newVal.toString()
                            logs.add("   💉 副作用生效: 属性 [${node.targetKey}] ${node.operation} $value -> 当前值: ${modifiedCharacter[node.targetKey]}")
                        }
                        "result_state" -> {
                            // 动态更迭最终结算状态
                            currentState = try {
                                ResultState.valueOf(value.toString().uppercase())
                            } catch (e: Exception) {
                                logs.add("   ⚠️ 解析 ResultState 失败: $value，回退为 UNKNOWN")
                                ResultState.UNKNOWN
                            }
                            logs.add("   🚩 状态更变: 判定最终为 $currentState")
                        }
                    }
                    currentNodeId = node.nextNodeId
                }
            }
            } catch (error: RuleEvaluationException) {
                val ruleError = RuleError(
                    code = error.code,
                    message = error.message.orEmpty(),
                    nodeId = currentNodeId
                )
                errors.add(ruleError)
                logs.add("规则错误: ${ruleError.message}")
                break
            }
        }

        if (loopGuard >= MAX_ITERATIONS) {
            val error = RuleError(
                code = "RULE_LOOP_LIMIT",
                message = "规则流水线超过最大执行步数，可能存在循环",
                nodeId = currentNodeId
            )
            errors.add(error)
            logs.add("规则错误: ${error.message}")
        }

        if (currentState == ResultState.UNKNOWN && errors.isEmpty()) {
            val error = RuleError(
                code = "UNKNOWN_RULE_RESULT",
                message = "规则流水线结束时没有产生明确结果",
                nodeId = currentNodeId
            )
            errors.add(error)
            logs.add("规则错误: ${error.message}")
        }

        @Suppress("UNCHECKED_CAST")
        val diceTraces = workingMemory.filter { it.key.endsWith("_trace") }
            .mapValues { it.value as List<Int> }

        return EvaluationResult(
            state = currentState,
            modifiedCharacterData = if (errors.isEmpty()) modifiedCharacter else character,
            logs = logs,
            diceTraces = diceTraces,
            resolvedValues = workingMemory,
            errors = errors
        )
    }

    private fun resetTaggedResources(rawJson: String, resetTags: List<String>): String {
        val root = runCatching { Json.parseToJsonElement(rawJson) }.getOrNull() ?: return rawJson
        val allowedTags = resetTags.mapTo(mutableSetOf()) { it.trim().lowercase() }

        fun reset(element: JsonElement): JsonElement {
            val objectValue = element as? JsonObject ?: return element
            val resetTag = objectValue["reset_on"]?.jsonPrimitive?.contentOrNull?.lowercase()
            val maxValue = objectValue["max"]?.jsonPrimitive?.intOrNull
            val transformed = objectValue.mapValues { (_, child) -> reset(child) }.toMutableMap()
            if (resetTag in allowedTags && maxValue != null && "current" in objectValue) {
                transformed["current"] = JsonPrimitive(maxValue)
            }
            return JsonObject(transformed)
        }

        return reset(root).toString()
    }

    /**
     * 高阶容错的数据源解析器
     */
    private fun resolveValue(
        sourceStr: String, 
        memory: Map<String, Any>, 
        character: Map<String, Any>, 
        intent: CheckIntent
    ): Any {
        val parts = sourceStr.split(":", limit = 2)
        if (parts.size < 2) {
            throw RuleEvaluationException("INVALID_VALUE_SOURCE", "非法规则数据源 $sourceStr")
        }
        val type = parts[0]
        val key = parts[1]

        return when (type) {
            "constant" -> key.toFloatOrNull() ?: key
            "variable" -> memory[key] ?: throw RuleEvaluationException(
                "MISSING_RULE_VARIABLE",
                "规则变量 $key 尚未生成"
            )
            "stat" -> character[key] ?: throw RuleEvaluationException(
                "MISSING_CHARACTER_STAT",
                "角色缺少规则所需属性 $key"
            )
            "intent" -> if (key == "actionId") {
                intent.actionId
            } else {
                intent.meta[key]
                    ?.takeUnless(String::isBlank)
                    ?: throw RuleEvaluationException(
                        "MISSING_INTENT_VALUE",
                        "动作缺少规则所需参数 $key"
                    )
            }
            else -> throw RuleEvaluationException(
                "UNKNOWN_VALUE_SOURCE",
                "规则使用了未知数据源类型 $type"
            )
        }
    }

    /**
     * 极简数学解析
     */
    private fun evaluateMath(left: Any, op: String, right: Any): Float {
        val l = left.toString().toFloatOrNull()
            ?: throw RuleEvaluationException("INVALID_NUMBER", "$left 不是有效数字")
        val r = right.toString().toFloatOrNull()
            ?: throw RuleEvaluationException("INVALID_NUMBER", "$right 不是有效数字")
        return when (op) {
            "+" -> l + r
            "-" -> l - r
            "*" -> l * r
            "/" -> if (r != 0f) l / r else throw RuleEvaluationException(
                "DIVISION_BY_ZERO",
                "规则数学运算发生除零"
            )
            else -> throw RuleEvaluationException("UNKNOWN_MATH_OPERATOR", "未知数学运算符 $op")
        }
    }

    /**
     * 条件比较解析
     */
    private fun evaluateCondition(left: Any, op: String, right: Any): Boolean {
        // 尝试按照数值比较
        val ln = left.toString().toFloatOrNull()
        val rn = right.toString().toFloatOrNull()
        
        if (ln != null && rn != null) {
            return when (op) {
                ">=" -> ln >= rn
                ">" -> ln > rn
                "<=" -> ln <= rn
                "<" -> ln < rn
                "==" -> ln == rn
                "!=" -> ln != rn
                else -> throw RuleEvaluationException(
                    "UNKNOWN_CONDITION_OPERATOR",
                    "未知比较运算符 $op"
                )
            }
        }
        
        // 降级为字符串比较
        val ls = left.toString()
        val rs = right.toString()
        return when (op) {
            "==" -> ls == rs
            "!=" -> ls != rs
            else -> throw RuleEvaluationException(
                "INVALID_STRING_COMPARISON",
                "非数值只能使用 == 或 != 比较，实际为 $op"
            )
        }
    }

    private fun String?.toDamageTypes(): Set<String> {
        return this.orEmpty()
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
    }
}

private class RuleEvaluationException(
    val code: String,
    message: String
) : IllegalStateException(message)

// ==========================================
// 模块 5：系统工厂
// ==========================================

@OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
object RulesetProvider {
    sealed interface ParseResult {
        data class Success(val ruleset: IRuleset) : ParseResult
        data class Invalid(val errors: List<RuleError>) : ParseResult
    }

    
    // 注入多态模块，支持 sealed class 序列化
    private val rulesetModule = SerializersModule {
        polymorphic(LogicNode::class) {
            subclass(RollNode::class)
            subclass(SwitchNode::class)
            subclass(DeathSaveNode::class)
            subclass(ConsumeResourceNode::class)
            subclass(TargetedAttackNode::class)
            subclass(RecoveryNode::class)
            subclass(ConditionNode::class)
            subclass(MathNode::class)
            subclass(EffectNode::class)
        }
        polymorphic(CreationField::class) {
            subclass(StringInputField::class)
            subclass(PointBuyField::class)
            subclass(DiceRollField::class)
            subclass(DropdownField::class)
        }
    }

    private val jsonConfig = Json {
        ignoreUnknownKeys = false
        serializersModule = rulesetModule
        classDiscriminator = "type" // 依据 JSON 里的 "type" 字段做动态派发
    }

    fun parseManifest(jsonString: String): IRuleset? {
        return when (val result = parseManifestDetailed(jsonString)) {
            is ParseResult.Success -> result.ruleset
            is ParseResult.Invalid -> null
        }
    }

    fun parseManifestDetailed(jsonString: String): ParseResult {
        return try {
            val manifest = jsonConfig.decodeFromString<RulesetManifest>(jsonString)
            val validationErrors = validateManifest(manifest)
            if (validationErrors.isNotEmpty()) {
                ParseResult.Invalid(validationErrors)
            } else {
                ParseResult.Success(DynamicRulesetImpl(manifest))
            }
        } catch (e: Exception) {
            ParseResult.Invalid(
                listOf(
                    RuleError(
                        code = "INVALID_RULESET_JSON",
                        message = e.message ?: "规则包 JSON 无法解析"
                    )
                )
            )
        }
    }

    internal fun validateManifest(manifest: RulesetManifest): List<RuleError> {
        val nodes = manifest.mechanicsPipeline.nodes
        val errors = mutableListOf<RuleError>()
        val combatRules = manifest.combatRules
        val checkRules = manifest.checkRules
        val supportedTargetSources = setOf("EVENT", "STAT_VALUE")
        val supportedModifierSources = setOf("EVENT", "ABILITY_MODIFIER", "NONE")
        val supportedEquipmentBonusTargets = setOf("MODIFIER", "TARGET", "NONE")
        val duplicateWorldviewIds = manifest.worldviewPresets
            .groupingBy(WorldviewManifest::id)
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        val duplicateQuickActionIds = manifest.quickActions
            .groupingBy(QuickActionDefinition::id)
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        if (manifest.quickActions.size > 20) {
            errors.add(
                RuleError(
                    code = "TOO_MANY_QUICK_ACTIONS",
                    message = "规则包快捷行动不能超过 20 个"
                )
            )
        }
        if (duplicateQuickActionIds.isNotEmpty()) {
            errors.add(
                RuleError(
                    code = "DUPLICATE_QUICK_ACTION",
                    message = "规则包快捷行动 ID 重复：${duplicateQuickActionIds.sorted().joinToString()}"
                )
            )
        }
        manifest.quickActions.forEach { action ->
            val payloadValid = when (action.kind) {
                QuickActionKind.NARRATIVE -> action.payload.isNotBlank() && action.payload.length <= 500
                QuickActionKind.LOCAL_RULE -> SAFE_RULE_ID.matches(action.payload) &&
                    nodes.values.filterIsInstance<SwitchNode>().any { switch ->
                        action.payload in switch.cases
                    }
                QuickActionKind.END_TURN -> action.payload.isBlank() &&
                    action.availability == QuickActionAvailability.PLAYER_TURN
            }
            if (
                !SAFE_RULE_ID.matches(action.id) ||
                action.label.isBlank() || action.label.length > 40 ||
                action.description.length > 160 ||
                !payloadValid
            ) {
                errors.add(
                    RuleError(
                        code = "INVALID_QUICK_ACTION",
                        message = "快捷行动 ${action.id} 的 ID、文案或负载无效"
                    )
                )
            }
        }
        if (duplicateWorldviewIds.isNotEmpty()) {
            errors.add(
                RuleError(
                    code = "DUPLICATE_WORLDVIEW_PRESET",
                    message = "规则包内置设定 ID 重复：${duplicateWorldviewIds.sorted().joinToString()}"
                )
            )
        }
        manifest.worldviewPresets.forEach { preset ->
            when {
                !WorldviewProvider.isValidManifest(preset) -> errors.add(
                    RuleError(
                        code = "INVALID_WORLDVIEW_PRESET",
                        message = "规则包内置设定 ${preset.id} 格式无效或尝试覆盖本地机制"
                    )
                )
                !WorldviewProvider.isCompatibleWith(preset, manifest.id) -> errors.add(
                    RuleError(
                        code = "INCOMPATIBLE_WORLDVIEW_PRESET",
                        message = "规则包内置设定 ${preset.id} 未声明兼容 ${manifest.id}"
                    )
                )
            }
        }
        if (checkRules.targetSource.trim().uppercase() !in supportedTargetSources) {
            errors.add(
                RuleError(
                    code = "UNSUPPORTED_CHECK_TARGET_SOURCE",
                    message = "检定目标来源 ${checkRules.targetSource} 不受支持"
                )
            )
        }
        if (checkRules.modifierSource.trim().uppercase() !in supportedModifierSources) {
            errors.add(
                RuleError(
                    code = "UNSUPPORTED_CHECK_MODIFIER_SOURCE",
                    message = "检定修正来源 ${checkRules.modifierSource} 不受支持"
                )
            )
        }
        if (checkRules.equipmentBonusAppliesTo.trim().uppercase() !in supportedEquipmentBonusTargets) {
            errors.add(
                RuleError(
                    code = "UNSUPPORTED_EQUIPMENT_BONUS_TARGET",
                    message = "装备加值落点 ${checkRules.equipmentBonusAppliesTo} 不受支持"
                )
            )
        }
        if (checkRules.defaultActionId.isBlank()) {
            errors.add(
                RuleError(
                    code = "MISSING_DEFAULT_CHECK_ACTION",
                    message = "默认检定动作 ID 不能为空"
                )
            )
        } else if (checkRules.defaultActionId !in checkRules.allowedDiceExpressions) {
            errors.add(
                RuleError(
                    code = "MISSING_DEFAULT_CHECK_DICE_POLICY",
                    message = "默认动作 ${checkRules.defaultActionId} 必须声明允许骰式"
                )
            )
        }
        checkRules.allowedDiceExpressions.forEach { (actionId, expressions) ->
            when {
                !SAFE_RULE_ID.matches(actionId) -> errors.add(
                    RuleError(
                        code = "INVALID_CHECK_DICE_POLICY",
                        message = "骰式白名单动作 ID $actionId 格式无效"
                    )
                )
                expressions.isEmpty() || expressions.size > 20 -> errors.add(
                    RuleError(
                        code = "INVALID_CHECK_DICE_POLICY",
                        message = "动作 $actionId 必须声明 1 至 20 个允许骰式"
                    )
                )
                expressions.any { expression ->
                    expression.isBlank() || expression.length > 64 ||
                        !SAFE_DICE_EXPRESSION.matches(normalizeDicePolicyExpression(expression))
                } -> errors.add(
                    RuleError(
                        code = "INVALID_CHECK_DICE_POLICY",
                        message = "动作 $actionId 包含不安全的允许骰式"
                    )
                )
            }
        }
        val supportedTransforms = setOf("NONE", "RAW_VALUE", "ABILITY_MODIFIER")
        val supportedLifePolicies = setOf("NONE", "DND_5E")
        val supportedLocalHandlers = setOf("NONE", "DND_5E")
        val supportedActionTimings = setOf("ANY", "PARTICIPANT_TURN")
        combatRules.initiative?.statTransform?.trim()?.uppercase()
            ?.takeIf { it !in supportedTransforms }
            ?.let { transform ->
                errors.add(
                    RuleError(
                        code = "UNSUPPORTED_INITIATIVE_TRANSFORM",
                        message = "战斗先攻使用了不受支持的属性变换 $transform"
                    )
                )
            }
        combatRules.lifePolicy.trim().uppercase()
            .takeIf { it !in supportedLifePolicies }
            ?.let { policy ->
                errors.add(
                    RuleError(
                        code = "UNSUPPORTED_LIFE_POLICY",
                        message = "战斗生命策略 $policy 未在本地注册"
                    )
                )
            }
        combatRules.localActionHandler.trim().uppercase()
            .takeIf { it !in supportedLocalHandlers }
            ?.let { handler ->
                errors.add(
                    RuleError(
                        code = "UNSUPPORTED_LOCAL_ACTION_HANDLER",
                        message = "本地动作处理器 $handler 未在本地注册"
                    )
                )
            }
        combatRules.turnResources.filterValues { it < 0 }.keys.forEach { resource ->
            errors.add(
                RuleError(
                    code = "INVALID_TURN_RESOURCE",
                    message = "回合资源 $resource 的初始值不能为负数"
                )
            )
        }
        combatRules.actionCosts.forEach { (actionId, costs) ->
            costs.forEach { (resource, cost) ->
                if (resource !in combatRules.turnResources || cost <= 0) {
                    errors.add(
                        RuleError(
                            code = "INVALID_ACTION_COST",
                            message = "动作 $actionId 的资源成本 $resource=$cost 无效"
                        )
                    )
                }
            }
        }
        combatRules.actionTimings.forEach { (actionId, timing) ->
            if (timing.trim().uppercase() !in supportedActionTimings) {
                errors.add(
                    RuleError(
                        code = "UNSUPPORTED_ACTION_TIMING",
                        message = "动作 $actionId 使用了不受支持的时机 $timing"
                    )
                )
            }
        }
        combatRules.primaryActionResource?.let { resource ->
            if (resource !in combatRules.turnResources) {
                errors.add(
                    RuleError(
                        code = "INVALID_PRIMARY_ACTION_RESOURCE",
                        message = "主要行动资源 $resource 未在 turnResources 中声明"
                    )
                )
            }
        }
        combatRules.encounterProfiles.forEach { (profileId, profile) ->
            when {
                !SAFE_RULE_ID.matches(profileId) -> errors.add(
                    RuleError(
                        code = "INVALID_ENCOUNTER_PROFILE_ID",
                        message = "遭遇档案 ID $profileId 格式无效"
                    )
                )
                profile.initiative !in -1_000..1_000 -> errors.add(
                    RuleError(
                        code = "INVALID_ENCOUNTER_PROFILE",
                        message = "遭遇档案 $profileId 的先攻超出安全范围"
                    )
                )
                profile.ac !in 0..1_000 -> errors.add(
                    RuleError(
                        code = "INVALID_ENCOUNTER_PROFILE",
                        message = "遭遇档案 $profileId 的 AC 超出安全范围"
                    )
                )
                profile.hp !in 1..1_000_000 || profile.maxHp !in profile.hp..1_000_000 -> errors.add(
                    RuleError(
                        code = "INVALID_ENCOUNTER_PROFILE",
                        message = "遭遇档案 $profileId 的生命值无效"
                    )
                )
                profile.savingThrows.size > 50 ||
                    profile.savingThrows.values.any { it !in -1_000..1_000 } -> errors.add(
                    RuleError(
                        code = "INVALID_ENCOUNTER_PROFILE",
                        message = "遭遇档案 $profileId 的豁免数据无效"
                    )
                )
                profile.attributes.size > 50 ||
                    profile.attributes.any { (key, value) -> key.length > 64 || value.length > 500 } -> errors.add(
                    RuleError(
                        code = "INVALID_ENCOUNTER_PROFILE",
                        message = "遭遇档案 $profileId 的规则属性无效"
                    )
                )
                listOf(profile.resistances, profile.vulnerabilities, profile.immunities)
                    .any { values ->
                        values.size > 50 || values.any { it.isBlank() || it.length > 64 }
                    } -> errors.add(
                    RuleError(
                        code = "INVALID_ENCOUNTER_PROFILE",
                        message = "遭遇档案 $profileId 的伤害类型列表无效"
                    )
                )
            }
        }
        if (manifest.mechanicsPipeline.entryNodeId !in nodes) {
            errors.add(
                RuleError(
                    code = "MISSING_ENTRY_NODE",
                    message = "入口节点 ${manifest.mechanicsPipeline.entryNodeId} 不存在",
                    nodeId = manifest.mechanicsPipeline.entryNodeId
                )
            )
        }
        nodes.forEach { (nodeId, node) ->
            node.referencedNodeIds().forEach { targetId ->
                if (targetId !in nodes) {
                    errors.add(
                        RuleError(
                            code = "MISSING_REFERENCED_NODE",
                            message = "节点 $nodeId 引用了不存在的节点 $targetId",
                            nodeId = nodeId
                        )
                    )
                }
            }
        }
        return errors
    }

    private val SAFE_RULE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
    private val SAFE_DICE_EXPRESSION = Regex(
        "^(?:(?:[1-9]\\d?|100))?d(?:[1-9]\\d{0,3}|10000)(?:kh1|kl1)?(?:[+-]\\d{1,6})?$"
    )

    private fun normalizeDicePolicyExpression(expression: String): String =
        expression.filterNot(Char::isWhitespace).lowercase()

    private fun LogicNode.referencedNodeIds(): Set<String> {
        val references = mutableSetOf<String>()
        nextNodeId?.let(references::add)
        when (this) {
            is SwitchNode -> {
                references.addAll(cases.values)
                defaultNodeId?.let(references::add)
            }
            is ConditionNode -> {
                trueNodeId?.let(references::add)
                falseNodeId?.let(references::add)
            }
            is DeathSaveNode -> {
                successNodeId?.let(references::add)
                failureNodeId?.let(references::add)
                stableNodeId?.let(references::add)
                unconsciousNodeId?.let(references::add)
            }
            is ConsumeResourceNode -> failNodeId?.let(references::add)
            is TargetedAttackNode -> {
                missNodeId?.let(references::add)
                hitNodeId?.let(references::add)
            }
            is RollNode,
            is RecoveryNode,
            is MathNode,
            is EffectNode -> Unit
        }
        return references
    }
}
