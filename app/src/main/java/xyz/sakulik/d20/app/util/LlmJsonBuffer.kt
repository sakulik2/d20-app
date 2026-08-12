package xyz.sakulik.d20.app.util

import xyz.sakulik.d20.app.data.model.AIResponse
import kotlinx.serialization.json.Json
import java.util.Stack

/**
 * 核心任务：实现健壮的大模型 JSON 提取与修复逻辑
 */
object LlmJsonBuffer {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * 解析并修复 LLM 返回的 JSON 文本
     * @param rawText 原始流文本
     */
    fun parseAndRepair(rawText: String): Either<Throwable, AIResponse> {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) {
            return Either.Left(IllegalArgumentException("LLM 输出为空"))
        }

        // 1. 尝试 JSON 提取与括号栈修复
        val extracted = extractJson(trimmed)
        if (extracted != null) {
            try {
                val sanitized = sanitize(extracted)
                val fixed = repairJson(sanitized)
                val result = json.decodeFromString<AIResponse>(fixed)
                return Either.Right(result)
            } catch (e: Exception) {
                return Either.Left(e)
            }
        }

        // 2. 兜底容错：当 LLM 未输出 JSON 格式而直接输出自然语言剧情旁白时，自动无感包裹为 AIResponse
        val cleanProse = sanitize(trimmed)
        if (cleanProse.isNotBlank()) {
            return Either.Right(
                AIResponse(
                    narrative = cleanProse,
                    gameEvents = emptyList()
                )
            )
        }

        return Either.Left(IllegalArgumentException("未能识别到有效内容"))
    }

    /**
     * 1. 边界提取：找到第一对匹配或最外层的 {..}
     */
    fun extractJson(text: String): String? {
        val startIndex = text.indexOf('{')
        val endIndex = text.lastIndexOf('}')
        if (startIndex == -1) return null
        
        return if (endIndex > startIndex) {
            text.substring(startIndex, endIndex + 1)
        } else {
            // 如果没有结束括号，提取从 { 开始到结尾的所有内容，交给 repair 处理
            text.substring(startIndex)
        }
    }

    /**
     * 2. 转义字符清理：清洗 AI 可能错误的二次转义
     */
    fun sanitize(text: String): String {
        return text
            .replace("```json", "")
            .replace("```", "")
            .replace("\\'", "'")
            .trim()
    }

    /**
     * 3. 括号补全 (Stack-based Fixing)
     * 核心逻辑：遍历字符串，记录未闭合的引号、大括号、中括号，并在结尾补齐
     */
    fun repairJson(text: String): String {
        val stack = Stack<Char>()
        var inQuote = false
        var isEscaped = false

        for (char in text) {
            if (isEscaped) {
                isEscaped = false
                continue
            }
            when (char) {
                '\\' -> isEscaped = true
                '"' -> inQuote = !inQuote
                '{', '[' -> if (!inQuote) stack.push(char)
                '}' -> if (!inQuote && stack.isNotEmpty() && stack.peek() == '{') stack.pop()
                ']' -> if (!inQuote && stack.isNotEmpty() && stack.peek() == '[') stack.pop()
            }
        }

        val repaired = StringBuilder(text)
        
        // 闭合引号
        if (inQuote) repaired.append('"')

        // 逆向闭合括号
        while (stack.isNotEmpty()) {
            val open = stack.pop()
            if (open == '{') repaired.append('}')
            if (open == '[') repaired.append(']')
        }

        return repaired.toString()
    }
}
