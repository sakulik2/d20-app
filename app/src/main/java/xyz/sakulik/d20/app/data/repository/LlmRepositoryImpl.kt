package xyz.sakulik.d20.app.data.repository

import android.util.Log
import xyz.sakulik.d20.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.sakulik.d20.app.data.model.*
import xyz.sakulik.d20.app.data.security.ApiProtocol
import xyz.sakulik.d20.app.data.security.LlmKeyManager
import xyz.sakulik.d20.app.util.Either
import xyz.sakulik.d20.app.util.LlmJsonBuffer
import java.io.IOException

/**
 * LlmRepository 的具体实现类 (BYOK 模式)
 * 实现逻辑：通过 OkHttp SSE 实时解析 narrative 片段
 */
class LlmRepositoryImpl(
    private val keyManager: LlmKeyManager,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
) : LlmRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = false
    }

    /**
     * 规范化构建 API 端点，防止 /v1 重复
     */
    private fun buildEndpoint(baseUrl: String, path: String): String {
        val base = baseUrl.trim().removeSuffix("/")
        val normalizedPath = path.trim().trimStart('/')
        val knownEndpoints = listOf(
            "v1/chat/completions",
            "v1/messages",
            "v1/responses"
        )

        if (base.endsWith("/$normalizedPath")) {
            return base
        }

        val endpointSuffix = knownEndpoints.firstOrNull { base.endsWith("/$it") }
        val normalizedBase = if (endpointSuffix != null) {
            base.removeSuffix(endpointSuffix).removeSuffix("/")
        } else {
            base
        }

        return if (normalizedBase.endsWith("/v1") && normalizedPath.startsWith("v1/")) {
            "$normalizedBase/${normalizedPath.removePrefix("v1/")}"
        } else {
            "$normalizedBase/$normalizedPath"
        }
    }

    private fun configuredProtocol(): ApiProtocol {
        return runCatching { ApiProtocol.valueOf(keyManager.getApiProtocol()) }
            .getOrDefault(ApiProtocol.DEFAULT)
    }

    private fun determineProtocol(baseUrl: String): ApiProtocol {
        val configured = try {
            ApiProtocol.valueOf(keyManager.getApiProtocol())
        } catch (e: Exception) {
            ApiProtocol.DEFAULT
        }

        if (configured != ApiProtocol.DEFAULT) {
            return configured
        }

        // 自动模式只按服务端地址识别，避免兼容端点因模型名称被误判。
        val lowerUrl = baseUrl.lowercase()
        return when {
            lowerUrl.contains("anthropic") -> ApiProtocol.ANTHROPIC
            lowerUrl.contains("/responses") || lowerUrl.endsWith("responses") -> ApiProtocol.RESPONSES
            else -> ApiProtocol.CHAT_COMPLETIONS
        }
    }

    private fun canFallback(protocol: ApiProtocol, responseCode: Int): Boolean {
        return configuredProtocol() == ApiProtocol.DEFAULT &&
            protocol != ApiProtocol.CHAT_COMPLETIONS &&
            responseCode in setOf(400, 404, 405, 415, 422)
    }

    private fun buildProtocolRequest(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        protocol: ApiProtocol,
        stream: Boolean,
        structuredOutputMode: StructuredOutputMode = StructuredOutputMode.NONE,
        outputSpec: StructuredOutputSpec = TURN_OUTPUT_SPEC
    ): Request {
        if (!BuildConfig.DEBUG) {
            val uri = runCatching { java.net.URI(baseUrl.trim()) }.getOrNull()
            require(uri?.scheme.equals("https", ignoreCase = true) && !uri?.host.isNullOrBlank()) {
                "Release 版本的 API Base URL 必须使用 HTTPS"
            }
        }
        return when (protocol) {
            ApiProtocol.ANTHROPIC -> buildAnthropicRequest(
                baseUrl, apiKey, model, messages, stream, structuredOutputMode, outputSpec
            )
            ApiProtocol.RESPONSES -> buildResponsesRequest(
                baseUrl, apiKey, model, messages, stream, structuredOutputMode, outputSpec
            )
            else -> buildChatCompletionsRequest(
                baseUrl, apiKey, model, messages, stream, structuredOutputMode, outputSpec
            )
        }
    }

    private fun preferredStructuredOutputMode(
        baseUrl: String,
        protocol: ApiProtocol,
        model: String
    ): StructuredOutputMode = when {
        protocol == ApiProtocol.ANTHROPIC -> StructuredOutputMode.JSON_SCHEMA
        protocol == ApiProtocol.CHAT_COMPLETIONS && isDeepSeek(baseUrl, model) ->
            StructuredOutputMode.JSON_OBJECT
        else -> StructuredOutputMode.JSON_SCHEMA
    }

    private fun isDeepSeek(baseUrl: String, model: String): Boolean =
        baseUrl.contains("deepseek", ignoreCase = true) ||
            model.startsWith("deepseek", ignoreCase = true)

    override fun chatStream(baseUrl: String, messages: List<ChatMessage>): Flow<StreamState> = callbackFlow {
        val apiKey = keyManager.getKey()
        if (apiKey.isNullOrBlank()) {
            trySend(StreamState.Error(IllegalStateException("API Key 缺失，请先在设置中配置")))
            close()
            return@callbackFlow
        }

        val model = keyManager.getModel()
        val primaryProtocol = determineProtocol(baseUrl)

        runCatching { Log.d("LlmRepo", "Primary protocol selected: $primaryProtocol") }

        // 构造并发发请求逻辑，优先执行 primaryProtocol，如果遭遇 404/400/405 等错误，降级回退至 CHAT_COMPLETIONS
        var activeCall: Call? = null

        fun executeStream(
            protocol: ApiProtocol,
            structuredOutputMode: StructuredOutputMode = preferredStructuredOutputMode(
                baseUrl,
                protocol,
                model
            )
        ) {
            val request = try {
                buildProtocolRequest(
                    baseUrl,
                    apiKey,
                    model,
                    messages,
                    protocol,
                    stream = true,
                    structuredOutputMode = structuredOutputMode
                )
            } catch (exception: Exception) {
                trySend(StreamState.Error(exception))
                close()
                return
            }

            val fullContentBuffer = StringBuilder()
            var streamCompleted = false
            var streamFailure: String? = null

            val call = client.newCall(request)
            activeCall = call
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!call.isCanceled()) {
                        trySend(StreamState.Error(e))
                        close(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: ""
                        Log.w("LlmRepo", "Protocol $protocol returned HTTP ${response.code}: $errorBody")
                        val fallbackMode = structuredOutputMode.fallbackMode(protocol)
                        if (fallbackMode != null && response.code in STRUCTURED_OUTPUT_FALLBACK_CODES) {
                            Log.i(
                                "LlmRepo",
                                "Structured output $structuredOutputMode unsupported -> retrying with $fallbackMode"
                            )
                            executeStream(protocol, structuredOutputMode = fallbackMode)
                            return
                        } else if (canFallback(protocol, response.code)) {
                            Log.i("LlmRepo", "Fallback triggered -> Retrying with /v1/chat/completions")
                            executeStream(
                                ApiProtocol.CHAT_COMPLETIONS,
                                preferredStructuredOutputMode(
                                    baseUrl,
                                    ApiProtocol.CHAT_COMPLETIONS,
                                    model
                                )
                            )
                            return
                        } else {
                            trySend(StreamState.Error(IOException("API Error ${response.code}: $errorBody")))
                            close()
                            return
                        }
                    }

                    response.body?.source()?.use { source ->
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            val chunk = parseStreamChunk(protocol, line)
                            if (chunk.delta.isNotEmpty()) {
                                fullContentBuffer.append(chunk.delta)
                            }
                            if (chunk.completed) streamCompleted = true
                            if (chunk.failure != null) {
                                streamFailure = chunk.failure
                            }
                        }
                    }

                    if (streamFailure != null) {
                        trySend(StreamState.Error(IOException(streamFailure)))
                        close()
                        return
                    }
                    if (!streamCompleted) {
                        trySend(StreamState.Error(IOException("模型响应流未完整结束，已拒绝执行其中的事件")))
                        close()
                        return
                    }
                    val finalJson = fullContentBuffer.toString()
                    val result = LlmJsonBuffer.parseAndRepair(finalJson)
                    
                    when (result) {
                        is Either.Right -> {
                            trySend(StreamState.TextChunk(result.value.narrative))
                            trySend(StreamState.Completed(result.value))
                            close()
                        }
                        is Either.Left -> {
                            Log.e("LlmRepo", "Final structured response parse failed", result.value)
                            trySend(
                                StreamState.Error(
                                    LlmResponseFormatException(
                                        result.value.message ?: "模型没有返回有效的 JSON 响应",
                                        result.value
                                    )
                                )
                            )
                            close()
                        }
                    }
                }
            })
        }

        executeStream(primaryProtocol)

        awaitClose { activeCall?.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun buildChatCompletionsRequest(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        structuredOutputMode: StructuredOutputMode,
        outputSpec: StructuredOutputSpec
    ): Request {
        val responseFormat = when (structuredOutputMode) {
            StructuredOutputMode.JSON_SCHEMA -> ResponseFormat(
                type = "json_schema",
                jsonSchema = JsonSchemaFormat(
                    name = outputSpec.name,
                    strict = true,
                    schema = outputSpec.schema
                )
            )
            StructuredOutputMode.JSON_OBJECT -> ResponseFormat(type = "json_object")
            StructuredOutputMode.NONE -> null
        }
        val requestBody = ChatRequest(
            model = model,
            messages = messages,
            stream = stream,
            responseFormat = responseFormat,
            maxTokens = if (
                structuredOutputMode != StructuredOutputMode.NONE &&
                !isOpenAiEndpoint(baseUrl)
            ) {
                4096
            } else {
                null
            },
            maxCompletionTokens = if (
                structuredOutputMode != StructuredOutputMode.NONE &&
                isOpenAiEndpoint(baseUrl)
            ) {
                4096
            } else {
                null
            }
        )
        return Request.Builder()
            .url(buildEndpoint(baseUrl, "v1/chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(ChatRequest.serializer(), requestBody).toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun isOpenAiEndpoint(baseUrl: String): Boolean =
        baseUrl.contains("openai.com", ignoreCase = true)

    private fun buildAnthropicRequest(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        structuredOutputMode: StructuredOutputMode,
        outputSpec: StructuredOutputSpec
    ): Request {
        val outputFormat = if (structuredOutputMode == StructuredOutputMode.JSON_SCHEMA) {
            AnthropicOutputFormat(type = "json_schema", schema = outputSpec.schema)
        } else {
            null
        }
        val requestBody = AnthropicMessagesAdapter.createRequestBody(
            model = model,
            messages = messages,
            stream = stream,
            outputFormat = outputFormat
        )
        return Request.Builder()
            .url(buildEndpoint(baseUrl, "v1/messages"))
            .header("x-api-key", apiKey)
            .header("Authorization", "Bearer $apiKey")
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(AnthropicRequest.serializer(), requestBody).toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun buildResponsesRequest(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        structuredOutputMode: StructuredOutputMode,
        outputSpec: StructuredOutputSpec
    ): Request {
        val requestBody = ResponsesRequest(
            model = model,
            input = messages,
            stream = stream,
            text = if (structuredOutputMode == StructuredOutputMode.JSON_SCHEMA) {
                ResponsesTextConfig(
                    format = ResponsesTextFormat(
                        type = "json_schema",
                        name = outputSpec.name,
                        strict = true,
                        schema = outputSpec.schema
                    )
                )
            } else {
                null
            },
            maxOutputTokens = if (structuredOutputMode == StructuredOutputMode.NONE) null else 4096
        )
        return Request.Builder()
            .url(buildEndpoint(baseUrl, "v1/responses"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(ResponsesRequest.serializer(), requestBody).toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun parseStreamChunk(protocol: ApiProtocol, line: String): ParsedStreamChunk {
        if (!line.startsWith("data:")) return ParsedStreamChunk()
        val data = line.removePrefix("data:").trim()
        if (data == "[DONE]" || data == "[FINISHED]") {
            return ParsedStreamChunk(completed = true)
        }

        return try {
            when (protocol) {
                ApiProtocol.ANTHROPIC -> {
                    AnthropicMessagesAdapter.parseStreamData(data, json)
                }
                ApiProtocol.RESPONSES -> {
                    val chunk = json.decodeFromString<ResponsesChunk>(data)
                    ParsedStreamChunk(
                        delta = chunk.delta ?: chunk.content ?: chunk.text.orEmpty(),
                        completed = chunk.type == "response.completed",
                        failure = chunk.type
                            ?.takeIf { it in RESPONSES_FAILURE_EVENTS }
                            ?.let { "Responses API 响应未正常结束：$it" }
                    )
                }
                else -> {
                    val chunk = json.decodeFromString<ChatChunk>(data)
                    val choice = chunk.choices.firstOrNull()
                    val finishReason = choice?.finishReason
                    ParsedStreamChunk(
                        delta = choice?.delta?.content.orEmpty(),
                        completed = finishReason != null,
                        failure = finishReason
                            ?.takeUnless { it in CHAT_SUCCESS_FINISH_REASONS }
                            ?.let { "Chat Completions 响应未正常结束：$it" }
                    )
                }
            }
        } catch (e: Exception) {
            ParsedStreamChunk(failure = "无法解析模型流事件：${e.message ?: "未知格式"}")
        }
    }

    private fun parseChunkDelta(protocol: ApiProtocol, line: String): String {
        return parseStreamChunk(protocol, line).delta
    }

    private fun extractResponseText(protocol: ApiProtocol, body: String): String {
        val root = json.parseToJsonElement(body).jsonObject

        fun chatCompletionsText(): String? {
            return root["choices"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("message")
                ?.jsonObject
                ?.get("content")
                ?.jsonPrimitive
                ?.contentOrNull
        }

        fun anthropicText(): String? {
            return root["content"]
                ?.jsonArray
                ?.mapNotNull { element ->
                    element.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                }
                ?.joinToString("")
                ?.takeIf { it.isNotBlank() }
        }

        fun responsesText(): String? {
            root["output_text"]?.jsonPrimitive?.contentOrNull?.let { text ->
                if (text.isNotBlank()) return text
            }

            return root["output"]
                ?.jsonArray
                ?.flatMap { output ->
                    output.jsonObject["content"]?.jsonArray?.mapNotNull { content ->
                        content.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                    }.orEmpty()
                }
                ?.joinToString("")
                ?.takeIf { it.isNotBlank() }
        }

        val extractors = when (protocol) {
            ApiProtocol.ANTHROPIC -> listOf(::anthropicText, ::chatCompletionsText, ::responsesText)
            ApiProtocol.RESPONSES -> listOf(::responsesText, ::chatCompletionsText, ::anthropicText)
            else -> listOf(::chatCompletionsText, ::responsesText, ::anthropicText)
        }

        return extractors.firstNotNullOfOrNull { extractor ->
            runCatching { extractor() }.getOrNull()
        }.orEmpty()
    }

    private fun extractJsonObject(text: String): String {
        return LlmJsonBuffer.extractJson(text)
            ?: throw LlmResponseFormatException("模型必须只返回一个完整 JSON 对象")
    }

    override fun chatRaw(baseUrl: String, messages: List<ChatMessage>): Flow<String> = callbackFlow {
        val apiKey = keyManager.getKey()
        if (apiKey.isNullOrBlank()) {
            trySend("错误: API Key 缺失")
            close()
            return@callbackFlow
        }

        val model = keyManager.getModel()
        val protocol = determineProtocol(baseUrl)
        val request = try {
            buildProtocolRequest(baseUrl, apiKey, model, messages, protocol, stream = true)
        } catch (exception: Exception) {
            trySend("错误: ${exception.message ?: "API 地址或请求配置无效"}")
            close()
            return@callbackFlow
        }

        Log.d("LlmRepo", "chatRaw protocol: $protocol, URL: ${request.url}")

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                trySend("错误: ${e.message}")
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    val detail = errorBody.take(500)
                        .takeIf { it.isNotBlank() }
                        ?.let { ": $it" }
                        .orEmpty()
                    trySend("错误: HTTP ${response.code}$detail")
                    close()
                    return
                }

                var receivedAnyData = false
                response.body?.source()?.use { source ->
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        val delta = parseChunkDelta(protocol, line)
                        if (delta.isNotEmpty()) {
                            receivedAnyData = true
                            trySend(delta)
                        }
                    }
                }
                if (!receivedAnyData) {
                    trySend("错误: 未从服务器接收到有效的文字流。请检查模型名称是否正确。")
                }
                close()
            }
        })

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    override fun generateCharacter(baseUrl: String, description: String, rulesetId: String, promptInjection: String?): Flow<xyz.sakulik.d20.app.data.model.CharacterGenState> = callbackFlow {
        val apiKey = keyManager.getKey()
        if (apiKey.isNullOrBlank()) {
            trySend(xyz.sakulik.d20.app.data.model.CharacterGenState.Error(IllegalStateException("API Key 缺失")))
            close()
            return@callbackFlow
        }

        trySend(xyz.sakulik.d20.app.data.model.CharacterGenState.Loading)

        val basePromptInjection = promptInjection ?: "该规则系统的核心属性"

        val prompt = """
            你是一个 TRPG 创卡助手。请根据以下描述，为 [${rulesetId}] 规则系统生成一个角色。
            描述：${description}
            
            规则特定指导：${basePromptInjection}
            
            要求返回 JSON（json）格式，包含以下字段：
            - name: 角色姓名
            - stats: 叙事属性 Map (包含 race, subrace, class, subclass, background, occupation 等文字描述字段。请勿包含力量、敏捷、HP 等任何数值属性，这些将由本地规则引擎生成)
            - bio: 一段简短的角色背景描述 (Markdown 格式)
            - items: 初始装备列表，每个物品为一个 JSON 对象，包含 [name, description, category, modifiers]。
              D&D 武器 modifiers 必须使用字符串值：attack_ability(STR/DEX/FINESSE)、proficient、attack_bonus、damage_formula、damage_ability、damage_bonus、damage_type；可选 targeting(SINGLE/MULTIPLE/ALL_ENEMIES) 与 max_targets。
              D&D 法术 category 使用“法术”，modifiers 必须包含 resolution_type(ATTACK/SAVING_THROW/AUTOMATIC/HEALING)、slot_level(戏法为0)，仪式法术另设 ritual=true；并按类型提供 ability、attack_bonus、save_ability、save_dc、damage_formula、damage_type、half_on_save 或 healing_formula；多目标法术使用 targeting=MULTIPLE/ALL_ENEMIES，可用 max_targets 限制数量。
              不要只把伤害骰写进 description；规则字段缺失的武器和法术无法由本地裁决器使用。

            JSON 示例：{"name":"示例角色","stats":{"race":"人类","class":"调查员"},"bio":"一段简短背景。","items":[]}
            
            请直接返回 JSON 对象，不要包含多余的文字说明。"""
        val model = keyManager.getModel()
        val messages = listOf(ChatMessage("user", prompt))
        val primaryProtocol = determineProtocol(baseUrl)
        var activeCall: Call? = null

        fun execute(
            protocol: ApiProtocol,
            structuredOutputMode: StructuredOutputMode = if (
                protocol == ApiProtocol.ANTHROPIC
            ) StructuredOutputMode.JSON_SCHEMA else if (
                protocol == ApiProtocol.CHAT_COMPLETIONS
            ) StructuredOutputMode.JSON_OBJECT else StructuredOutputMode.NONE,
            formatRetryCount: Int = 0
        ) {
            val requestMessages = if (formatRetryCount == 0) {
                messages
            } else {
                messages + ChatMessage(
                    role = "user",
                    content = "上一条响应为空或不是合法 json。请只重新返回一个完整 JSON 对象，不要解释。"
                )
            }
            val request = try {
                buildProtocolRequest(
                    baseUrl,
                    apiKey,
                    model,
                    requestMessages,
                    protocol,
                    stream = false,
                    structuredOutputMode = structuredOutputMode,
                    outputSpec = CHARACTER_OUTPUT_SPEC
                )
            } catch (exception: Exception) {
                trySend(xyz.sakulik.d20.app.data.model.CharacterGenState.Error(exception))
                close()
                return
            }

            Log.d("LlmRepo", "genChar protocol: $protocol, URL: ${request.url}")
            val call = client.newCall(request)
            activeCall = call
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!call.isCanceled()) {
                        Log.e("LlmRepo", "genChar Failed", e)
                        trySend(xyz.sakulik.d20.app.data.model.CharacterGenState.Error(e))
                        close()
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.body?.string() ?: ""

                        if (!response.isSuccessful) {
                            val fallbackMode = structuredOutputMode.fallbackMode(protocol)
                            if (fallbackMode != null && response.code in STRUCTURED_OUTPUT_FALLBACK_CODES) {
                                execute(protocol, fallbackMode, formatRetryCount)
                                return
                            }
                            if (canFallback(protocol, response.code)) {
                                execute(ApiProtocol.CHAT_COMPLETIONS)
                                return
                            }
                            trySend(xyz.sakulik.d20.app.data.model.CharacterGenState.Error(IOException("API Error ${response.code}: $body")))
                            close()
                            return
                        }

                        val content = extractResponseText(protocol, body)
                        val character = json.decodeFromString<xyz.sakulik.d20.app.data.model.CharacterGenResponse>(
                            extractJsonObject(content)
                        )
                        trySend(xyz.sakulik.d20.app.data.model.CharacterGenState.Success(character))
                        close()
                    } catch (e: Exception) {
                        if (
                            formatRetryCount < 1 &&
                            (e is LlmResponseFormatException ||
                                e is kotlinx.serialization.SerializationException)
                        ) {
                            execute(protocol, structuredOutputMode, formatRetryCount + 1)
                            return
                        }
                        Log.e("LlmRepo", "genChar Parse Error", e)
                        trySend(xyz.sakulik.d20.app.data.model.CharacterGenState.Error(e))
                        close()
                    }
                }
            })
        }

        execute(primaryProtocol)

        awaitClose { activeCall?.cancel() }
    }.flowOn(Dispatchers.IO)

    private enum class StructuredOutputMode {
        JSON_SCHEMA,
        JSON_OBJECT,
        NONE;

        fun fallbackMode(protocol: ApiProtocol): StructuredOutputMode? = when (this) {
            JSON_SCHEMA -> if (protocol == ApiProtocol.CHAT_COMPLETIONS) JSON_OBJECT else NONE
            JSON_OBJECT -> NONE
            NONE -> null
        }
    }

    private data class StructuredOutputSpec(
        val name: String,
        val schema: JsonObject
    )

    private companion object {
        val TURN_OUTPUT_SPEC = StructuredOutputSpec("trpg_turn_response", AiResponseSchema.value)
        val CHARACTER_OUTPUT_SPEC = StructuredOutputSpec(
            "trpg_character_response",
            CharacterGenResponseSchema.value
        )
        val STRUCTURED_OUTPUT_FALLBACK_CODES = setOf(400, 404, 405, 415, 422)
        val CHAT_SUCCESS_FINISH_REASONS = setOf("stop")
        val RESPONSES_FAILURE_EVENTS = setOf("response.incomplete", "response.failed", "error")
    }
}

