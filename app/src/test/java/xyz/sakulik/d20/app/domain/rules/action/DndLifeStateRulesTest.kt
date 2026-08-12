package xyz.sakulik.d20.app.domain.rules.action

import org.junit.Assert.assertEquals
import org.junit.Test

class DndLifeStateRulesTest {

    @Test
    fun damageAtZeroAddsFailureAndBreaksStability() {
        val stats = mapOf(
            "hp" to "0",
            "max_hp" to "10",
            "deathSaves" to """{"successes":3,"failures":1,"isStable":true}"""
        )

        val updated = DndLifeStateRules.applyHpDelta(stats, -4)
        val snapshot = DndLifeStateRules.snapshot(updated)

        assertEquals(0, snapshot.hp)
        assertEquals(0, snapshot.successes)
        assertEquals(2, snapshot.failures)
        assertEquals(DndLifeState.DYING, snapshot.state)
    }

    @Test
    fun healingAboveZeroResetsDeathSaves() {
        val stats = mapOf(
            "hp" to "0",
            "max_hp" to "10",
            "deathSaves" to """{"successes":1,"failures":2,"isStable":false}"""
        )

        val snapshot = DndLifeStateRules.snapshot(DndLifeStateRules.applyHpDelta(stats, 3))

        assertEquals(3, snapshot.hp)
        assertEquals(0, snapshot.successes)
        assertEquals(0, snapshot.failures)
        assertEquals(DndLifeState.CONSCIOUS, snapshot.state)
    }

    @Test
    fun hpCannotDropBelowZero() {
        val updated = DndLifeStateRules.applyHpDelta(
            mapOf("hp" to "2", "max_hp" to "10"),
            -20
        )

        assertEquals("0", updated["hp"])
        assertEquals(DndLifeState.DYING, DndLifeStateRules.snapshot(updated).state)
    }

    @Test
    fun ordinaryHealingDoesNotReviveDeadCharacter() {
        val stats = mapOf(
            "hp" to "0",
            "max_hp" to "10",
            "deathSaves" to """{"successes":0,"failures":3,"isStable":false}"""
        )

        val updated = DndLifeStateRules.applyHpDelta(stats, 5)

        assertEquals(stats, updated)
        assertEquals(DndLifeState.DEAD, DndLifeStateRules.snapshot(updated).state)
    }
}
