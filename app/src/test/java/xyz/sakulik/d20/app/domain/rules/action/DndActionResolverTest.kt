package xyz.sakulik.d20.app.domain.rules.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.sakulik.d20.app.domain.combat.Combatant
import xyz.sakulik.d20.app.domain.rules.dynamic.DiceSubmission

class DndActionResolverTest {
    private val resolver = DndActionResolver()
    private val character = mapOf(
        "str" to "16",
        "dex" to "18",
        "int" to "16",
        "level" to "5",
        "prof_bonus" to "3"
    )
    private val target = Combatant(
        id = "target",
        name = "目标",
        initiative = 10,
        ac = 18,
        hp = 40,
        maxHp = 40,
        resistances = listOf("fire"),
        vulnerabilities = listOf("cold"),
        immunities = listOf("poison"),
        savingThrows = mapOf("dex" to 2)
    )

    @Test
    fun weaponAttackUsesProficiencyAndFinesseAbility() {
        val weapon = weapon(attackAbility = AttackAbility.FINESSE)
        val resolution = resolver.resolveWeaponAttack(
            character, weapon, target, DiceSubmission.manual("1d20", 11)
        )

        assertTrue(resolution is ActionResolution.NeedsEffectRoll)
        assertEquals("1d8+4", (resolution as ActionResolution.NeedsEffectRoll).expression)
    }

    @Test
    fun naturalOneMissesAndNaturalTwentyDoublesAllDamageDice() {
        val weapon = weapon(damageFormula = "2d6+1d4")

        val miss = resolver.resolveWeaponAttack(
            character, weapon, target.copy(ac = 1), DiceSubmission.manual("1d20", 1)
        ) as ActionResolution.Completed
        val critical = resolver.resolveWeaponAttack(
            character, weapon, target.copy(ac = 99), DiceSubmission.manual("1d20", 20)
        ) as ActionResolution.NeedsEffectRoll

        assertEquals(false, miss.outcome.hit)
        assertEquals("4d6+2d4+3", critical.expression)
        assertTrue(critical.critical)
    }

    @Test
    fun damageAppliesResistanceImmunityAndVulnerability() {
        val fire = resolveDamage("fire", 9)
        val poison = resolveDamage("poison", 9)
        val cold = resolveDamage("cold", 9)

        assertEquals(4, fire.finalAmount)
        assertEquals(0, poison.finalAmount)
        assertEquals(18, cold.finalAmount)
    }

    @Test
    fun spellAttackMissStillConsumesSlotAndCantripDoesNot() {
        val spell = attackSpell(slotLevel = 2)
        val miss = resolver.resolveSpell(
            character, spell, target, DiceSubmission.manual("1d20", 1)
        ) as ActionResolution.Completed
        val cantripMiss = resolver.resolveSpell(
            character, spell.copy(slotLevel = 0), target, DiceSubmission.manual("1d20", 1)
        ) as ActionResolution.Completed
        val ritualMiss = resolver.resolveSpell(
            character,
            spell.copy(isRitual = true),
            target,
            DiceSubmission.manual("1d20", 1)
        ) as ActionResolution.Completed

        assertEquals(mapOf("spell_slots.level_2" to -1), miss.outcome.resourceChanges)
        assertEquals(emptyMap<String, Int>(), cantripMiss.outcome.resourceChanges)
        assertEquals(emptyMap<String, Int>(), ritualMiss.outcome.resourceChanges)
    }

    @Test
    fun savingThrowRecordsSuccessAndHalfDamage() {
        val spell = attackSpell(
            resolutionType = SpellResolutionType.SAVING_THROW,
            saveAbilityId = "dex",
            saveDc = 15,
            halfDamageOnSave = true
        )
        val request = resolver.resolveSpell(
            character, spell, target, DiceSubmission.manual("1d20", 13)
        ) as ActionResolution.NeedsEffectRoll
        val completed = resolver.resolveSpellEffect(
            spell = spell,
            target = target,
            submission = DiceSubmission.manual("2d6", 9),
            expectedExpression = "2d6",
            critical = false,
            targetSaved = request.targetSaved
        ) as ActionResolution.Completed

        assertEquals(true, request.targetSaved)
        assertEquals(2, completed.outcome.damage.single().finalAmount)
    }

    @Test
    fun automaticAndHealingSpellsGoStraightToEffectRoll() {
        val automatic = attackSpell(resolutionType = SpellResolutionType.AUTOMATIC)
        val healing = attackSpell(
            resolutionType = SpellResolutionType.HEALING,
            damageFormula = null,
            damageType = null,
            healingFormula = "1d8+3"
        )

        assertEquals(
            "2d6",
            (resolver.resolveSpell(character, automatic, target, DiceSubmission.manual("2d6", 7))
                as ActionResolution.NeedsEffectRoll).expression
        )
        assertEquals(
            "1d8+3",
            (resolver.resolveSpell(character, healing, null, DiceSubmission.manual("1d8+3", 7))
                as ActionResolution.NeedsEffectRoll).expression
        )
    }

    private fun resolveDamage(type: String, amount: Int): DamageComponent {
        val resolution = resolver.resolveWeaponDamage(
            weapon = weapon(damageType = type),
            target = target,
            submission = DiceSubmission.manual("1d8+3", amount),
            expectedExpression = "1d8+3",
            critical = false
        ) as ActionResolution.Completed
        return resolution.outcome.damage.single()
    }

    private fun weapon(
        attackAbility: AttackAbility = AttackAbility.STR,
        damageFormula: String = "1d8",
        damageType: String = "slashing"
    ) = WeaponProfile(
        itemId = "weapon",
        name = "测试武器",
        attackAbility = attackAbility,
        proficient = true,
        attackBonus = 0,
        damageFormula = damageFormula,
        damageAbility = attackAbility,
        damageBonus = 0,
        damageType = damageType
    )

    private fun attackSpell(
        slotLevel: Int = 1,
        resolutionType: SpellResolutionType = SpellResolutionType.ATTACK,
        saveAbilityId: String? = null,
        saveDc: Int? = null,
        damageFormula: String? = "2d6",
        damageType: String? = "fire",
        halfDamageOnSave: Boolean = false,
        healingFormula: String? = null
    ) = SpellProfile(
        spellId = "spell",
        name = "测试法术",
        resolutionType = resolutionType,
        slotLevel = slotLevel,
        isRitual = false,
        abilityId = "int",
        attackBonus = 0,
        targetId = null,
        saveAbilityId = saveAbilityId,
        saveDc = saveDc,
        damageFormula = damageFormula,
        damageType = damageType,
        halfDamageOnSave = halfDamageOnSave,
        healingFormula = healingFormula
    )
}