internal data class ParsedStreamChunk(
    val delta: String = "",
    val completed: Boolean = false,
    val failure: String? = null
)

internal object AnthropicMessagesAdapter {
    fun createRequestBody(
        model: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        outputFormat: AnthropicOutputFormat?
    ): AnthropicRequest {
        val systemPrompt = messages.filter { it.role == "system" }
            .joinToString("\n\n") { it.content }
        val conversation = messages.filter { it.role == "user" || it.role == "assistant" }
        require(conversation.isNotEmpty()) { "Anthropic Messages 请求至少需要一条用户或助手消息" }
        return AnthropicRequest(
            model = model,
            messages = conversation,
            system = systemPrompt.ifBlank { null },
            maxTokens = 4096,
            stream = stream,
            outputConfig = outputFormat?.let(::AnthropicOutputConfig)
        )
    }

    fun parseStreamData(data: String, json: Json): ParsedStreamChunk {
        val chunk = json.decodeFromString<AnthropicChunk>(data)
        val eventFailure = chunk.error?.let { error ->
            "Anthropic ${error.type ?: "error"}：${error.message ?: "未知错误"}"
        }
        val stopFailure = chunk.delta?.stopReason
            ?.takeUnless { it in SUCCESS_STOP_REASONS }
            ?.let { "Anthropic 响应未正常结束：$it" }
        return ParsedStreamChunk(
            delta = chunk.delta?.text ?: chunk.delta?.partialJson.orEmpty(),
            completed = chunk.type == "message_stop",
            failure = eventFailure ?: stopFailure
        )
    }

    private val SUCCESS_STOP_REASONS = setOf("end_turn", "stop_sequence", "tool_use")
}
