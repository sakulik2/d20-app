package xyz.sakulik.d20.app.domain.combat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.sakulik.d20.app.domain.rules.dynamic.CombatRules

class CombatStateManagerTest {

    @Test
    fun startCombatKeepsTargetRulesDataAndInitiativeOrder() {
        val manager = CombatStateManager()

        val state = manager.startCombat(
            enemies = listOf(
                CombatantDefinition(
                    id = "goblin_1",
                    name = "地精",
                    initiative = 14,
                    ac = 15,
                    hp = 7,
                    resistances = listOf(" Poison "),
                    vulnerabilities = listOf("Fire"),
                    immunities = listOf("Psychic")
                )
            ),
            playerName = "英雄",
            playerInitiative = 12,
            playerAc = 16,
            playerHp = 10
        )

        val target = state.availableTargets.single()
        assertEquals("goblin_1", state.initiativeQueue.first())
        assertEquals(15, target.ac)
        assertEquals(listOf("poison"), target.resistances)
        assertEquals(listOf("fire"), target.vulnerabilities)
        assertEquals(listOf("psychic"), target.immunities)
    }

    @Test
    fun defeatedTargetIsNoLongerAvailable() {
        val manager = CombatStateManager()
        manager.startCombat(
            enemies = listOf(
                CombatantDefinition("goblin_1", "地精", 10, 12, 5)
            ),
            playerName = "英雄",
            playerInitiative = 15,
            playerAc = 16,
            playerHp = 10,
            defeatAtZeroHp = true
        )

        val state = manager.applyDamage("goblin_1", 5)

        assertTrue(state.availableTargets.isEmpty())
        assertEquals(0, state.combatants.first { it.id == "goblin_1" }.hp)
        assertNull(manager.getTarget("goblin_1"))
    }

    @Test
    fun restoredCombatKeepsRoundTurnAndResourceLedger() {
        val manager = CombatStateManager()

        val state = manager.restoreCombat(
            enemies = listOf(CombatantDefinition("goblin", "地精", 15, 12, 7)),
            playerName = "英雄",
            playerInitiative = 20,
            playerAc = 16,
            playerHp = 10,
            round = 3,
            initiativeQueue = listOf(CombatStateManager.PLAYER_ID, "goblin"),
            currentTurnIndex = 0,
            turnResources = mapOf("action" to 0, "bonus_action" to 1, "reaction" to 0),
            defeatAtZeroHp = true
        )

        assertEquals(3, state.round)
        assertEquals("英雄", state.currentCombatant?.name)
        assertEquals(0, state.turnResources["action"])
        assertEquals(1, state.turnResources["bonus_action"])
        assertEquals(0, state.turnResources["reaction"])
    }

    @Test
    fun policyConsumesDeclaredResourcesOnlyOnPlayerTurn() {
        val manager = CombatStateManager()
        val policy = RulesetCombatPolicy.from(
            rulesetId = "three_action_system",
            rules = CombatRules(
                turnResources = mapOf("action_point" to 3),
                actionCosts = mapOf("strike" to mapOf("action_point" to 1))
            )
        )
        val initial = manager.startCombat(
            enemies = listOf(CombatantDefinition("goblin", "地精", 10, 12, 7)),
            playerName = "英雄",
            playerInitiative = 20,
            playerAc = 16,
            playerHp = 10,
            playerTurnResources = policy.initialTurnResources(isPlayerTurn = true)
        )

        val afterFirst = policy.consume("strike", initial)!!
        val afterSecond = policy.consume("strike", afterFirst)!!
        val afterThird = policy.consume("strike", afterSecond)!!

        assertEquals(2, afterFirst.turnResources["action_point"])
        assertEquals(1, afterSecond.turnResources["action_point"])
        assertEquals(0, afterThird.turnResources["action_point"])
        assertNull(policy.consume("strike", afterThird))
    }

    @Test
    fun nextTurnEndsCombatWhenEveryEnemyIsDefeated() {
        val manager = CombatStateManager()
        manager.startCombat(
            enemies = listOf(CombatantDefinition("goblin", "地精", 10, 12, 1)),
            playerName = "英雄",
            playerInitiative = 20,
            playerAc = 16,
            playerHp = 10,
            defeatAtZeroHp = true
        )
        manager.applyDamage("goblin", 1)

        assertTrue(!manager.nextTurn().isActive)
    }

