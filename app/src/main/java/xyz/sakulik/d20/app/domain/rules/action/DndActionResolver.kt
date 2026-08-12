package xyz.sakulik.d20.app.domain.rules.action

import xyz.sakulik.d20.app.domain.combat.Combatant
import xyz.sakulik.d20.app.domain.combat.CombatStateManager
import xyz.sakulik.d20.app.domain.rules.dynamic.DiceSubmission
import xyz.sakulik.d20.app.domain.rules.dynamic.RuleError

sealed interface ActionResolution {
    data class Completed(val outcome: RuleOutcome) : ActionResolution
    data class NeedsEffectRoll(
        val expression: String,
        val critical: Boolean = false,
        val targetSaved: Boolean? = null,
        val resourceChanges: Map<String, Int> = emptyMap()
    ) : ActionResolution
    data class Invalid(val error: RuleError) : ActionResolution
}

class DndActionResolver {

    fun resolveWeaponAttack(
        character: Map<String, String>,
        weapon: WeaponProfile,
        target: Combatant,
        submission: DiceSubmission,
        expectedExpression: String = "1d20"
    ): ActionResolution {
        submission.validateAgainst(expectedExpression)?.let { return ActionResolution.Invalid(it) }
        val naturalRoll = submission.keptTerms.singleOrNull()?.value ?: submission.total
        val abilityModifier = abilityModifier(character, weapon.attackAbility)
            ?: return missingAbility(weapon.attackAbility)
        val proficiency = if (weapon.proficient) proficiencyBonus(character) else 0
        val attackTotal = naturalRoll + abilityModifier + proficiency + weapon.attackBonus
        val critical = naturalRoll == 20
        val hit = naturalRoll != 1 && (critical || attackTotal >= target.ac)
        if (!hit) {
            return ActionResolution.Completed(
                RuleOutcome(targetId = target.id, hit = false)
            )
        }
        val damageModifier = weapon.damageAbility
            ?.let { abilityModifier(character, it) }
            ?: 0
        val formula = appendModifier(
            weapon.damageFormula,
            damageModifier + weapon.damageBonus
        )
        return ActionResolution.NeedsEffectRoll(
            expression = if (critical) DamageRoller.criticalFormula(formula) else formula,
            critical = critical
        )
    }

    fun resolveWeaponDamage(
        weapon: WeaponProfile,
        target: Combatant,
        submission: DiceSubmission,
        expectedExpression: String,
        critical: Boolean
    ): ActionResolution {
        submission.validateAgainst(expectedExpression)?.let {
            return ActionResolution.Invalid(it)
        }
        val component = applyDamageType(
            formula = submission.expression,
            damageType = weapon.damageType,
            amount = submission.total.coerceAtLeast(0),
            target = target
        )
        return ActionResolution.Completed(
            RuleOutcome(
                targetId = target.id,
                hit = true,
                critical = critical,
                damage = listOf(component)
            )
        )
    }

    fun resolveSpell(
        character: Map<String, String>,
        spell: SpellProfile,
        target: Combatant?,
        submission: DiceSubmission,
        expectedExpression: String = "1d20"
    ): ActionResolution {
        return when (spell.resolutionType) {
            SpellResolutionType.ATTACK -> resolveSpellAttack(
                character, spell, target, submission, expectedExpression
            )
            SpellResolutionType.SAVING_THROW -> resolveSavingThrow(
                character, spell, target, submission, expectedExpression
            )
            SpellResolutionType.AUTOMATIC -> spellEffectRequest(spell)
            SpellResolutionType.HEALING -> ActionResolution.NeedsEffectRoll(
                expression = spell.healingFormula
                    ?: return ActionResolution.Invalid(
                        RuleError("MISSING_HEALING_FORMULA", "法术 ${spell.name} 缺少治疗公式")
                    ),
                resourceChanges = spellSlotChange(spell)
            )
        }
    }

