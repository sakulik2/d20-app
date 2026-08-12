package xyz.sakulik.d20.app.data.model

object ConversationMemoryPolicy {
    const val DEFAULT_RECENT_TURNS = 16
    const val MIN_RECENT_TURNS = 8
    const val MAX_RECENT_TURNS = 48
    const val SUMMARY_CHARACTER_BUDGET = 8_000
    const val LORE_CHARACTER_BUDGET = 6_000
    const val LORE_MAX_ENTRIES = 6

    fun sanitizeRecentTurns(turns: Int): Int {
        return turns.coerceIn(MIN_RECENT_TURNS, MAX_RECENT_TURNS)
    }

    fun recentCharacterBudget(turns: Int): Int {
        return (sanitizeRecentTurns(turns) * CHARACTERS_PER_TURN)
            .coerceAtLeast(MIN_RECENT_CHARACTER_BUDGET)
    }

    private const val CHARACTERS_PER_TURN = 750
    private const val MIN_RECENT_CHARACTER_BUDGET = 12_000
}
