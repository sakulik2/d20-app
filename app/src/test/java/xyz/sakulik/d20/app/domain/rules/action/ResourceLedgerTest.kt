package xyz.sakulik.d20.app.domain.rules.action

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ResourceLedgerTest {
    private val stats = mapOf(
        "hp" to "10",
        "resources" to """{"spell_slots":{"level_1":{"current":2,"max":4}},"ki_points":{"current":3,"max":3}}"""
    )

    @Test
    fun appliesSpellSlotChangeAndPreservesOtherResources() {
        val updated = ResourceLedger.apply(stats, mapOf("spell_slots.level_1" to -1))!!
        val resources = Json.parseToJsonElement(updated.getValue("resources")).jsonObject

        assertEquals(1, resources.getValue("spell_slots").jsonObject
            .getValue("level_1").jsonObject.getValue("current").jsonPrimitive.int)
        assertEquals(3, resources.getValue("ki_points").jsonObject
            .getValue("current").jsonPrimitive.int)
        assertEquals("10", updated["hp"])
    }

    @Test
    fun rejectsInsufficientSlotWithoutPartialMutation() {
        val empty = stats + ("resources" to
            """{"spell_slots":{"level_1":{"current":0,"max":4}}}""")

        assertNull(ResourceLedger.apply(empty, mapOf("spell_slots.level_1" to -1)))
    }

    @Test
    fun cantripChangeLeavesStatsUntouched() {
        assertSame(stats, ResourceLedger.apply(stats, emptyMap()))
    }
}
