package xyz.sakulik.d20.app.util

import xyz.sakulik.d20.app.data.model.AIResponse
import kotlinx.serialization.json.Json

/**
 * 严格解析模型返回的完整 JSON。这里不得修补截断内容或把普通文本视为成功，
 * 因为解析结果会驱动本地游戏状态。
 */
object LlmJsonBuffer {

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = true
    }

    /**
     * 解析 LLM 返回的完整 JSON 文本。
     * @param rawText 原始流文本
     */
    fun parseAndRepair(rawText: String): Either<Throwable, AIResponse> {
        val normalized = stripSingleCodeFence(rawText)
        if (normalized.isBlank()) {
            return Either.Left(IllegalArgumentException("LLM 输出为空"))
        }
        if (!normalized.startsWith('{') || !normalized.endsWith('}')) {
            return Either.Left(IllegalArgumentException("模型必须只返回一个完整 JSON 对象"))
        }
        return try {
            Either.Right(json.decodeFromString<AIResponse>(normalized))
        } catch (error: Exception) {
            Either.Left(error)
        }
    }

    /**
     * 仅供诊断使用：只有整个文本本身是一个对象时才返回。
     */
    fun extractJson(text: String): String? {
        val normalized = stripSingleCodeFence(text)
        return normalized.takeIf { it.startsWith('{') && it.endsWith('}') }
    }

    private fun stripSingleCodeFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```") || !trimmed.endsWith("```")) return trimmed
        val firstLineEnd = trimmed.indexOf('\n')
        if (firstLineEnd < 0) return trimmed
        val language = trimmed.substring(3, firstLineEnd).trim()
        if (language.isNotEmpty() && !language.equals("json", ignoreCase = true)) return trimmed
        return trimmed.substring(firstLineEnd + 1, trimmed.length - 3).trim()
    }
}
