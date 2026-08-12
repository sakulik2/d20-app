package xyz.sakulik.d20.app.domain.combat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class EffectTickTiming {
    TURN_START,
    TURN_END
}

@Serializable
enum class EffectOperation {
    DAMAGE,
    HEAL
}

@Serializable
enum class EffectStackPolicy {
    REPLACE,
    REFRESH,
    STACK
}

@Serializable
data class OngoingEffect(
    val id: String,
    val name: String,
    @SerialName("target_id")
    val targetId: String,
    @SerialName("source_id")
    val sourceId: String,
    val timing: EffectTickTiming,
    val operation: EffectOperation,
    val amount: Int,
    @SerialName("remaining_ticks")
    val remainingTicks: Int,
    @SerialName("stack_key")
    val stackKey: String = sourceId,
    @SerialName("stack_policy")
    val stackPolicy: EffectStackPolicy = EffectStackPolicy.REFRESH
) {
    init {
        require(id.isNotBlank()) { "持续效果 ID 不能为空" }
        require(name.isNotBlank()) { "持续效果名称不能为空" }
        require(targetId.isNotBlank()) { "持续效果目标不能为空" }
        require(sourceId.isNotBlank()) { "持续效果来源不能为空" }
        require(amount > 0) { "持续效果数值必须大于 0" }
        require(remainingTicks > 0) { "持续效果剩余次数必须大于 0" }
        require(stackKey.isNotBlank()) { "持续效果堆叠键不能为空" }
    }
}

data class EffectTickResult(
    val effect: OngoingEffect,
    val previousHp: Int,
    val currentHp: Int
) {
    val appliedAmount: Int
        get() = kotlin.math.abs(currentHp - previousHp)
}

data class TurnAdvanceResult(
    val state: CombatState,
    val ticks: List<EffectTickResult>,
    val combatEnded: Boolean
)

fun List<OngoingEffect>.withEffect(effect: OngoingEffect): List<OngoingEffect> {
    val matchingIndex = indexOfFirst { existing ->
        existing.targetId == effect.targetId && existing.stackKey == effect.stackKey
    }
    return when {
        matchingIndex < 0 || effect.stackPolicy == EffectStackPolicy.STACK -> this + effect
        effect.stackPolicy == EffectStackPolicy.REPLACE ->
            toMutableList().apply { set(matchingIndex, effect) }
        else -> toMutableList().apply {
            val existing = get(matchingIndex)
            set(
                matchingIndex,
                existing.copy(remainingTicks = maxOf(existing.remainingTicks, effect.remainingTicks))
            )
        }
    }
}