    fun resolveSpellEffect(
        spell: SpellProfile,
        target: Combatant?,
        submission: DiceSubmission,
        expectedExpression: String,
        critical: Boolean,
        targetSaved: Boolean?
    ): ActionResolution {
        submission.validateAgainst(expectedExpression)?.let {
            return ActionResolution.Invalid(it)
        }
        if (spell.resolutionType == SpellResolutionType.HEALING) {
            return ActionResolution.Completed(
                RuleOutcome(
                    healing = submission.total.coerceAtLeast(0),
                    ongoingEffects = spell.ongoingEffect
                        ?.instantiate(spell.spellId, CombatStateManager.PLAYER_ID)
                        ?.let(::listOf)
                        .orEmpty()
                )
            )
        }
        val resolvedTarget = target ?: return ActionResolution.Invalid(
            RuleError("MISSING_SPELL_TARGET", "法术 ${spell.name} 缺少目标")
        )
        var amount = submission.total.coerceAtLeast(0)
        if (targetSaved == true) {
            amount = if (spell.halfDamageOnSave) amount / 2 else 0
        }
        val component = applyDamageType(
            formula = submission.expression,
            damageType = spell.damageType.orEmpty(),
            amount = amount,
            target = resolvedTarget
        )
        return ActionResolution.Completed(
            RuleOutcome(
                targetId = resolvedTarget.id,
                hit = if (spell.resolutionType == SpellResolutionType.ATTACK) true else null,
                critical = critical,
                targetSaved = targetSaved,
                damage = listOf(component),
                ongoingEffects = if (targetSaved == true) {
                    emptyList()
                } else {
                    spell.ongoingEffect
                        ?.instantiate(spell.spellId, resolvedTarget.id)
                        ?.let(::listOf)
                        .orEmpty()
                }
            )
        )
    }

    fun spellMissOutcome(spell: SpellProfile, targetId: String): RuleOutcome {
        return RuleOutcome(
            targetId = targetId,
            hit = false,
            resourceChanges = spellSlotChange(spell)
        )
    }

    fun spellResourceChanges(spell: SpellProfile): Map<String, Int> = spellSlotChange(spell)

    private fun resolveSpellAttack(
        character: Map<String, String>,
        spell: SpellProfile,
        target: Combatant?,
        submission: DiceSubmission,
        expectedExpression: String
    ): ActionResolution {
        val resolvedTarget = target ?: return ActionResolution.Invalid(
            RuleError("MISSING_SPELL_TARGET", "法术 ${spell.name} 缺少目标")
        )
        submission.validateAgainst(expectedExpression)?.let { return ActionResolution.Invalid(it) }
        val abilityId = spell.abilityId ?: return ActionResolution.Invalid(
            RuleError("MISSING_SPELL_ABILITY", "法术 ${spell.name} 缺少施法属性")
        )
        val abilityModifier = abilityModifier(character, abilityId)
            ?: return ActionResolution.Invalid(
                RuleError("MISSING_CHARACTER_STAT", "角色缺少施法属性 $abilityId")
            )
        val naturalRoll = submission.keptTerms.singleOrNull()?.value ?: submission.total
        val critical = naturalRoll == 20
        val hit = naturalRoll != 1 && (
            critical || naturalRoll + abilityModifier + proficiencyBonus(character) +
                spell.attackBonus >= resolvedTarget.ac
        )
        if (!hit) {
            return ActionResolution.Completed(spellMissOutcome(spell, resolvedTarget.id))
        }
        val formula = spell.damageFormula ?: return ActionResolution.Completed(
            RuleOutcome(
                targetId = resolvedTarget.id,
                hit = true,
                critical = critical,
                resourceChanges = spellSlotChange(spell),
                ongoingEffects = spell.ongoingEffect
                    ?.instantiate(spell.spellId, resolvedTarget.id)
                    ?.let(::listOf)
                    .orEmpty()
            )
        )
        return ActionResolution.NeedsEffectRoll(
            expression = if (critical) DamageRoller.criticalFormula(formula) else formula,
            critical = critical,
            resourceChanges = spellSlotChange(spell)
        )
    }

