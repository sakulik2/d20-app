package xyz.sakulik.d20.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmJsonBufferTest {

    @Test
    fun rejectsProseAroundJson() {
        val raw = "好的，这是你要的内容: {\"narrative\": \"你好\"} 后面还有废话"
        assertTrue(LlmJsonBuffer.parseAndRepair(raw).isLeft())
    }

    @Test
    fun rejectsTruncatedJson() {
        val incomplete = "{\"narrative\":\"正在生成中...\",\"game_events\":["
        assertTrue(LlmJsonBuffer.parseAndRepair(incomplete).isLeft())
    }

    @Test
    fun acceptsCompleteJsonCodeFence() {
        val fenced = """
            ```json
            {"narrative":"你好\n世界","game_events":[]}
            ```
        """.trimIndent()
        val parsed = LlmJsonBuffer.parseAndRepair(fenced).getRightOrNull()
        assertEquals("你好\n世界", parsed?.narrative)
    }

    @Test
    fun rejectsUnknownTopLevelField() {
        val raw = """{"narrative":"你好","game_events":[],"extra":true}"""
        assertTrue(LlmJsonBuffer.parseAndRepair(raw).isLeft())
    }

    @Test
    fun rejectsMissingGameEventsField() {
        val raw = """{"narrative":"你好"}"""
        assertTrue(LlmJsonBuffer.parseAndRepair(raw).isLeft())
    }

    @Test
    fun rejectsPlainNarrative() {
        assertTrue(LlmJsonBuffer.parseAndRepair("门后传来脚步声。").isLeft())
    }
}
