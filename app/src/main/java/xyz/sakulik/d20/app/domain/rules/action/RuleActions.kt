package xyz.sakulik.d20.app.domain.rules.action

import kotlinx.serialization.Serializable
import xyz.sakulik.d20.app.data.local.ItemEntity
import xyz.sakulik.d20.app.domain.combat.EffectOperation
import xyz.sakulik.d20.app.domain.combat.EffectStackPolicy
import xyz.sakulik.d20.app.domain.combat.EffectTickTiming
import xyz.sakulik.d20.app.domain.combat.OngoingEffect

enum class AttackAbility {
    STR,
    DEX,
    FINESSE;

    companion object {
        fun parse(value: String?): AttackAbility? = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true)
        }
    }
}

data class WeaponProfile(
    val itemId: String,
    val name: String,
    val attackAbility: AttackAbility,
    val proficient: Boolean,
    val attackBonus: Int,
    val damageFormula: String,
    val damageAbility: AttackAbility?,
    val damageBonus: Int,
    val damageType: String,
    val targeting: TargetingMode = TargetingMode.SINGLE,
    val maxTargets: Int? = null
)

enum class TargetingMode {
    SINGLE,
    MULTIPLE,
    ALL_ENEMIES,
    SELF;

    companion object {
        fun parse(value: String?): TargetingMode? = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true)
        }
    }
}

enum class SpellResolutionType {
    ATTACK,
    SAVING_THROW,
    AUTOMATIC,
    HEALING
}

data class SpellProfile(
    val spellId: String,
    val name: String,
    val resolutionType: SpellResolutionType,
    val slotLevel: Int,
    val isRitual: Boolean,
    val abilityId: String?,
    val attackBonus: Int,
    val targetId: String?,
    val saveAbilityId: String?,
    val saveDc: Int?,
    val damageFormula: String?,
    val damageType: String?,
    val halfDamageOnSave: Boolean,
    val healingFormula: String?,
    val targeting: TargetingMode = TargetingMode.SINGLE,
    val maxTargets: Int? = null,
    val ongoingEffect: OngoingEffectSpec? = null
)

data class OngoingEffectSpec(
    val name: String,
    val timing: EffectTickTiming,
    val operation: EffectOperation,
    val amount: Int,
    val durationTicks: Int,
    val stackPolicy: EffectStackPolicy,
    val stackKey: String
) {
    fun instantiate(sourceId: String, targetId: String): OngoingEffect = OngoingEffect(
        id = java.util.UUID.randomUUID().toString(),
        name = name,
        targetId = targetId,
        sourceId = sourceId,
        timing = timing,
        operation = operation,
        amount = amount,
        remainingTicks = durationTicks,
        stackKey = stackKey,
        stackPolicy = stackPolicy
    )
}

@Serializable
data class DamageComponent(
    val formula: String,
    val type: String,
    val rawAmount: Int,
    val finalAmount: Int
)

@Serializable
data class RuleOutcome(
    val targetId: String? = null,
    val hit: Boolean? = null,
    val critical: Boolean = false,
    val targetSaved: Boolean? = null,
    val damage: List<DamageComponent> = emptyList(),
    val healing: Int = 0,
    val resourceChanges: Map<String, Int> = emptyMap(),
    val ongoingEffects: List<OngoingEffect> = emptyList()
)

data class BatchRuleOutcome(
    val outcomes: List<RuleOutcome>,
    val resourceChanges: Map<String, Int> = emptyMap()
) {
    init {
        require(outcomes.mapNotNull { it.targetId }.distinct().size ==
            outcomes.mapNotNull { it.targetId }.size) {
            "批量裁决不能重复包含同一个目标"
        }
    }
}

fun ItemEntity.toWeaponProfileOrNull(): WeaponProfile? {
    if (!category.contains("武器", ignoreCase = true) &&
        !category.contains("weapon", ignoreCase = true)
    ) {
        return null
    }
    val damageFormula = modifiers["damage_formula"]
        ?: modifiers["damage"]
        ?: return null
    val targeting = modifiers["targeting"]?.let { value -> TargetingMode.parse(value) }
        ?: if ("targeting" in modifiers) return null else TargetingMode.SINGLE
    val attackAbility = AttackAbility.parse(modifiers["attack_ability"]) ?: AttackAbility.STR
    return WeaponProfile(
        itemId = id,
        name = name,
        attackAbility = attackAbility,
        proficient = modifiers["proficient"]?.toBooleanStrictOrNull() ?: false,
        attackBonus = modifiers["attack_bonus"]?.toIntOrNull() ?: 0,
        damageFormula = damageFormula,
        damageAbility = AttackAbility.parse(modifiers["damage_ability"]) ?: attackAbility,
        damageBonus = modifiers["damage_bonus"]?.toIntOrNull() ?: 0,
        damageType = modifiers["damage_type"]?.trim()?.lowercase().orEmpty(),
        targeting = targeting,
        maxTargets = modifiers["max_targets"]?.toIntOrNull()
    ).takeIf {
        it.damageType.isNotBlank() &&
            it.targeting != TargetingMode.SELF &&
            (it.maxTargets == null || it.maxTargets > 0)
    }
}

