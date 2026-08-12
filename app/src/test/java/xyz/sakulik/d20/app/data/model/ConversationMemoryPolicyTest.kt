package xyz.sakulik.d20.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationMemoryPolicyTest {
    @Test
    fun clampsRecentTurnsToSupportedRange() {
        assertEquals(
            ConversationMemoryPolicy.MIN_RECENT_TURNS,
            ConversationMemoryPolicy.sanitizeRecentTurns(1)
        )
        assertEquals(24, ConversationMemoryPolicy.sanitizeRecentTurns(24))
        assertEquals(
            ConversationMemoryPolicy.MAX_RECENT_TURNS,
            ConversationMemoryPolicy.sanitizeRecentTurns(100)
        )
    }

    @Test
    fun recentCharacterBudgetGrowsWithConfiguredTurns() {
        assertEquals(12_000, ConversationMemoryPolicy.recentCharacterBudget(8))
        assertEquals(18_000, ConversationMemoryPolicy.recentCharacterBudget(24))
        assertEquals(36_000, ConversationMemoryPolicy.recentCharacterBudget(48))
    }
}
