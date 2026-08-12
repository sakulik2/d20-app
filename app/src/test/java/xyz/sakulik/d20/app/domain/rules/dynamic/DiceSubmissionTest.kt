package xyz.sakulik.d20.app.domain.rules.dynamic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiceSubmissionTest {

    @Test
    fun manualAdvantageAcceptsOfflineFinalResult() {
        val submission = DiceSubmission.manual("2d20kh1", 18)

        assertNull(submission.validateAgainst("2d20kh1"))
        assertEquals(18, submission.total)
        assertEquals(emptyList<DiceTermResult>(), submission.terms)
    }

    @Test
    fun rejectsOutOfRangeManualFinalResult() {
        val submission = DiceSubmission.manual("1d20", 21)

        assertEquals("DICE_TOTAL_OUT_OF_RANGE", submission.validateAgainst("1d20")?.code)
    }

    @Test
    fun virtualAdvantageKeepsHighestDieWithAuditTrail() {
        val submission = DiceSubmission.virtual("2d20kh1", listOf(7, 18))

        assertNull(submission.validateAgainst("2d20kh1"))
        assertEquals(listOf(18), submission.keptTerms.map { it.value })
        assertEquals(listOf(7), submission.terms.filter { it.isDropped }.map { it.value })
    }

    @Test
    fun rejectsExpressionMismatch() {
        val submission = DiceSubmission.manual("1d100", 73)

        assertEquals("DICE_EXPRESSION_MISMATCH", submission.validateAgainst("1d20")?.code)
    }

    @Test
    fun manualFinalResultSupportsFixedModifier() {
        val submission = DiceSubmission.manual("2d6+3", 12)

        assertNull(submission.validateAgainst("2d6+3"))
    }

    @Test
    fun virtualResultIncludesFixedModifier() {
        val submission = DiceSubmission(
            expression = "1d20+5",
            terms = listOf(DiceTermResult(value = 12, sides = 20)),
            total = 17,
            source = DiceSubmissionSource.VIRTUAL
        )

        assertNull(submission.validateAgainst("1d20+5"))
    }

    @Test
    fun validatesMultipleDiceGroupsAndFixedModifier() {
        val submission = DiceSubmission(
            expression = "2d6+1d4+3",
            terms = listOf(
                DiceTermResult(4, 6),
                DiceTermResult(5, 6),
                DiceTermResult(2, 4)
            ),
            total = 14,
            source = DiceSubmissionSource.VIRTUAL
        )

        assertNull(submission.validateAgainst("2d6+1d4+3"))
        assertNull(DiceSubmission.manual("2d6+1d4+3", 14).validateAgainst("2d6+1d4+3"))
    }
}
