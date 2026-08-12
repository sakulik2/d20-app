package xyz.sakulik.d20.app.data.repository

import android.util.Log
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
import xyz.sakulik.d20.app.util.unescapeJson
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
     * 辅助逻辑：寻找 JSON 字符串的闭合引号，并跳过转义的引号
     */
    private fun findJsonStringEnd(text: String): Int {
        var isEscaped = false
        for (i in text.indices) {
            val c = text[i]
            if (isEscaped) {
                isEscaped = false
                continue
            }
            if (c == '\\') {
                isEscaped = true
            } else if (c == '"') {
                return i
            }
        }
        return -1
    }

    /**
     * 辅助逻辑：处理 JSON 字符串中的转义字符 (\n, \", \\)
     */
    private fun unescapeJson(text: String): String {
        if (!text.contains('\\')) return text
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    else -> sb.append(text[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
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
        stream: Boolean
    ): Request {
        return when (protocol) {
            ApiProtocol.ANTHROPIC -> buildAnthropicRequest(
                baseUrl, apiKey, model, messages, stream
            )
            ApiProtocol.RESPONSES -> buildResponsesRequest(
                baseUrl, apiKey, model, messages, stream
            )
            else -> buildChatCompletionsRequest(
                baseUrl, apiKey, model, messages, stream
            )
        }
    }

    override fun chatStream(baseUrl: String, messages: List<ChatMessage>): Flow<StreamState> = callbackFlow {
        val apiKey = keyManager.getKey()
        if (apiKey.isNullOrBlank()) {
            trySend(StreamState.Error(IllegalStateException("API Key 缺失，请先在设置中配置")))
            close()
            return@callbackFlow
        }

        val model = keyManager.getModel()
        val primaryProtocol = determineProtocol(baseUrl)

        Log.d("LlmRepo", "Primary protocol selected: $primaryProtocol")

        // 构造并发发请求逻辑，优先执行 primaryProtocol，如果遭遇 404/400/405 等错误，降级回退至 CHAT_COMPLETIONS
        var activeCall: Call? = null

        fun executeStream(protocol: ApiProtocol) {
            val request = try {
                buildProtocolRequest(baseUrl, apiKey, model, messages, protocol, stream = true)
            } catch (exception: Exception) {
                trySend(StreamState.Error(exception))
                close()
                return
            }

            val fullContentBuffer = StringBuilder()
            var narrativeFieldFound = false
            var lastExtractedIndex = 0

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
                        if (canFallback(protocol, response.code)) {
                            Log.i("LlmRepo", "Fallback triggered -> Retrying with /v1/chat/completions")
                            executeStream(ApiProtocol.CHAT_COMPLETIONS)
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
                            val deltaText = parseChunkDelta(protocol, line)
                            if (deltaText.isNotEmpty()) {
                                fullContentBuffer.append(deltaText)

                                val currentTotal = fullContentBuffer.toString()
                                
                                if (!narrativeFieldFound) {
                                    val marker = "\"narrative\":"
                                    val markerIndex = currentTotal.indexOf(marker, lastExtractedIndex)
                                    if (markerIndex != -1) {
                                        val firstQuote = currentTotal.indexOf("\"", markerIndex + marker.length)
                                        if (firstQuote != -1) {
                                            narrativeFieldFound = true
                                            lastExtractedIndex = firstQuote + 1
                                        }
                                    }
                                }

                                if (narrativeFieldFound) {
                                    val potentialNarrative = currentTotal.substring(lastExtractedIndex)
                                    val endOfField = this@LlmRepositoryImpl.findJsonStringEnd(potentialNarrative)
                                    if (endOfField != -1) {
                                        val finalChunk = potentialNarrative.substring(0, endOfField)
                                        if (finalChunk.isNotEmpty()) {
                                            trySend(StreamState.TextChunk(unescapeJson(finalChunk)))
                                        }
                                        narrativeFieldFound = false 
                                        lastExtractedIndex = currentTotal.length 
                                    } else {
                                        if (potentialNarrative.isNotEmpty()) {
                                            val toEmit = if (potentialNarrative.endsWith("\\")) {
                                                potentialNarrative.dropLast(1)
                                            } else {
                                                potentialNarrative
                                            }
                                            if (toEmit.isNotEmpty()) {
                                                trySend(StreamState.TextChunk(unescapeJson(toEmit)))
                                            }
                                            lastExtractedIndex += toEmit.length
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val finalJson = fullContentBuffer.toString()
                    val result = LlmJsonBuffer.parseAndRepair(finalJson)
                    
                    when (result) {
                        is Either.Right -> {
                            trySend(StreamState.EventTrigger(result.value.gameEvents))
                            close()
                        }
                        is Either.Left -> {
                            Log.e("LlmRepo", "Final parse failed after repair: $finalJson", result.value)
                            trySend(StreamState.Error(result.value))
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
        stream: Boolean
    ): Request {
        val requestBody = ChatRequest(
            model = model,
            messages = messages,
            stream = stream,
            responseFormat = null
        )
        return Request.Builder()
            .url(buildEndpoint(baseUrl, "v1/chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(ChatRequest.serializer(), requestBody).toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun buildAnthropicRequest(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        stream: Boolean
    ): Request {
        val systemPrompt = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        val userAssistantMessages = messages.filter { it.role != "system" }
        
        val requestBody = AnthropicRequest(
            model = model,
            messages = userAssistantMessages,
            system = systemPrompt.ifBlank { null },
            maxTokens = 4096,
            stream = stream
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
        stream: Boolean
    ): Request {
        val requestBody = ResponsesRequest(
            model = model,
            input = messages,
            stream = stream
        )
        return Request.Builder()
            .url(buildEndpoint(baseUrl, "v1/responses"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(ResponsesRequest.serializer(), requestBody).toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun parseChunkDelta(protocol: ApiProtocol, line: String): String {
        if (!line.startsWith("data:")) return ""
        val data = line.removePrefix("data:").trim()
        if (data == "[DONE]" || data == "[FINISHED]") return ""

        return try {
            when (protocol) {
                ApiProtocol.ANTHROPIC -> {
                    val chunk = json.decodeFromString<AnthropicChunk>(data)
                    chunk.delta?.text ?: ""
                }
                ApiProtocol.RESPONSES -> {
                    val chunk = json.decodeFromString<ResponsesChunk>(data)
                    chunk.delta ?: chunk.content ?: chunk.text ?: ""
                }
                else -> {
                    val chunk = json.decodeFromString<ChatChunk>(data)
                    chunk.choices.firstOrNull()?.delta?.content ?: ""
                }
            }
        } catch (e: Exception) {
            // 兼容性降级尝试
            try {
                val chunk = json.decodeFromString<ChatChunk>(data)
                chunk.choices.firstOrNull()?.delta?.content ?: ""
            } catch (ex: Exception) {
                ""
            }
        }
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
        val firstBrace = text.indexOf('{')
        val lastBrace = text.lastIndexOf('}')
        require(firstBrace >= 0 && lastBrace > firstBrace) {
            "模型响应中没有找到有效的 JSON 对象"
        }
        return text.substring(firstBrace, lastBrace + 1)
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
            
            要求返回 JSON 格式，包含以下字段：
            - name: 角色姓名
            - stats: 叙事属性 Map (包含 race, subrace, class, subclass, background, occupation 等文字描述字段。请勿包含力量、敏捷、HP 等任何数值属性，这些将由本地规则引擎生成)
            - bio: 一段简短的角色背景描述 (Markdown 格式)
            - items: 初始装备列表，每个物品为一个 JSON 对象，包含 [name, description, category, modifiers]。
              D&D 武器 modifiers 必须使用字符串值：attack_ability(STR/DEX/FINESSE)、proficient、attack_bonus、damage_formula、damage_ability、damage_bonus、damage_type；可选 targeting(SINGLE/MULTIPLE/ALL_ENEMIES) 与 max_targets。
              D&D 法术 category 使用“法术”，modifiers 必须包含 resolution_type(ATTACK/SAVING_THROW/AUTOMATIC/HEALING)、slot_level(戏法为0)，仪式法术另设 ritual=true；并按类型提供 ability、attack_bonus、save_ability、save_dc、damage_formula、damage_type、half_on_save 或 healing_formula；多目标法术使用 targeting=MULTIPLE/ALL_ENEMIES，可用 max_targets 限制数量。
              不要只把伤害骰写进 description；规则字段缺失的武器和法术无法由本地裁决器使用。
            
            请直接返回 JSON 对象，不要包含多余的文字说明。"""
        val model = keyManager.getModel()
        val messages = listOf(ChatMessage("user", prompt))
        val primaryProtocol = determineProtocol(baseUrl)
        var activeCall: Call? = null

        fun execute(protocol: ApiProtocol) {
            val request = try {
                buildProtocolRequest(baseUrl, apiKey, model, messages, protocol, stream = false)
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
}
