package xyz.sakulik.d20.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 泛型化的 AI 交互协议
 * 用于描述 AI 返回的叙事内容及随后的游戏逻辑事件
 */
@Serializable
data class AIResponse(
    @SerialName("narrative")
    val narrative: String, // UI 打字机呈现的故事文本
    @SerialName("game_events")
    val gameEvents: List<GameEvent>
)

/**
 * 游戏事件密封类
 */
@Serializable
sealed class GameEvent {

    /**
     * 判定请求事件
     * 例如 AI 要求玩家掷骰子来决定行为结果
     * @param expression 骰子表达式，如 "1d100", "2d20kh1"
     * @param threshold 判定阈值，可选
     * @param reason 判定原因描述
     */
    @Serializable
    @SerialName("require_roll")
    data class RequireRoll(
        val expression: String,
        val threshold: Int? = null,
        val reason: String,
        @SerialName("action_id")
        val actionId: String? = null,
        @SerialName("stat_id")
        val statId: String? = null,
        @SerialName("target_value")
        val targetValue: Int? = null,
        val modifier: Int? = null,
        @SerialName("target_id")
        val targetId: String? = null,
        @SerialName("slot_level")
        val slotLevel: Int? = null,
        @SerialName("weapon_id")
        val weaponId: String? = null,
        @SerialName("spell_id")
        val spellId: String? = null
    ) : GameEvent()

    /**
     * 获得物品事件
     * @param name 物品名称
     * @param description 描述
     * @param category 类别 (武器/防具/消耗品)
     * @param modifiers 修正值字典，如 {"strength": 1}
     */
    @Serializable
    @SerialName("add_item")
    data class AddItem(
        val name: String,
        val description: String,
        val category: String,
        val modifiers: Map<String, JsonElement> = emptyMap()
    ) : GameEvent()

    /**
     * 进入战斗事件
     * @param combatants 只包含叙事身份与受信任规则包档案引用
     */
    @Serializable
    @SerialName("start_combat")
    data class StartCombat(
        val combatants: List<EncounterParticipantRequest>
    ) : GameEvent()

}

@Serializable
data class EncounterParticipantRequest(
    val id: String,
    val name: String,
    @SerialName("profile_id")
    val profileId: String
)

/**
 * 角色生成协议
 */
@Serializable
data class CharacterGenResponse(
    val name: String,
    val stats: Map<String, JsonElement>,
    val bio: String,
    val items: List<ItemGenModel> = emptyList()
)

@Serializable
data class ItemGenModel(
    val name: String,
    val description: String,
    val category: String,
    val modifiers: Map<String, JsonElement> = emptyMap()
)

sealed class CharacterGenState {
    object Loading : CharacterGenState()
    data class Success(val data: CharacterGenResponse) : CharacterGenState()
    data class Error(val throwable: Throwable) : CharacterGenState()
}
