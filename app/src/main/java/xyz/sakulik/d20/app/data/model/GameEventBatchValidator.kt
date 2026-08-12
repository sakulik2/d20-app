package xyz.sakulik.d20.app.data.model

private const val MAX_EVENTS_PER_TURN = 12
private const val MAX_COMBATANTS_PER_BATTLE = 20
private val SAFE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")

data class EventBatchValidationError(val message: String)

object GameEventBatchValidator {
    fun validate(events: List<GameEvent>): EventBatchValidationError? {
        if (events.size > MAX_EVENTS_PER_TURN) {
            return EventBatchValidationError("单次回复的事件数量不能超过 $MAX_EVENTS_PER_TURN")
        }

        val rollRequests = events.filterIsInstance<GameEvent.RequireRoll>()
        if (rollRequests.size > 1) {
            return EventBatchValidationError("单次回复最多只能包含一个 require_roll")
        }
        if (rollRequests.isNotEmpty() && events.size != 1) {
            return EventBatchValidationError("require_roll 必须是本次回复中唯一的游戏事件")
        }
        if (events.any { it is GameEvent.StartCombat } && events.size != 1) {
            return EventBatchValidationError("start_combat 必须是本次回复中唯一的游戏事件")
        }

        events.forEachIndexed { index, event ->
            val error = validateEvent(event)
            if (error != null) return EventBatchValidationError("game_events[$index]：$error")
        }
        return null
    }

    private fun validateEvent(event: GameEvent): String? {
        return when (event) {
            is GameEvent.RequireRoll -> when {
                event.expression.isBlank() -> "骰式不能为空"
                event.expression.length > 64 -> "骰式过长"
                event.reason.isBlank() -> "检定原因不能为空"
                event.reason.length > 200 -> "检定原因过长"
                event.actionId != null && !SAFE_ID.matches(event.actionId) -> "action_id 格式无效"
                event.statId != null && !SAFE_ID.matches(event.statId) -> "stat_id 格式无效"
                event.targetId != null && !SAFE_ID.matches(event.targetId) -> "target_id 格式无效"
                event.weaponId != null && !SAFE_ID.matches(event.weaponId) -> "weapon_id 格式无效"
                event.spellId != null && !SAFE_ID.matches(event.spellId) -> "spell_id 格式无效"
                event.threshold != null && event.threshold !in -1_000_000..1_000_000 ->
                    "threshold 超出安全范围"
                event.targetValue != null && event.targetValue !in -1_000_000..1_000_000 ->
                    "target_value 超出安全范围"
                event.modifier != null && event.modifier !in -1_000..1_000 ->
                    "modifier 超出安全范围"
                event.slotLevel != null && event.slotLevel !in 0..9 -> "slot_level 必须在 0..9"
                else -> null
            }
            is GameEvent.AddItem -> when {
                event.name.isBlank() || event.name.length > 100 -> "物品名称无效"
                event.description.isBlank() -> "物品描述不能为空"
                event.description.length > 2_000 -> "物品描述过长"
                event.category.isBlank() || event.category.length > 50 -> "物品类别无效"
                event.modifiers.isNotEmpty() -> "模型不能创建带规则修正的可信物品档案"
                else -> null
            }
            is GameEvent.StartCombat -> validateCombatants(event)
        }
    }

    private fun validateCombatants(event: GameEvent.StartCombat): String? {
        val combatants = event.combatants
        if (combatants.isEmpty()) return "战斗参与者不能为空"
        if (combatants.size > MAX_COMBATANTS_PER_BATTLE) {
            return "敌方参与者不能超过 $MAX_COMBATANTS_PER_BATTLE 个"
        }
        if (combatants.map { it.id.lowercase() }.distinct().size != combatants.size) {
            return "敌方参与者 ID 不能重复"
        }
        combatants.forEach { combatant ->
            when {
                !SAFE_ID.matches(combatant.id) -> return "敌方参与者 ID 格式无效"
                combatant.id.equals("player", ignoreCase = true) -> return "敌方参与者不能使用保留 ID player"
                combatant.name.isBlank() || combatant.name.length > 100 -> return "敌方参与者名称无效"
                !SAFE_ID.matches(combatant.profileId) -> return "敌方 profile_id 格式无效"
            }
        }
        return null
    }
}
