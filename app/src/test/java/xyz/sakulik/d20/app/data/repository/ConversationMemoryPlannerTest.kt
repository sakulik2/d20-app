package xyz.sakulik.d20.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMemoryPlannerTest {
    @Test
    fun keepsRecentCompleteTurnsAndNormalizesRoles() {
        val plan = ConversationMemoryPlanner.plan(
            messages = listOf(
                message(1, "system", "内部协议"),
                message(2, "USER", "第一轮"),
                message(3, "AI", "第一轮回应"),
                message(4, "user", "第二轮"),
                message(5, "assistant", "第二轮回应"),
                message(6, "user", "第三轮"),
                message(7, "assistant", "第三轮回应")
            ),
            maxTurns = 2
        )

        assertEquals(listOf(4L, 5L, 6L, 7L), plan.recentMessages.map(MemoryMessage::id))
        assertEquals(
            listOf("user", "assistant", "user", "assistant"),
            plan.recentMessages.map(MemoryMessage::role)
        )
        assertFalse(plan.olderSummary.contains("内部协议"))
        assertTrue(plan.olderSummary.contains("第一轮"))
    }

    @Test
    fun alwaysKeepsLatestTurnEvenWhenItExceedsRecentBudget() {
        val plan = ConversationMemoryPlanner.plan(
            messages = listOf(
                message(1, "user", "旧问题"),
                message(2, "assistant", "旧回答"),
                message(3, "user", "很长的最新问题"),
                message(4, "assistant", "很长的最新回答")
            ),
            maxTurns = 4,
            recentCharacterBudget = 4
        )

        assertEquals(listOf(3L, 4L), plan.recentMessages.map(MemoryMessage::id))
        assertTrue(plan.olderSummary.contains("旧问题"))
    }

    @Test
    fun summaryPrioritizesDecisionsAndRespectsBudget() {
        val messages = (1L..12L).map { id ->
            val content = if (id == 3L) {
                "玩家决定接受寻找失踪商队的任务，并答应前往灰港调查线索"
            } else {
                "普通寒暄 $id"
            }
            message(id, if (id % 2L == 1L) "user" else "assistant", content)
        }

        val plan = ConversationMemoryPlanner.plan(
            messages = messages,
            maxTurns = 1,
            summaryCharacterBudget = 240
        )

        assertTrue(plan.olderSummary.contains("失踪商队"))
        assertTrue(plan.olderSummary.length <= 240)
    }

    private fun message(id: Long, role: String, content: String) = MemoryMessage(
        id = id,
        role = role,
        content = content
    )
}
