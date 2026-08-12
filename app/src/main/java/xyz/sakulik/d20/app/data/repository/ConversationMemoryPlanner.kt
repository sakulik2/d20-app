package xyz.sakulik.d20.app.data.repository

import kotlin.math.ceil
import xyz.sakulik.d20.app.data.model.ConversationMemoryPolicy

internal data class MemoryMessage(
    val id: Long,
    val role: String,
    val content: String
)

internal data class ConversationContextPlan(
    val recentMessages: List<MemoryMessage>,
    val olderSummary: String
)

internal object ConversationMemoryPlanner {
    fun plan(
        messages: List<MemoryMessage>,
        maxTurns: Int,
        recentCharacterBudget: Int = ConversationMemoryPolicy.recentCharacterBudget(maxTurns),
        summaryCharacterBudget: Int = ConversationMemoryPolicy.SUMMARY_CHARACTER_BUDGET
    ): ConversationContextPlan {
        val normalized = messages
            .asSequence()
            .filter { message -> message.content.isNotBlank() }
            .map { message -> message.copy(role = message.role.normalizedRole()) }
            .filter { message -> message.role == "user" || message.role == "assistant" }
            .toList()
        if (normalized.isEmpty()) return ConversationContextPlan(emptyList(), "")

        val turns = normalized.toTurns()
        val selectedTurns = ArrayDeque<List<MemoryMessage>>()
        var selectedCharacters = 0
        for (turn in turns.asReversed()) {
            if (selectedTurns.size >= maxTurns.coerceAtLeast(1)) break
            val turnCharacters = turn.sumOf { message -> message.content.length }
            if (selectedTurns.isNotEmpty() && selectedCharacters + turnCharacters > recentCharacterBudget) {
                break
            }
            selectedTurns.addFirst(turn)
            selectedCharacters += turnCharacters
        }
        val recentMessages = selectedTurns.flatten()
        val firstRecentId = recentMessages.firstOrNull()?.id ?: Long.MAX_VALUE
        val olderMessages = normalized.filter { message -> message.id < firstRecentId }
        return ConversationContextPlan(
            recentMessages = recentMessages,
            olderSummary = summarize(olderMessages, summaryCharacterBudget)
        )
    }

    private fun List<MemoryMessage>.toTurns(): List<List<MemoryMessage>> {
        val turns = mutableListOf<MutableList<MemoryMessage>>()
        forEach { message ->
            if (message.role == "user" || turns.isEmpty()) {
                turns.add(mutableListOf(message))
            } else {
                turns.last().add(message)
            }
        }
        return turns
    }

    private fun summarize(messages: List<MemoryMessage>, budget: Int): String {
        if (messages.isEmpty() || budget <= 0) return ""
        val maxEntries = (budget / SUMMARY_ENTRY_TARGET_LENGTH).coerceIn(8, 40)
        val bucketSize = ceil(messages.size.toDouble() / maxEntries).toInt().coerceAtLeast(1)
        val selected = messages
            .chunked(bucketSize)
            .mapNotNull { bucket -> bucket.maxWithOrNull(summaryComparator) }
            .toMutableList()
        messages.takeLast(RECENT_OLDER_MESSAGES).forEach { message ->
            if (selected.none { candidate -> candidate.id == message.id }) selected.add(message)
        }

        val lines = selected
            .sortedBy(MemoryMessage::id)
            .map { message ->
                val speaker = if (message.role == "user") "玩家" else "主持"
                "$speaker：${message.content.toMemorySnippet()}"
            }
        val kept = mutableListOf<String>()
        var used = 0
        for (line in lines.asReversed()) {
            val separatorLength = if (kept.isEmpty()) 0 else 1
            val remaining = budget - used - separatorLength
            if (remaining <= 0) break
            val fittedLine = if (line.length <= remaining) {
                line
            } else {
                line.take(remaining).trimEnd()
            }
            if (fittedLine.isBlank()) break
            kept.add(0, fittedLine)
            used += fittedLine.length + separatorLength
        }
        return kept.joinToString("\n")
    }

    private val summaryComparator = compareBy<MemoryMessage> { message ->
        message.summaryScore()
    }.thenBy(MemoryMessage::id)

    private fun MemoryMessage.summaryScore(): Int {
        val normalized = content.lowercase()
        val keywordScore = MEMORY_KEYWORDS.count { keyword -> keyword in normalized } * 3
        val playerScore = if (role == "user") 2 else 0
        val outcomeScore = if ('【' in content || "检定" in content || "战斗" in content) 2 else 0
        return keywordScore + playerScore + outcomeScore
    }

    private fun String.toMemorySnippet(): String {
        val compact = replace(MARKDOWN_MARKERS, "")
            .replace(WHITESPACE, " ")
            .trim()
        if (compact.length <= MAX_SUMMARY_ENTRY_LENGTH) return compact
        return compact.take(MAX_SUMMARY_ENTRY_LENGTH - 1).trimEnd() + "…"
    }

    private fun String.normalizedRole(): String = when (trim().lowercase()) {
        "user" -> "user"
        "assistant", "ai" -> "assistant"
        else -> ""
    }

    private const val SUMMARY_ENTRY_TARGET_LENGTH = 160
    private const val MAX_SUMMARY_ENTRY_LENGTH = 180
    private const val RECENT_OLDER_MESSAGES = 8
    private val WHITESPACE = Regex("\\s+")
    private val MARKDOWN_MARKERS = Regex("[`*_>#]+")
    private val MEMORY_KEYWORDS = listOf(
        "决定", "答应", "拒绝", "承诺", "目标", "任务", "线索", "发现", "秘密",
        "获得", "失去", "死亡", "击败", "逃离", "来到", "前往", "地点", "名字",
        "组织", "关系", "盟友", "敌人", "战斗", "检定"
    )
}
