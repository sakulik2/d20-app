package xyz.sakulik.d20.app.data.repository

import xyz.sakulik.d20.app.data.local.LoreEntryEntity

internal object LoreRetrievalPlanner {
    fun select(
        entries: List<LoreEntryEntity>,
        userText: String,
        recentMessages: List<MemoryMessage>,
        maxEntries: Int = DEFAULT_MAX_ENTRIES,
        characterBudget: Int = DEFAULT_CHARACTER_BUDGET
    ): List<LoreEntryEntity> {
        if (entries.isEmpty() || characterBudget <= 0 || maxEntries <= 0) return emptyList()
        val currentText = userText.normalizeForRetrieval()
        val recentText = recentMessages.takeLast(RECENT_MESSAGE_LIMIT)
            .joinToString(" ") { message -> message.content }
            .normalizeForRetrieval()

        val ranked = entries.mapNotNull { entry ->
            val title = entry.title.normalizeForRetrieval()
            val keywords = entry.keywords
                .split(KEYWORD_SEPARATOR)
                .map { keyword -> keyword.normalizeForRetrieval() }
                .filter { keyword -> keyword.length >= MIN_KEYWORD_LENGTH }
                .distinct()
            val currentTitle = title.isNotEmpty() && title in currentText
            val recentTitle = title.isNotEmpty() && title in recentText
            val currentMatches = keywords.count { keyword -> keyword in currentText }
            val recentMatches = keywords.count { keyword -> keyword in recentText }
            val score = (if (currentTitle) 20 else 0) +
                (if (recentTitle) 6 else 0) +
                currentMatches * 12 +
                recentMatches * 3
            score.takeIf { it > 0 }?.let { value -> RankedLore(entry, value) }
        }.sortedWith(
            compareByDescending<RankedLore>(RankedLore::score)
                .thenByDescending { rankedLore -> rankedLore.entry.lastUpdated }
                .thenBy { rankedLore -> rankedLore.entry.title }
        )

        val selected = mutableListOf<LoreEntryEntity>()
        var usedCharacters = 0
        for (rankedLore in ranked) {
            if (selected.size >= maxEntries) break
            val entryCharacters = rankedLore.entry.title.length +
                rankedLore.entry.category.length +
                rankedLore.entry.content.length
            if (usedCharacters + entryCharacters > characterBudget) continue
            selected.add(rankedLore.entry)
            usedCharacters += entryCharacters
        }
        return selected
    }

    private fun String.normalizeForRetrieval(): String {
        return lowercase().replace(WHITESPACE, " ").trim()
    }

    private data class RankedLore(
        val entry: LoreEntryEntity,
        val score: Int
    )

    private const val DEFAULT_MAX_ENTRIES = 5
    private const val DEFAULT_CHARACTER_BUDGET = 4_000
    private const val RECENT_MESSAGE_LIMIT = 4
    private const val MIN_KEYWORD_LENGTH = 2
    private val KEYWORD_SEPARATOR = Regex("[,，;；\\n\\r]+")
    private val WHITESPACE = Regex("\\s+")
}
