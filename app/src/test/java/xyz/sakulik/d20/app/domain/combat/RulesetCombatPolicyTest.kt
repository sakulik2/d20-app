package xyz.sakulik.d20.app.domain.combat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.sakulik.d20.app.domain.rules.dynamic.CombatRules
import xyz.sakulik.d20.app.domain.rules.dynamic.InitiativeRules

class RulesetCombatPolicyTest {

    @Test
    fun dndStyleInitiativeUsesDeclaredAbilityModifier() {
        val policy = policy(
            CombatRules(
                initiative = InitiativeRules(
                    diceExpression = "1d20",
                    statKey = "dex",
                    statTransform = "ABILITY_MODIFIER",
                    label = "先攻"
                )
            )
        )

        val request = policy.initiativeRequest(mapOf("dex" to "16"))!!

        assertEquals("1d20+3", request.expression)
        assertEquals(3, request.modifier)
    }

    @Test
    fun rawInitiativeDoesNotRequireDicePanel() {
        val policy = policy(
            CombatRules(
                initiative = InitiativeRules(
                    statKey = "dex",
                    statTransform = "RAW_VALUE",
                    defaultValue = 0
                )
            )
        )

        assertNull(policy.initiativeRequest(mapOf("dex" to "70")))
        assertEquals(70, policy.automaticInitiative(mapOf("dex" to "70")))
    }

    @Test
    fun genericPolicyAddsNoDndCombatAssumptions() {
        val policy = RulesetCombatPolicy.generic("custom")
        val zeroHpTarget = Combatant(
            id = "target",
            name = "目标",
            initiative = 1,
            ac = 0,
            hp = 0,
            maxHp = 1
        )

        assertNull(policy.initiativeRequest(emptyMap()))
        assertTrue(policy.initialTurnResources(isPlayerTurn = true).isEmpty())
        assertFalse(zeroHpTarget.isDefeated(policy.rules.defeatAtZeroHp))
        assertEquals(RulesetCombatPolicy.LIFE_POLICY_NONE, policy.lifePolicyId)
        assertEquals(RulesetCombatPolicy.LOCAL_ACTION_HANDLER_NONE, policy.localActionHandlerId)
    }

    @Test
    fun unsupportedExecutablePoliciesDowngradeToNone() {
        val policy = policy(
            CombatRules(
                lifePolicy = "downloaded.ClassName",
                localActionHandler = "javascript:handler"
            )
        )

        assertEquals(RulesetCombatPolicy.LIFE_POLICY_NONE, policy.lifePolicyId)
        assertEquals(RulesetCombatPolicy.LOCAL_ACTION_HANDLER_NONE, policy.localActionHandlerId)
    }

    @Test
    fun actionWithoutDeclaredCostRemainsAvailableOutsidePlayerTurn() {
        val policy = policy(
            CombatRules(
                turnResources = mapOf("action_point" to 1),
                actionCosts = mapOf("strike" to mapOf("action_point" to 1)),
                actionTimings = mapOf("strike" to "PARTICIPANT_TURN")
            )
        )
        val enemyTurn = CombatState(
            isActive = true,
            combatants = listOf(
                Combatant("player", "玩家", 10, 0, 1, 1, isPlayer = true),
                Combatant("enemy", "敌人", 20, 0, 1, 1)
            ),
            initiativeQueue = listOf("enemy", "player"),
            currentTurnIndex = 0
        )

        assertTrue(policy.canPerform("observe", enemyTurn))
        assertEquals(enemyTurn, policy.consume("observe", enemyTurn))
        assertFalse(policy.canPerform("strike", enemyTurn))
    }

    @Test
    fun declaredAnyTurnActionCanConsumeResourcesOutsideParticipantTurn() {
        val policy = policy(
            CombatRules(
                turnResources = mapOf("reaction" to 1),
                actionCosts = mapOf("react" to mapOf("reaction" to 1)),
                actionTimings = mapOf("react" to "ANY")
            )
        )
        val enemyTurn = CombatState(
            isActive = true,
            combatants = listOf(
                Combatant("player", "玩家", 10, 0, 1, 1, isPlayer = true),
                Combatant("enemy", "敌人", 20, 0, 1, 1)
            ),
            initiativeQueue = listOf("enemy", "player"),
            currentTurnIndex = 0,
            turnResources = mapOf("reaction" to 1)
        )

        val consumed = policy.consume("react", enemyTurn)

        assertEquals(0, consumed?.turnResources?.get("reaction"))
    }

    private fun policy(rules: CombatRules): RulesetCombatPolicy {
        return RulesetCombatPolicy.from("test_ruleset", rules)
    }
}
