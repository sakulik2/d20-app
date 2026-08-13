package xyz.sakulik.d20.app.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningEffortTest {
    @Test
    fun invalidStoredValueFallsBackToAuto() {
        assertEquals(ReasoningEffort.AUTO, ReasoningEffort.fromStored("extreme"))
    }

    @Test
    fun explicitLevelsProvidePortablePromptGuidance() {
        assertTrue(ReasoningEffort.LOW.promptGuidance().contains("快速响应"))
        assertTrue(ReasoningEffort.HIGH.promptGuidance().contains("不要输出思考过程"))
    }
}
