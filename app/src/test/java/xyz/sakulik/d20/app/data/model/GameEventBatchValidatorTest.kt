package xyz.sakulik.d20.app.data.model

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.sakulik.d20.app.domain.combat.CombatantDefinition

class GameEventBatchValidatorTest {
    @Test
    fun acceptsSingleRollRequest() {
        val error = GameEventBatchValidator.validate(
            listOf(GameEvent.RequireRoll("1d20", reason = "敏捷检定"))
        )

        assertNull(error)
    }

    @Test
    fun rejectsMultipleRollRequests() {
        val error = GameEventBatchValidator.validate(
            listOf(
                GameEvent.RequireRoll("1d20", reason = "检定一"),
                GameEvent.RequireRoll("1d20", reason = "检定二")
            )
        )

        assertTrue(error?.message?.contains("最多只能包含一个") == true)
    }

    @Test
    fun rejectsRollMixedWithNarrativeItem() {
        val error = GameEventBatchValidator.validate(
            listOf(
                GameEvent.RequireRoll("1d20", reason = "调查"),
                GameEvent.AddItem("信件", "旧信", "线索", JsonObject(emptyMap()))
            )
        )

        assertTrue(error?.message?.contains("唯一") == true)
    }

    @Test
    fun rejectsRuleBearingGeneratedItem() {
        val error = GameEventBatchValidator.validate(
            listOf(
                GameEvent.AddItem(
                    name = "长剑",
                    description = "武器",
                    category = "武器",
                    modifiers = mapOf("damage_formula" to kotlinx.serialization.json.JsonPrimitive("1d8"))
                )
            )
        )

        assertTrue(error?.message?.contains("规则修正") == true)
    }

    @Test
    fun rejectsEmptyCombat() {
        val error = GameEventBatchValidator.validate(listOf(GameEvent.StartCombat(emptyList())))

        assertTrue(error?.message?.contains("不能为空") == true)
    }

    @Test
    fun rejectsInvalidCombatantRange() {
        val error = GameEventBatchValidator.validate(
            listOf(
                GameEvent.StartCombat(
                    listOf(CombatantDefinition("goblin", "地精", 10, 12, -1, 7))
                )
            )
        )

        assertTrue(error?.message?.contains("HP") == true)
    }

    @Test
    fun rejectsUnsafeRollNumbers() {
        val error = GameEventBatchValidator.validate(
            listOf(
                GameEvent.RequireRoll(
                    expression = "1d20",
                    threshold = Int.MAX_VALUE,
                    reason = "异常检定"
                )
            )
        )

        assertTrue(error?.message?.contains("threshold") == true)
    }

    @Test
    fun rejectsUnsafeArchiveIds() {
        val error = GameEventBatchValidator.validate(
            listOf(
                GameEvent.RequireRoll(
                    expression = "1d20",
                    reason = "攻击",
                    weaponId = "../../weapon"
                )
            )
        )

        assertTrue(error?.message?.contains("weapon_id") == true)
    }
}