    @Test
    fun zeroHpDoesNotImplyDefeatWithoutRulesetDeclaration() {
        val manager = CombatStateManager()
        manager.startCombat(
            enemies = listOf(CombatantDefinition("opponent", "对手", 10, hp = 1)),
            playerName = "调查员",
            playerInitiative = 20,
            playerAc = 0,
            playerHp = 10,
            defeatAtZeroHp = false
        )

        val state = manager.applyDamage("opponent", 1)

        assertEquals(0, state.availableTargets.single().hp)
        assertTrue(manager.nextTurn().isActive)
        assertFalse(manager.currentState().isPlayerTurn)
    }

    @Test
    fun resourcesPersistAcrossOpponentTurnsAndRefreshOnParticipantTurn() {
        val manager = CombatStateManager()
        manager.startCombat(
            enemies = listOf(CombatantDefinition("opponent", "对手", 10, hp = 5)),
            playerName = "玩家",
            playerInitiative = 20,
            playerAc = 0,
            playerHp = 10,
            playerTurnResources = mapOf("action" to 0, "reaction" to 1)
        )

        val opponentTurn = manager.nextTurn(mapOf("action" to 1, "reaction" to 1))
        val participantTurn = manager.nextTurn(mapOf("action" to 1, "reaction" to 1))

        assertFalse(opponentTurn.isPlayerTurn)
        assertEquals(mapOf("action" to 0, "reaction" to 1), opponentTurn.turnResources)
        assertTrue(participantTurn.isPlayerTurn)
        assertEquals(mapOf("action" to 1, "reaction" to 1), participantTurn.turnResources)
    }

    @Test
    fun resourcesExistWhenOpponentActsFirst() {
        val manager = CombatStateManager()

        val state = manager.startCombat(
            enemies = listOf(CombatantDefinition("opponent", "对手", 20, hp = 5)),
            playerName = "玩家",
            playerInitiative = 10,
            playerAc = 0,
            playerHp = 10,
            playerTurnResources = mapOf("reaction" to 1)
        )

        assertFalse(state.isPlayerTurn)
        assertEquals(1, state.turnResources["reaction"])
    }

    @Test
    fun ongoingEffectTicksAtDeclaredTurnAndExpires() {
        val manager = CombatStateManager()
        manager.startCombat(
            enemies = listOf(CombatantDefinition("opponent", "对手", 10, hp = 8)),
            playerName = "玩家",
            playerInitiative = 20,
            playerAc = 0,
            playerHp = 10,
            defeatAtZeroHp = true
        )
        manager.addOngoingEffect(
            OngoingEffect(
                id = "burn",
                name = "燃烧",
                targetId = "opponent",
                sourceId = "spell",
                timing = EffectTickTiming.TURN_START,
                operation = EffectOperation.DAMAGE,
                amount = 3,
                remainingTicks = 2
            )
        )

        val first = manager.advanceTurn()
        assertEquals(5, first.state.combatants.first { it.id == "opponent" }.hp)
        assertEquals(1, first.state.ongoingEffects.single().remainingTicks)
        assertEquals(3, first.ticks.single().appliedAmount)

        manager.advanceTurn()
        val second = manager.advanceTurn()
        assertEquals(2, second.state.combatants.first { it.id == "opponent" }.hp)
        assertTrue(second.state.ongoingEffects.isEmpty())
    }

    @Test
    fun refreshEffectKeepsSingleInstanceAndLongerDuration() {
        val manager = CombatStateManager()
        manager.startCombat(
            enemies = listOf(CombatantDefinition("opponent", "对手", 10, hp = 8)),
            playerName = "玩家",
            playerInitiative = 20,
            playerAc = 0,
            playerHp = 10
        )
        val original = OngoingEffect(
            id = "first",
            name = "中毒",
            targetId = "opponent",
            sourceId = "poison",
            timing = EffectTickTiming.TURN_END,
            operation = EffectOperation.DAMAGE,
            amount = 1,
            remainingTicks = 2
        )
        manager.addOngoingEffect(original)
        manager.addOngoingEffect(original.copy(id = "second", remainingTicks = 4))

        assertEquals(1, manager.currentState().ongoingEffects.size)
        assertEquals(4, manager.currentState().ongoingEffects.single().remainingTicks)
    }
}
