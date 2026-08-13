package xyz.sakulik.d20.app.data.repository

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.sakulik.d20.app.data.model.AiResponseSchema
import xyz.sakulik.d20.app.data.model.AnthropicOutputFormat
import xyz.sakulik.d20.app.data.model.AnthropicRequest
import xyz.sakulik.d20.app.data.model.ChatMessage
import xyz.sakulik.d20.app.data.model.ResponsesRequest
import xyz.sakulik.d20.app.data.model.ResponsesReasoningConfig

class AnthropicMessagesAdapterTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    @Test
    fun requestMovesSystemMessagesAndAddsNativeJsonSchema() {
        val request = AnthropicMessagesAdapter.createRequestBody(
            model = "claude-sonnet",
            messages = listOf(
                ChatMessage("system", "规则一"),
                ChatMessage("user", "行动"),
                ChatMessage("system", "规则二"),
                ChatMessage("assistant", "回应")
            ),
            stream = true,
            outputFormat = AnthropicOutputFormat(
                type = "json_schema",
                schema = AiResponseSchema.value
            ),
            effort = "medium"
        )

        val encoded = json.encodeToString(AnthropicRequest.serializer(), request)
        val root = json.parseToJsonElement(encoded).jsonObject

        assertEquals("规则一\n\n规则二", root.getValue("system").jsonPrimitive.content)
        assertEquals(
            listOf("user", "assistant"),
            root.getValue("messages").jsonArray.map {
                it.jsonObject.getValue("role").jsonPrimitive.content
            }
        )
        val format = root.getValue("output_config").jsonObject
            .getValue("format").jsonObject
        assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
        assertEquals(AiResponseSchema.value, format.getValue("schema"))
        assertEquals(
            "medium",
            root.getValue("output_config").jsonObject.getValue("effort").jsonPrimitive.content
        )
        assertEquals(4096, root.getValue("max_tokens").jsonPrimitive.content.toInt())
        assertTrue(root.getValue("stream").jsonPrimitive.content.toBoolean())
        assertFalse(root.containsKey("response_format"))
    }

    @Test
    fun rawRequestDoesNotClaimStructuredOutput() {
        val request = AnthropicMessagesAdapter.createRequestBody(
            model = "claude-sonnet",
            messages = listOf(ChatMessage("user", "自由文本")),
            stream = true,
            outputFormat = null
        )

        val root = json.parseToJsonElement(
            json.encodeToString(AnthropicRequest.serializer(), request)
        ).jsonObject

        assertFalse(root.containsKey("output_config"))
        assertTrue(root.getValue("stream").jsonPrimitive.content.toBoolean())
        assertNull(request.system)
    }

    @Test
    fun streamParsesTextAndPartialJsonDeltas() {
        val text = AnthropicMessagesAdapter.parseStreamData(
            """{"type":"content_block_delta","delta":{"type":"text_delta","text":"你好"}}""",
            json
        )
        val partialJson = AnthropicMessagesAdapter.parseStreamData(
            """{"type":"content_block_delta","delta":{"type":"input_json_delta","partial_json":"{\"narrative\":"}}""",
            json
        )

        assertEquals("你好", text.delta)
        assertEquals("{\"narrative\":", partialJson.delta)
        assertFalse(text.completed)
        assertNull(partialJson.failure)
    }

    @Test
    fun streamRecognizesCompletionAndErrorEvents() {
        val completed = AnthropicMessagesAdapter.parseStreamData(
            """{"type":"message_stop"}""",
            json
        )
        val failed = AnthropicMessagesAdapter.parseStreamData(
            """{"type":"error","error":{"type":"overloaded_error","message":"busy"}}""",
            json
        )

        assertTrue(completed.completed)
        assertNotNull(failed.failure)
        assertTrue(failed.failure!!.contains("overloaded_error"))
        assertTrue(failed.failure!!.contains("busy"))
    }

    @Test
    fun responsesRequestAlwaysSerializesStreamingChoice() {
        val request = ResponsesRequest(
            model = "gpt-5.5",
            input = listOf(ChatMessage("user", "继续")),
            stream = true,
            reasoning = ResponsesReasoningConfig("high")
        )

        val root = json.parseToJsonElement(
            json.encodeToString(ResponsesRequest.serializer(), request)
        ).jsonObject

        assertTrue(root.getValue("stream").jsonPrimitive.content.toBoolean())
        assertEquals(
            "high",
            root.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content
        )
    }
}