fun ItemEntity.toSpellProfileOrNull(): SpellProfile? {
    if (!category.contains("法术", ignoreCase = true) &&
        !category.contains("spell", ignoreCase = true)
    ) {
        return null
    }
    val resolutionType = modifiers["resolution_type"]
        ?.let { value ->
            SpellResolutionType.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        }
        ?: return null
    val slotLevel = modifiers["slot_level"]?.toIntOrNull() ?: 0
    if (slotLevel !in 0..9) return null
    val targeting = modifiers["targeting"]?.let { value -> TargetingMode.parse(value) }
        ?: if ("targeting" in modifiers) {
            return null
        } else if (resolutionType == SpellResolutionType.HEALING) {
            TargetingMode.SELF
        } else {
            TargetingMode.SINGLE
        }
    val ongoingEffect = parseOngoingEffectSpec(modifiers, id, name)
    if (modifiers.keys.any { it.startsWith("effect_") } && ongoingEffect == null) return null
    val profile = SpellProfile(
        spellId = id,
        name = name,
        resolutionType = resolutionType,
        slotLevel = slotLevel,
        isRitual = modifiers["ritual"]?.toBooleanStrictOrNull() ?: false,
        abilityId = modifiers["ability"]?.lowercase(),
        attackBonus = modifiers["attack_bonus"]?.toIntOrNull() ?: 0,
        targetId = null,
        saveAbilityId = modifiers["save_ability"]?.lowercase(),
        saveDc = modifiers["save_dc"]?.toIntOrNull(),
        damageFormula = modifiers["damage_formula"] ?: modifiers["damage"],
        damageType = modifiers["damage_type"]?.lowercase(),
        halfDamageOnSave = modifiers["half_on_save"]?.toBooleanStrictOrNull() ?: false,
        healingFormula = modifiers["healing_formula"] ?: modifiers["healing"],
        targeting = targeting,
        maxTargets = modifiers["max_targets"]?.toIntOrNull(),
        ongoingEffect = ongoingEffect
    )
    return profile.takeIf {
        (it.maxTargets == null || it.maxTargets > 0) &&
            (resolutionType != SpellResolutionType.HEALING || it.targeting == TargetingMode.SELF) &&
            !(it.targeting == TargetingMode.SELF && resolutionType != SpellResolutionType.HEALING) &&
        when (resolutionType) {
            SpellResolutionType.ATTACK ->
                !it.abilityId.isNullOrBlank() &&
                    (it.damageFormula.isNullOrBlank() || !it.damageType.isNullOrBlank())
            SpellResolutionType.SAVING_THROW ->
                !it.saveAbilityId.isNullOrBlank() &&
                    (it.saveDc != null || !it.abilityId.isNullOrBlank()) &&
                    (it.damageFormula.isNullOrBlank() || !it.damageType.isNullOrBlank())
            SpellResolutionType.AUTOMATIC ->
                !it.damageFormula.isNullOrBlank() && !it.damageType.isNullOrBlank()
            SpellResolutionType.HEALING ->
                !it.healingFormula.isNullOrBlank()
        }
    }
}

private fun parseOngoingEffectSpec(
    modifiers: Map<String, String>,
    spellId: String,
    spellName: String
): OngoingEffectSpec? {
    val effectKeys = modifiers.keys.filter { it.startsWith("effect_") }
    if (effectKeys.isEmpty()) return null
    val timing = modifiers["effect_timing"]?.let { raw ->
        EffectTickTiming.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    } ?: return null
    val operation = modifiers["effect_operation"]?.let { raw ->
        EffectOperation.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    } ?: return null
    val amount = modifiers["effect_amount"]?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val duration = modifiers["effect_duration"]?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val stackPolicy = modifiers["effect_stack_policy"]?.let { raw ->
        EffectStackPolicy.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
    } ?: EffectStackPolicy.REFRESH
    return OngoingEffectSpec(
        name = modifiers["effect_name"]?.takeIf { it.isNotBlank() } ?: spellName,
        timing = timing,
        operation = operation,
        amount = amount,
        durationTicks = duration,
        stackPolicy = stackPolicy,
        stackKey = modifiers["effect_stack_key"]?.takeIf { it.isNotBlank() } ?: spellId
    )
}
