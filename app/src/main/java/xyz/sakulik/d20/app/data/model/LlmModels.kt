package xyz.sakulik.d20.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * OpenAI Chat Completion 请求体
 */
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean,
    @SerialName("response_format")
    val responseFormat: ResponseFormat? = null,
    val temperature: Double? = null,
    @SerialName("top_p")
    val topP: Double? = null,
    @SerialName("frequency_penalty")
    val frequencyPenalty: Double? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    @SerialName("max_completion_tokens")
    val maxCompletionTokens: Int? = null
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class ResponseFormat(
    val type: String,
    @SerialName("json_schema")
    val jsonSchema: JsonSchemaFormat? = null
)

@Serializable
data class JsonSchemaFormat(
    val name: String,
    val strict: Boolean,
    val schema: JsonObject
)

/**
 * OpenAI Streaming Chunk 模型
 */
@Serializable
data class ChatChunk(
    val choices: List<ChoiceChunk>
)

@Serializable
data class ChoiceChunk(
    val delta: DeltaChunk,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class DeltaChunk(
    val content: String? = null
)

/**
 * Anthropic Messages API 模型
 */
@Serializable
data class AnthropicRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val system: String? = null,
    @SerialName("max_tokens")
    val maxTokens: Int = 4096,
    val stream: Boolean = true,
    val temperature: Double? = null
)

@Serializable
data class AnthropicChunk(
    val type: String? = null,
    val delta: AnthropicDelta? = null
)

@Serializable
data class AnthropicDelta(
    val type: String? = null,
    val text: String? = null,
    @SerialName("stop_reason")
    val stopReason: String? = null
)

/**
 * OpenAI Responses API 模型 (v1/responses)
 */
@Serializable
data class ResponsesRequest(
    val model: String,
    val input: List<ChatMessage>,
    val stream: Boolean = true,
    val temperature: Double? = null,
    val text: ResponsesTextConfig? = null,
    @SerialName("max_output_tokens")
    val maxOutputTokens: Int? = null
)

@Serializable
data class ResponsesTextConfig(
    val format: ResponsesTextFormat
)

@Serializable
data class ResponsesTextFormat(
    val type: String,
    val name: String,
    val strict: Boolean,
    val schema: JsonObject
)

@Serializable
data class ResponsesChunk(
    val type: String? = null,
    val delta: String? = null,
    val content: String? = null,
    val text: String? = null
)

/**
 * Repository 输出的流式结果封装
 */
sealed class StreamState {
    /** 叙事文本片段 */
    data class TextChunk(val delta: String) : StreamState()
    /** 完整解析并通过结构校验的模型回合 */
    data class Completed(val response: AIResponse) : StreamState()
    /** 错误状态 */
    data class Error(val throwable: Throwable) : StreamState()
}

class LlmResponseFormatException(
    message: String,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)
