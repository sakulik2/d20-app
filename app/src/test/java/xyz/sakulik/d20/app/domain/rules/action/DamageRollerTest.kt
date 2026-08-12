package xyz.sakulik.d20.app.domain.rules.action

import org.junit.Assert.assertEquals
import org.junit.Test

class DamageRollerTest {

    @Test
    fun criticalDoublesEveryDiceTermButNotFixedModifier() {
        assertEquals("2d8+3", DamageRoller.criticalFormula("1d8+3"))
        assertEquals("4d6+2d4+3", DamageRoller.criticalFormula("2d6+1d4+3"))
    }
}
