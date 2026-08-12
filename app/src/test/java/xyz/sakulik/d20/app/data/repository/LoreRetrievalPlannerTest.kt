package xyz.sakulik.d20.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.sakulik.d20.app.data.local.LoreEntryEntity

class LoreRetrievalPlannerTest {
    @Test
    fun currentInputRanksAboveHistoryOnlyMatch() {
        val current = lore("current", "灰港", "灰港", updated = 1)
        val historical = lore("historical", "旧王都", "王都", updated = 2)

        val selected = LoreRetrievalPlanner.select(
            entries = listOf(historical, current),
            userText = "我们进入灰港",
            recentMessages = listOf(message("刚刚离开王都"))
        )

        assertEquals(listOf("current", "historical"), selected.map(LoreEntryEntity::id))
    }

    @Test
    fun supportsChineseKeywordSeparators() {
        val selected = LoreRetrievalPlanner.select(
            entries = listOf(
                lore("chinese", "港口档案", "港口，码头；商会\n船长")
            ),
            userText = "船长让我们去码头寻找商会",
            recentMessages = emptyList()
        )

        assertEquals(listOf("chinese"), selected.map(LoreEntryEntity::id))
    }

    @Test
    fun respectsEntryLimitAfterRanking() {
        val selected = LoreRetrievalPlanner.select(
            entries = listOf(
                lore("high", "灰港", "灰港，码头"),
                lore("medium", "商会档案", "商会，仓库"),
                lore("low", "水手档案", "船长")
            ),
            userText = "我们抵达灰港码头，寻找商会仓库和船长",
            recentMessages = emptyList(),
            maxEntries = 2
        )

        assertEquals(listOf("high", "medium"), selected.map(LoreEntryEntity::id))
    }

    @Test
    fun excludesUnmatchedAndOverBudgetEntries() {
        val oversized = lore("large", "灰港档案", "灰港", content = "x".repeat(100))
        val fitted = lore("small", "灰港", "灰港", content = "短设定")
        val unrelated = lore("other", "高山", "雪峰", content = "无关设定")

        val selected = LoreRetrievalPlanner.select(
            entries = listOf(oversized, fitted, unrelated),
            userText = "抵达灰港",
            recentMessages = emptyList(),
            characterBudget = 20
        )

        assertEquals(listOf("small"), selected.map(LoreEntryEntity::id))
    }

    private fun lore(
        id: String,
        title: String,
        keywords: String,
        content: String = "设定内容",
        updated: Long = 0
    ) = LoreEntryEntity(
        id = id,
        campaignId = "campaign",
        title = title,
        keywords = keywords,
        content = content,
        lastUpdated = updated
    )

    private fun message(content: String) = MemoryMessage(
        id = 1,
        role = "assistant",
        content = content
    )
}
