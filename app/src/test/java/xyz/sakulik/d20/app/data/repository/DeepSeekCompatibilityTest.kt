package xyz.sakulik.d20.app.data.repository

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.sakulik.d20.app.data.model.CharacterGenState
import xyz.sakulik.d20.app.data.model.ChatMessage
import xyz.sakulik.d20.app.data.model.StreamState
import xyz.sakulik.d20.app.data.security.ApiProtocol
import xyz.sakulik.d20.app.data.security.LlmKeyManager

class DeepSeekCompatibilityTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun deepSeekJsonModeRequestAndStreamCompleteOneTurn() = runBlocking {
        var capturedUrl = ""
        var capturedAuthorization = ""
        var capturedBody = ""
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                capturedUrl = request.url.toString()
                capturedAuthorization = request.header("Authorization").orEmpty()
                capturedBody = Buffer().also { request.body?.writeTo(it) }.readUtf8()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(DEEPSEEK_SSE.toResponseBody("text/event-stream".toMediaType()))
                    .build()
            })
            .build()
        val repository = LlmRepositoryImpl(DeepSeekKeyManager(), client)

        val states = withTimeout(2_000) {
            repository.chatStream(
                baseUrl = "https://api.deepseek.com/v1",
                messages = listOf(
                    ChatMessage("system", "必须返回 JSON 对象"),
                    ChatMessage("user", "继续剧情"),
                ),
            ).toList()
        }

        assertEquals("https://api.deepseek.com/v1/chat/completions", capturedUrl)
        assertEquals("Bearer deepseek-test-key", capturedAuthorization)
        val request = json.parseToJsonElement(capturedBody).jsonObject
        assertEquals("deepseek-chat", request.getValue("model").jsonPrimitive.content)
        assertTrue(request.getValue("stream").jsonPrimitive.content.toBoolean())
        assertEquals(
            "json_object",
            request.getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals(4096, request.getValue("max_tokens").jsonPrimitive.content.toInt())
        assertFalse(request.containsKey("max_completion_tokens"))
        assertTrue(
            request.getValue("messages").jsonArray.any { message ->
                message.jsonObject.getValue("content").jsonPrimitive.content
                    .contains("json", ignoreCase = true)
            },
        )

        assertEquals(2, states.size)
        assertEquals("雨声逼近。", (states[0] as StreamState.TextChunk).delta)
        val completed = states[1] as StreamState.Completed
        assertEquals("雨声逼近。", completed.response.narrative)
        assertTrue(completed.response.gameEvents.isEmpty())
    }

    @Test
    fun characterGenerationRetriesTruncatedReasoningWithCompactEquipmentPrompt() = runBlocking {
        val capturedBodies = mutableListOf<String>()
        var responseIndex = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                capturedBodies += Buffer().also { request.body?.writeTo(it) }.readUtf8()
                val body = if (responseIndex++ == 0) {
                    TRUNCATED_CHARACTER_RESPONSE
                } else {
                    COMPLETE_CHARACTER_RESPONSE
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
        val repository = LlmRepositoryImpl(DeepSeekKeyManager(), client)

        val states = withTimeout(2_000) {
            repository.generateCharacter(
                baseUrl = "https://api.deepseek.com/v1",
                description = "犬儒主义的精灵吟游诗人",
                rulesetId = "dnd_5e",
                promptInjection = "stats 只可使用 race、class、subclass 和 background。",
            ).toList()
        }

        assertEquals(2, capturedBodies.size)
        capturedBodies.forEach { body ->
            val request = json.parseToJsonElement(body).jsonObject
            assertEquals(8192, request.getValue("max_tokens").jsonPrimitive.content.toInt())
            val prompt = request.getValue("messages").jsonArray.single()
                .jsonObject.getValue("content").jsonPrimitive.content
            assertTrue(prompt.contains("3–5 件核心初始"))
            assertTrue(prompt.contains("规则包明确支持的法术"))
        }
        assertTrue(
            capturedBodies[1].contains("上一条响应被截断或格式无效")
        )
        assertEquals(2, states.size)
        assertTrue(states.first() is CharacterGenState.Loading)
        val success = states.last() as CharacterGenState.Success
        assertEquals("凯伦", success.data.name)
        assertEquals(1, success.data.items.size)
        assertEquals("细剑", success.data.items.single().name)
    }

    private class DeepSeekKeyManager : LlmKeyManager {
        override fun getKey(): String = "deepseek-test-key"
        override fun hasKey(): Boolean = true
        override fun getBaseUrl(): String = "https://api.deepseek.com"
        override fun getModel(): String = "deepseek-chat"
        override fun getThemeStyle(): String = "AUTO"
        override fun getApiProtocol(): String = ApiProtocol.DEFAULT.name
        override fun getMaxHistoryTurns(): Int = 8
        override fun saveKey(key: String) = Unit
        override fun clearKey() = Unit
        override fun saveBaseUrl(url: String) = Unit
        override fun saveModel(model: String) = Unit
        override fun saveThemeStyle(style: String) = Unit
        override fun saveApiProtocol(protocol: String) = Unit
        override fun saveMaxHistoryTurns(turns: Int) = Unit
    }

    private companion object {
        val DEEPSEEK_SSE = """
            data: {"choices":[{"delta":{"content":"{\"narrative\":\"雨声逼近。\",\"game_events\":[]}"},"finish_reason":null}]}

            data: {"choices":[{"delta":{"content":""},"finish_reason":"stop"}]}

            data: [DONE]

        """.trimIndent()

        val TRUNCATED_CHARACTER_RESPONSE = """
            {"choices":[{"message":{"role":"assistant","content":"","reasoning_content":"很长的分析"},"finish_reason":"length"}]}
        """.trimIndent()

        val COMPLETE_CHARACTER_RESPONSE = """
            {"choices":[{"message":{"role":"assistant","content":"{\"name\":\"凯伦\",\"stats\":{\"race\":\"精灵\",\"class\":\"吟游诗人\"},\"bio\":\"讥讽虚伪的流浪诗人。\",\"items\":[{\"name\":\"细剑\",\"description\":\"轻巧的穿刺武器\",\"category\":\"武器\",\"modifiers\":{\"damage_formula\":\"1d8\",\"damage_type\":\"piercing\",\"attack_ability\":\"FINESSE\"}}]}","reasoning_content":"","finish_reason":"stop"}]}
        """.trimIndent()
    }
}
