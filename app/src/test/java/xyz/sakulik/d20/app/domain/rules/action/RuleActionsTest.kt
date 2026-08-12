package xyz.sakulik.d20.app.domain.rules.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import xyz.sakulik.d20.app.data.local.ItemEntity
import xyz.sakulik.d20.app.domain.combat.EffectOperation
import xyz.sakulik.d20.app.domain.combat.EffectTickTiming

class RuleActionsTest {

    @Test
    fun parsesWeaponProfileFromItemRules() {
        val profile = item(
            category = "武器",
            modifiers = mapOf(
                "attack_ability" to "FINESSE",
                "proficient" to "true",
                "damage_formula" to "1d8",
                "damage_type" to "piercing"
            )
        ).toWeaponProfileOrNull()!!

        assertEquals(AttackAbility.FINESSE, profile.attackAbility)
        assertTrue(profile.proficient)
        assertEquals("1d8", profile.damageFormula)
        assertEquals(TargetingMode.SINGLE, profile.targeting)
    }

    @Test
    fun parsesRitualSpellAndRejectsIncompleteSpell() {
        val ritual = item(
            category = "法术",
            modifiers = mapOf(
                "resolution_type" to "AUTOMATIC",
                "slot_level" to "1",
                "ritual" to "true",
                "damage_formula" to "1d6",
                "damage_type" to "radiant"
            )
        ).toSpellProfileOrNull()!!

        assertTrue(ritual.isRitual)
        assertNull(
            item(
                category = "法术",
                modifiers = mapOf("resolution_type" to "HEALING", "slot_level" to "1")
            ).toSpellProfileOrNull()
        )
    }

    @Test
    fun parsesMultiTargetProfilesAndRejectsUnknownMode() {
        val weapon = item(
            category = "武器",
            modifiers = mapOf(
                "damage_formula" to "1d6",
                "damage_type" to "slashing",
                "targeting" to "MULTIPLE",
                "max_targets" to "2"
            )
        ).toWeaponProfileOrNull()!!
        val spell = item(
            category = "法术",
            modifiers = mapOf(
                "resolution_type" to "SAVING_THROW",
                "slot_level" to "3",
                "save_ability" to "dex",
                "save_dc" to "15",
                "damage_formula" to "8d6",
                "damage_type" to "fire",
                "targeting" to "ALL_ENEMIES"
            )
        ).toSpellProfileOrNull()!!

        assertEquals(TargetingMode.MULTIPLE, weapon.targeting)
        assertEquals(2, weapon.maxTargets)
        assertEquals(TargetingMode.ALL_ENEMIES, spell.targeting)
        assertNull(
            item(
                category = "法术",
                modifiers = mapOf(
                    "resolution_type" to "AUTOMATIC",
                    "damage_formula" to "1d6",
                    "damage_type" to "cold",
                    "targeting" to "AREA"
                )
            ).toSpellProfileOrNull()
        )
    }

    @Test
    fun batchOutcomeRejectsDuplicateTargets() {
        assertThrows(IllegalArgumentException::class.java) {
            BatchRuleOutcome(
                outcomes = listOf(
                    RuleOutcome(targetId = "same"),
                    RuleOutcome(targetId = "same")
                )
            )
        }
    }

    @Test
    fun parsesTrustedOngoingEffectAndRejectsPartialDefinition() {
        val spell = item(
            category = "法术",
            modifiers = mapOf(
                "resolution_type" to "AUTOMATIC",
                "damage_formula" to "1d4",
                "damage_type" to "fire",
                "effect_name" to "燃烧",
                "effect_timing" to "TURN_START",
                "effect_operation" to "DAMAGE",
                "effect_amount" to "2",
                "effect_duration" to "3"
            )
        ).toSpellProfileOrNull()!!

        assertEquals(EffectTickTiming.TURN_START, spell.ongoingEffect?.timing)
        assertEquals(EffectOperation.DAMAGE, spell.ongoingEffect?.operation)
        assertEquals(3, spell.ongoingEffect?.durationTicks)
        assertNull(
            item(
                category = "法术",
                modifiers = mapOf(
                    "resolution_type" to "AUTOMATIC",
                    "damage_formula" to "1d4",
                    "damage_type" to "fire",
                    "effect_timing" to "TURN_START"
                )
            ).toSpellProfileOrNull()
        )
    }

    private fun item(category: String, modifiers: Map<String, String>) = ItemEntity(
        id = "item",
        campaignId = "campaign",
        name = "测试条目",
        description = "",
        category = category,
        modifiers = modifiers,
        isEquipped = true
    )
}
