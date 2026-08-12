package xyz.sakulik.d20.app.domain.rules.action

import xyz.sakulik.d20.app.domain.combat.Combatant
import xyz.sakulik.d20.app.domain.rules.dynamic.RuleError

sealed interface BatchPlanningResult {
    data class Completed(
        val targetHpUpdates: Map<String, Int>,
        val targetNames: Map<String, String>
    ) : BatchPlanningResult

    data class Invalid(val error: RuleError) : BatchPlanningResult
}

class BatchOutcomePlanner {

    fun plan(batch: BatchRuleOutcome, availableTargets: List<Combatant>): BatchPlanningResult {
        if (batch.outcomes.isEmpty()) {
            return invalid("EMPTY_BATCH_OUTCOME", "批量裁决没有产生任何目标结果")
        }
        val targetsById = availableTargets.associateBy(Combatant::id)
        val hpUpdates = linkedMapOf<String, Int>()
        val targetNames = linkedMapOf<String, String>()
        batch.outcomes.forEach { outcome ->
            val targetId = outcome.targetId
                ?: return invalid("MISSING_BATCH_TARGET", "批量裁决包含没有目标的结果")
            val target = targetsById[targetId]
                ?: return invalid(
                    "MISSING_BATCH_TARGET",
                    "批量裁决目标 $targetId 已不存在或已被击败"
                )
            val damage = outcome.damage.sumOf { component -> component.finalAmount }
                .coerceAtLeast(0)
            hpUpdates[targetId] = (target.hp - damage).coerceIn(0, target.maxHp)
            targetNames[targetId] = target.name
        }
        return BatchPlanningResult.Completed(hpUpdates, targetNames)
    }

    private fun invalid(code: String, message: String): BatchPlanningResult.Invalid {
        return BatchPlanningResult.Invalid(RuleError(code, message))
    }
}
