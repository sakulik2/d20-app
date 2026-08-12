package xyz.sakulik.d20.app.domain.combat

import xyz.sakulik.d20.app.domain.rules.dynamic.CombatRules
import xyz.sakulik.d20.app.domain.rules.dynamic.IRuleset

data class InitiativeRequest(
    val expression: String,
    val reason: String,
    val modifier: Int
)

class RulesetCombatPolicy private constructor(
    val rulesetId: String,
    val rules: CombatRules
) {
    val lifePolicyId: String = rules.lifePolicy.normalizedId(
        supported = setOf(LIFE_POLICY_NONE, LIFE_POLICY_DND_5E),
        fallback = LIFE_POLICY_NONE
    )
    val localActionHandlerId: String = rules.localActionHandler.normalizedId(
        supported = setOf(LOCAL_ACTION_HANDLER_NONE, LOCAL_ACTION_HANDLER_DND_5E),
        fallback = LOCAL_ACTION_HANDLER_NONE
    )
    val primaryActionResource: String? = rules.primaryActionResource
        ?.takeIf { it in rules.turnResources && rules.turnResources.getValue(it) > 0 }

    fun initiativeRequest(character: Map<String, String>): InitiativeRequest? {
        val initiative = rules.initiative ?: return null
        val diceExpression = initiative.diceExpression?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val modifier = initiativeModifier(character)
        return InitiativeRequest(
            expression = diceExpression.withModifier(modifier),
            reason = initiative.label.ifBlank { "先攻" },
            modifier = modifier
        )
    }

    fun automaticInitiative(character: Map<String, String>): Int {
        return initiativeModifier(character)
    }

    fun initialTurnResources(isPlayerTurn: Boolean): Map<String, Int> {
        if (!isPlayerTurn) return emptyMap()
        return rules.turnResources
            .filterValues { it > 0 }
            .mapValues { (_, value) -> value.coerceAtMost(MAX_RESOURCE_VALUE) }
    }

    fun actionCost(actionId: String): Map<String, Int> {
        return rules.actionCosts[actionId]
            .orEmpty()
            .filterValues { it > 0 }
    }

    fun canPerform(actionId: String, state: CombatState): Boolean {
        val costs = actionCost(actionId)
        if (!state.isActive) return true
        if (actionTiming(actionId) == ACTION_TIMING_PARTICIPANT_TURN && !state.isPlayerTurn) {
            return false
        }
        return costs.all { (resource, cost) ->
            state.turnResources.getOrDefault(resource, 0) >= cost
        }
    }

    fun consume(actionId: String, state: CombatState): CombatState? {
        if (!canPerform(actionId, state)) return null
        val costs = actionCost(actionId)
        if (costs.isEmpty()) return state
        return state.copy(
            turnResources = state.turnResources.mapValues { (resource, current) ->
                current - costs.getOrDefault(resource, 0)
            }
        )
    }

    fun resourceLabel(resource: String): String {
        return rules.turnResourceLabels[resource]?.takeIf { it.isNotBlank() }
            ?: resource.replace('_', ' ')
    }

    private fun actionTiming(actionId: String): String {
        return rules.actionTimings[actionId].orEmpty().normalizedId(
            supported = setOf(ACTION_TIMING_ANY, ACTION_TIMING_PARTICIPANT_TURN),
            fallback = ACTION_TIMING_ANY
        )
    }

    private fun initiativeModifier(character: Map<String, String>): Int {
        val initiative = rules.initiative ?: return 0
        val raw = initiative.statKey
            ?.let { character[it]?.toIntOrNull() }
            ?: initiative.defaultValue
        return when (initiative.statTransform.trim().uppercase()) {
            TRANSFORM_ABILITY_MODIFIER -> Math.floorDiv(raw - 10, 2)
            TRANSFORM_RAW_VALUE -> raw
            TRANSFORM_NONE -> initiative.defaultValue
            else -> initiative.defaultValue
        }
    }

    companion object {
        const val LIFE_POLICY_NONE = "NONE"
        const val LIFE_POLICY_DND_5E = "DND_5E"
        const val LOCAL_ACTION_HANDLER_NONE = "NONE"
        const val LOCAL_ACTION_HANDLER_DND_5E = "DND_5E"
        const val ACTION_TIMING_ANY = "ANY"
        const val ACTION_TIMING_PARTICIPANT_TURN = "PARTICIPANT_TURN"
        private const val TRANSFORM_ABILITY_MODIFIER = "ABILITY_MODIFIER"
        private const val TRANSFORM_RAW_VALUE = "RAW_VALUE"
        private const val TRANSFORM_NONE = "NONE"
        private const val MAX_RESOURCE_VALUE = 100

        fun from(ruleset: IRuleset): RulesetCombatPolicy {
            return RulesetCombatPolicy(ruleset.id, ruleset.combatRules)
        }

        fun from(rulesetId: String, rules: CombatRules): RulesetCombatPolicy {
            return RulesetCombatPolicy(rulesetId, rules)
        }

        fun generic(rulesetId: String): RulesetCombatPolicy {
            return RulesetCombatPolicy(rulesetId, CombatRules())
        }
    }
}

private fun String.normalizedId(supported: Set<String>, fallback: String): String {
    return trim().uppercase().takeIf(supported::contains) ?: fallback
}

private fun String.withModifier(modifier: Int): String {
    return when {
        modifier > 0 -> "$this+$modifier"
        modifier < 0 -> "$this$modifier"
        else -> this
    }
}
