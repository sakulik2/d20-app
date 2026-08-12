package xyz.sakulik.d20.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.sakulik.d20.app.domain.rules.dynamic.EncounterProfile

class TrustedGameEventAuthorizerTest {
    private val context = GameEventAuthorizationContext(
        defaultActionId = "dnd_check",
        allowedActionIds = setOf("dnd_check", "dnd_attack", "dnd_cast"),
        allowedDiceExpressions = mapOf(
            "dnd_check" to setOf("1d20", "2d20kh1", "2d20kl1"),
            "dnd_attack" to setOf("1d20"),
            "dnd_cast" to setOf("1d20")
        ),
        characterStatIds = setOf("str", "dex"),
        encounterProfiles = mapOf(
            "goblin" to EncounterProfile(
                initiative = 10,
                ac = 12,
                hp = 7,
                maxHp = 7,
                savingThrows = mapOf("dex" to 2)
            )
        ),
        combatTargetIds = setOf("goblin_1"),
        weaponIds = setOf("longsword"),
        spellIds = setOf("magic_missile"),
        availableSpellSlotLevels = setOf(1)
    )

    @Test
    fun normalizesAllowedExpressionAndBindsDefaultAction() {
        val result = TrustedGameEventAuthorizer.authorize(
            listOf(
                GameEvent.RequireRoll(
                    expression = " 2D20 KH1 ",
                    reason = "优势检定",
                    statId = "STR"
                )
            ),
            context
        ) as GameEventAuthorizationResult.Authorized

        val request = (result.events.single() as TrustedGameEvent.RequireRoll).request
        assertEquals("2d20kh1", request.expression)
        assertEquals("dnd_check", request.actionId)
        assertEquals("str", request.statId)
    }

    @Test
    fun rejectsExpressionOutsideActionPolicy() {
        val oversizedResult = TrustedGameEventAuthorizer.authorize(
            listOf(
                GameEvent.RequireRoll(
                    expression = "1000d1000",
                    reason = "伪造骰式",
                    actionId = "dnd_check"
                )
            ),
            context
        )
        val wrongActionResult = TrustedGameEventAuthorizer.authorize(
            listOf(
                GameEvent.RequireRoll(
                    expression = "1d100",
                    reason = "错误攻击骰",
                    actionId = "dnd_attack"
                )
            ),
            context
        )

        assertTrue(oversizedResult is GameEventAuthorizationResult.Rejected)
        assertTrue(wrongActionResult is GameEventAuthorizationResult.Rejected)
    }

    @Test
    fun resolvesEncounterFromTrustedRulesetProfile() {
        val result = TrustedGameEventAuthorizer.authorize(
            listOf(
                GameEvent.StartCombat(
                    listOf(EncounterParticipantRequest("goblin_1", "洞穴守卫", "goblin"))
                )
            ),
            context
        ) as GameEventAuthorizationResult.Authorized

        val combatant = (result.events.single() as TrustedGameEvent.StartCombat)
            .combatants.single()
        assertEquals("洞穴守卫", combatant.name)
        assertEquals(12, combatant.ac)
        assertEquals(7, combatant.hp)
        assertEquals(mapOf("dex" to 2), combatant.savingThrows)
    }

    @Test
    fun rejectsUnknownEncounterProfile() {
        val result = TrustedGameEventAuthorizer.authorize(
            listOf(
                GameEvent.StartCombat(
                    listOf(EncounterParticipantRequest("dragon", "巨龙", "dragon"))
                )
            ),
            context
        )

        assertTrue(result is GameEventAuthorizationResult.Rejected)
        assertTrue((result as GameEventAuthorizationResult.Rejected).message.contains("未由当前规则包授权"))
    }

    @Test
    fun rejectsStartingAnotherCombat() {
        val result = TrustedGameEventAuthorizer.authorize(
            listOf(
                GameEvent.StartCombat(
                    listOf(EncounterParticipantRequest("goblin_2", "增援", "goblin"))
                )
            ),
            context.copy(canStartCombat = false)
        )

        assertTrue(result is GameEventAuthorizationResult.Rejected)
    }

    @Test
    fun rejectsUnknownRuleActionAndCharacterStat() {
        val actionResult = TrustedGameEventAuthorizer.authorize(
            listOf(GameEvent.RequireRoll("1d20", reason = "未知动作", actionId = "admin")),
            context
        )
        val statResult = TrustedGameEventAuthorizer.authorize(
            listOf(GameEvent.RequireRoll("1d20", reason = "检定", statId = "hp_override")),
            context
        )

        assertTrue(actionResult is GameEventAuthorizationResult.Rejected)
        assertTrue(statResult is GameEventAuthorizationResult.Rejected)
    }

    @Test
    fun rejectsUnknownCombatTarget() {
        val result = TrustedGameEventAuthorizer.authorize(
            listOf(
                GameEvent.RequireRoll(
                    expression = "1d20",
                    reason = "攻击",
                    actionId = "dnd_attack",
                    targetId = "missing"
                )
            ),
            context
        )

        assertTrue(result is GameEventAuthorizationResult.Rejected)
    }

    @Test
    fun rejectsUnknownWeaponSpellAndSlotReferences() {
        val weaponResult = TrustedGameEventAuthorizer.authorize(
            listOf(
                GameEvent.RequireRoll(
                    expression = "1d20",
                    reason = "攻击",
                    actionId = "dnd_attack",
                    targetId = "goblin_1",
                    weaponId = "missing"
                )
            ),
            context
        )
        val spellResult = TrustedGameEventAuthorizer.authorize(
            listOf(
                GameEvent.RequireRoll(
                    expression = "1d20",
                    reason = "施法",
                    actionId = "dnd_cast",
                    targetId = "goblin_1",
                    spellId = "missing"
                )
            ),
            context
        )
        val slotResult = TrustedGameEventAuthorizer.authorize(
            listOf(
                GameEvent.RequireRoll(
                    expression = "1d20",
                    reason = "施法",
                    actionId = "dnd_cast",
                    targetId = "goblin_1",
                    spellId = "magic_missile",
                    slotLevel = 9
                )
            ),
            context
        )

        assertTrue(weaponResult is GameEventAuthorizationResult.Rejected)
        assertTrue(spellResult is GameEventAuthorizationResult.Rejected)
        assertTrue(slotResult is GameEventAuthorizationResult.Rejected)
    }
}
