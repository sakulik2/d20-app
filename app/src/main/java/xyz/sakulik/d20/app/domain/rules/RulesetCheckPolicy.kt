package xyz.sakulik.d20.app.domain.rules

import xyz.sakulik.d20.app.domain.rules.dynamic.CheckRules
import xyz.sakulik.d20.app.domain.rules.dynamic.IRuleset

data class CheckParameters(
    val actionId: String,
    val targetValue: Int?,
    val modifier: Int,
    val targetLabel: String
)

class RulesetCheckPolicy private constructor(private val rules: CheckRules) {

    fun resolveStatId(
        requestedStatId: String?,
        reason: String,
        character: Map<String, String>
    ): String? {
        return requestedStatId
            ?.takeIf { it.isNotBlank() }
            ?.let { requested ->
                character.keys.firstOrNull { key -> key.equals(requested, ignoreCase = true) }
            }
            ?: inferStatId(reason, character)
    }

    fun inferStatId(reason: String, character: Map<String, String>): String? {
        return character.keys.firstOrNull { key ->
            reason.contains(key, ignoreCase = true) ||
                rules.statAliases.entries
                    .firstOrNull { (configuredKey, _) ->
                        configuredKey.equals(key, ignoreCase = true)
                    }
                    ?.value
                    .orEmpty()
                    .any { alias ->
                        alias.isNotBlank() && reason.contains(alias, ignoreCase = true)
                    }
        }
    }

    fun resolve(
        requestedActionId: String?,
        statValue: Int?,
        eventThreshold: Int?,
        eventTargetValue: Int?,
        eventModifier: Int?
    ): CheckParameters {
        val actionId = requestedActionId?.takeIf { it.isNotBlank() }
            ?: rules.defaultActionId
        val targetValue = when (rules.targetSource.normalized()) {
            TARGET_STAT_VALUE -> statValue
            else -> eventTargetValue ?: eventThreshold
        }
        val modifier = when (rules.modifierSource.normalized()) {
            MODIFIER_ABILITY -> statValue?.let { Math.floorDiv(it - 10, 2) } ?: 0
            MODIFIER_NONE -> 0
            else -> eventModifier ?: 0
        }
        return CheckParameters(
            actionId = actionId,
            targetValue = targetValue,
            modifier = modifier,
            targetLabel = rules.targetLabel.ifBlank { "目标值" }
        )
    }

    fun validationError(parameters: CheckParameters): String? {
        if (
            parameters.actionId in rules.requiredTargetActionIds &&
            (parameters.targetValue == null || parameters.targetValue <= 0)
        ) {
            return "检定缺少有效${parameters.targetLabel}"
        }
        return null
    }

    fun applyEquipmentBonus(
        targetValue: Int?,
        modifier: Int,
        bonus: Int
    ): Pair<Int?, Int> {
        return when (rules.equipmentBonusAppliesTo.normalized()) {
            EQUIPMENT_TARGET -> targetValue?.plus(bonus) to modifier
            EQUIPMENT_NONE -> targetValue to modifier
            else -> targetValue to modifier + bonus
        }
    }

    fun appliesEquipmentBonus(actionId: String): Boolean {
        return rules.equipmentBonusActionIds.isEmpty() ||
            rules.equipmentBonusActionIds.any { configuredId ->
                configuredId.equals(actionId, ignoreCase = true)
            }
    }

    companion object {
        private const val TARGET_STAT_VALUE = "STAT_VALUE"
        private const val MODIFIER_ABILITY = "ABILITY_MODIFIER"
        private const val MODIFIER_NONE = "NONE"
        private const val EQUIPMENT_TARGET = "TARGET"
        private const val EQUIPMENT_NONE = "NONE"

        fun from(ruleset: IRuleset): RulesetCheckPolicy {
            return RulesetCheckPolicy(ruleset.checkRules)
        }

        fun from(rules: CheckRules): RulesetCheckPolicy {
            return RulesetCheckPolicy(rules)
        }
    }
}

private fun String.normalized(): String = trim().uppercase()
