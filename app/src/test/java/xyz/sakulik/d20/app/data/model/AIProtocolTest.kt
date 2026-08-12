package xyz.sakulik.d20.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.sakulik.d20.app.util.LlmJsonBuffer

class AIProtocolTest {

    @Test
    fun parseCanonicalRequireRollEvent() {
        val raw = """
            {
              "narrative": "门后传来异响。",
              "game_events": [
                {
                  "type": "require_roll",
                  "action_id": "dnd_check",
                  "expression": "1d20",
                  "threshold": 15,
                  "stat_id": "dex",
                  "reason": "敏捷（隐匿）"
                }
              ]
            }
        """.trimIndent()

        val response = LlmJsonBuffer.parseAndRepair(raw).getRightOrNull()
        val event = response?.gameEvents?.single() as? GameEvent.RequireRoll

        assertNotNull(response)
        assertNotNull(event)
        assertEquals("dnd_check", event?.actionId)
        assertEquals("dex", event?.statId)
        assertEquals(15, event?.threshold)
    }

    @Test
    fun rejectJsonThatViolatesEventContract() {
        val raw = """
            {
              "narrative": "需要检定。",
              "game_events": [
                {"actionId":"dnd_check","meta":{"dc":"15"}}
              ]
            }
        """.trimIndent()

        assertTrue(LlmJsonBuffer.parseAndRepair(raw).isLeft())
    }

    @Test
    fun rejectLegacyUpdateStatEvent() {
        val raw = """
            {
              "narrative": "你受到了伤害。",
              "game_events": [
                {"type":"update_stat","stat_id":"hp","delta":-4,"reason":"伤害"}
              ]
            }
        """.trimIndent()

        assertTrue(LlmJsonBuffer.parseAndRepair(raw).isLeft())
    }

    @Test
    fun rejectUnknownEventField() {
        val raw = """
            {
              "narrative": "需要检定。",
              "game_events": [{
                "type":"require_roll",
                "expression":"1d20",
                "reason":"调查",
                "invented_field":true
              }]
            }
        """.trimIndent()

        assertTrue(LlmJsonBuffer.parseAndRepair(raw).isLeft())
    }

    @Test
    fun parseAttackTargetAndSpellSlotFields() {
        val raw = """
            {
              "narrative": "战斗继续。",
              "game_events": [
                {
                  "type": "require_roll",
                  "action_id": "dnd_attack",
                  "expression": "1d20",
                  "stat_id": "str",
                  "target_id": "goblin_1",
                  "weapon_id": "longsword",
                  "reason": "攻击地精"
                },
                {
                  "type": "require_roll",
                  "action_id": "dnd_cast",
                  "expression": "1d20",
                  "stat_id": "int",
                  "slot_level": 2,
                  "spell_id": "scorching_ray",
                  "reason": "施放法术"
                }
              ]
            }
        """.trimIndent()

        val events = LlmJsonBuffer.parseAndRepair(raw).getRightOrNull()?.gameEvents.orEmpty()
        val attack = events[0] as GameEvent.RequireRoll
        val cast = events[1] as GameEvent.RequireRoll

        assertEquals("goblin_1", attack.targetId)
        assertEquals("longsword", attack.weaponId)
        assertEquals(2, cast.slotLevel)
        assertEquals("scorching_ray", cast.spellId)
    }

    @Test
    fun parseEncounterProfileReferences() {
        val raw = """
            {
              "narrative": "地精拔出了短刀。",
              "game_events": [
                {
                  "type": "start_combat",
                  "combatants": [{
                    "id": "goblin_1",
                    "name": "地精斥候",
                    "profile_id": "goblin"
                  }]
                }
              ]
            }
        """.trimIndent()

        val event = LlmJsonBuffer.parseAndRepair(raw).getRightOrNull()
            ?.gameEvents?.single() as GameEvent.StartCombat

        assertEquals(
            EncounterParticipantRequest(
                id = "goblin_1",
                name = "地精斥候",
                profileId = "goblin"
            ),
            event.combatants.single()
        )
    }

    @Test
    fun rejectModelSuppliedCombatantRules() {
        val raw = """
            {
              "narrative": "对手进入冲突。",
              "game_events": [{
                "type": "start_combat",
                "combatants": [{
                  "id": "opponent",
                  "name": "对手",
                  "profile_id": "goblin",
                  "hp": 999
                }]
              }]
            }
        """.trimIndent()

        assertTrue(LlmJsonBuffer.parseAndRepair(raw).isLeft())
    }
}