    private fun resolveSavingThrow(
        character: Map<String, String>,
        spell: SpellProfile,
        target: Combatant?,
        submission: DiceSubmission,
        expectedExpression: String
    ): ActionResolution {
        val resolvedTarget = target ?: return ActionResolution.Invalid(
            RuleError("MISSING_SPELL_TARGET", "法术 ${spell.name} 缺少目标")
        )
        submission.validateAgainst(expectedExpression)?.let { return ActionResolution.Invalid(it) }
        val saveAbility = spell.saveAbilityId?.lowercase() ?: return ActionResolution.Invalid(
            RuleError("MISSING_SAVE_ABILITY", "法术 ${spell.name} 缺少豁免属性")
        )
        val saveBonus = resolvedTarget.savingThrows[saveAbility] ?: return ActionResolution.Invalid(
            RuleError("MISSING_TARGET_SAVE", "目标 ${resolvedTarget.name} 缺少 $saveAbility 豁免加值")
        )
        val dc = spell.saveDc ?: spell.abilityId?.let { abilityId ->
            abilityModifier(character, abilityId)?.let { 8 + proficiencyBonus(character) + it }
        } ?: return ActionResolution.Invalid(
            RuleError("MISSING_SPELL_DC", "法术 ${spell.name} 无法计算豁免 DC")
        )
        val targetSaved = submission.total + saveBonus >= dc
        val formula = spell.damageFormula ?: return ActionResolution.Completed(
            RuleOutcome(
                targetId = resolvedTarget.id,
                targetSaved = targetSaved,
                resourceChanges = spellSlotChange(spell),
                ongoingEffects = if (targetSaved) {
                    emptyList()
                } else {
                    spell.ongoingEffect
                        ?.instantiate(spell.spellId, resolvedTarget.id)
                        ?.let(::listOf)
                        .orEmpty()
                }
            )
        )
        return ActionResolution.NeedsEffectRoll(
            expression = formula,
            targetSaved = targetSaved,
            resourceChanges = spellSlotChange(spell)
        )
    }

    private fun spellEffectRequest(spell: SpellProfile): ActionResolution {
        val formula = spell.damageFormula ?: return ActionResolution.Invalid(
            RuleError("MISSING_DAMAGE_FORMULA", "法术 ${spell.name} 缺少效果公式")
        )
        return ActionResolution.NeedsEffectRoll(
            expression = formula,
            resourceChanges = spellSlotChange(spell)
        )
    }

    private fun applyDamageType(
        formula: String,
        damageType: String,
        amount: Int,
        target: Combatant
    ): DamageComponent {
        val normalizedType = damageType.trim().lowercase()
        val finalAmount = when {
            normalizedType in target.immunities -> 0
            normalizedType in target.resistances -> amount / 2
            normalizedType in target.vulnerabilities -> amount * 2
            else -> amount
        }
        return DamageComponent(
            formula = formula,
            type = normalizedType,
            rawAmount = amount,
            finalAmount = finalAmount
        )
    }

    private fun spellSlotChange(spell: SpellProfile): Map<String, Int> {
        return if (spell.slotLevel > 0 && !spell.isRitual) {
            mapOf("spell_slots.level_${spell.slotLevel}" to -1)
        } else {
            emptyMap()
        }
    }

    private fun abilityModifier(
        character: Map<String, String>,
        ability: AttackAbility
    ): Int? {
        return when (ability) {
            AttackAbility.STR -> abilityModifier(character, "str")
            AttackAbility.DEX -> abilityModifier(character, "dex")
            AttackAbility.FINESSE -> listOfNotNull(
                abilityModifier(character, "str"),
                abilityModifier(character, "dex")
            ).maxOrNull()
        }
    }

    private fun abilityModifier(character: Map<String, String>, abilityId: String): Int? {
        val normalized = abilityId.lowercase()
        character["${normalized}_mod"]?.toIntOrNull()?.let { return it }
        val score = character[normalized]?.toIntOrNull() ?: return null
        return Math.floorDiv(score - 10, 2)
    }

    private fun proficiencyBonus(character: Map<String, String>): Int {
        character["prof_bonus"]?.toIntOrNull()?.let { return it }
        val level = character["level"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        return 2 + (level - 1) / 4
    }

    private fun missingAbility(ability: AttackAbility): ActionResolution.Invalid {
        return ActionResolution.Invalid(
            RuleError("MISSING_CHARACTER_STAT", "角色缺少 $ability 攻击所需属性")
        )
    }

    private fun appendModifier(formula: String, modifier: Int): String {
        return when {
            modifier > 0 -> "$formula+$modifier"
            modifier < 0 -> "$formula$modifier"
            else -> formula
        }
    }
}
