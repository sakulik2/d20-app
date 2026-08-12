package xyz.sakulik.d20.app.domain.rules.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.sakulik.d20.app.domain.combat.Combatant

class BatchOutcomePlannerTest {
    private val planner = BatchOutcomePlanner()

    @Test
    fun plansEveryTargetWithoutMutatingInput() {
        val targets = listOf(target("first", 10), target("second", 7))
        val batch = BatchRuleOutcome(
            outcomes = listOf(
                outcome("first", 4),
                outcome("second", 20)
            ),
            resourceChanges = mapOf("spell_slots.level_3" to -1)
        )

        val result = planner.plan(batch, targets) as BatchPlanningResult.Completed

        assertEquals(mapOf("first" to 6, "second" to 0), result.targetHpUpdates)
        assertEquals(10, targets.first().hp)
        assertEquals(mapOf("spell_slots.level_3" to -1), batch.resourceChanges)
    }

    @Test
    fun missingTargetRejectsWholePlan() {
        val result = planner.plan(
            BatchRuleOutcome(listOf(outcome("missing", 4))),
            listOf(target("other", 10))
        )

        assertTrue(result is BatchPlanningResult.Invalid)
        assertEquals(
            "MISSING_BATCH_TARGET",
            (result as BatchPlanningResult.Invalid).error.code
        )
    }

    private fun target(id: String, hp: Int) = Combatant(
        id = id,
        name = id,
        initiative = 10,
        ac = 12,
        hp = hp,
        maxHp = hp
    )

    private fun outcome(targetId: String, damage: Int) = RuleOutcome(
        targetId = targetId,
        damage = listOf(
            DamageComponent("1d6", "force", damage, damage)
        )
    )
}
