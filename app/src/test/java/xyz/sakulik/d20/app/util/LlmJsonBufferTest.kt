package xyz.sakulik.d20.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmJsonBufferTest {

    @Test
    fun testBoundaryExtraction() {
        val raw = "好的，这是你要的内容: {\"narrative\": \"你好\"} 后面还有废话"
        val extracted = LlmJsonBuffer.extractJson(raw)
        assertEquals("{\"narrative\": \"你好\"}", extracted)
    }

    @Test
    fun testRepairUnclosedBraces() {
        val incomplete = "{\"narrative\": \"正在生成中..."
        val repaired = LlmJsonBuffer.repairJson(incomplete)
        // 期望补齐引号和右大括号
        assertEquals("{\"narrative\": \"正在生成中...\"}", repaired)
    }

    @Test
    fun testParseEscapedChars() {
        val dirty = "{\"narrative\": \"AI 错误转义了\\'单引号\\'\\n还有换行\"}"
        val parsed = LlmJsonBuffer.parseAndRepair(dirty).getRightOrNull()
        assertEquals("AI 错误转义了'单引号'\n还有换行", parsed?.narrative)
    }

    @Test
    fun testComplexRepair() {
        val text = "{\"game_events\": [{\"expression\": \"1d20\""
        val repaired = LlmJsonBuffer.repairJson(text)
        // 应该补全: } ] } (注意对象和列表的层级)
        // 实际遍历: { [ { "
        // 补全顺序: " } ] }
        assertEquals("{\"game_events\": [{\"expression\": \"1d20\"}]}", repaired)
    }
}
