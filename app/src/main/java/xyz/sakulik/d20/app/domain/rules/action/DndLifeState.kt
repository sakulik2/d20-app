package xyz.sakulik.d20.app.domain.rules.action

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class DndLifeState {
    CONSCIOUS,
    DYING,
    STABLE,
    DEAD
}

data class DndLifeSnapshot(
    val state: DndLifeState,
    val hp: Int,
    val successes: Int,
    val failures: Int
)

object DndLifeStateRules {
    private const val DEATH_SAVES_KEY = "deathSaves"

    fun snapshot(stats: Map<String, String>): DndLifeSnapshot {
        val hp = stats["hp"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val saves = runCatching {
            Json.parseToJsonElement(stats[DEATH_SAVES_KEY].orEmpty()).jsonObject
        }.getOrNull()
        val successes = saves?.get("successes")?.jsonPrimitive?.intOrNull?.coerceIn(0, 3) ?: 0
        val failures = saves?.get("failures")?.jsonPrimitive?.intOrNull?.coerceIn(0, 3) ?: 0
        val stable = saves?.get("isStable")?.jsonPrimitive?.booleanOrNull ?: false
        val state = when {
            hp > 0 -> DndLifeState.CONSCIOUS
            failures >= 3 -> DndLifeState.DEAD
            stable || successes >= 3 -> DndLifeState.STABLE
            else -> DndLifeState.DYING
        }
        return DndLifeSnapshot(state, hp, successes, failures)
    }

    fun applyHpDelta(stats: Map<String, String>, delta: Int): Map<String, String> {
        val current = snapshot(stats)
        if (current.state == DndLifeState.DEAD) return stats
        val maxHp = stats["max_hp"]?.toIntOrNull()?.coerceAtLeast(1)
        val targetHp = (current.hp + delta).let { hp ->
            if (maxHp == null) hp.coerceAtLeast(0) else hp.coerceIn(0, maxHp)
        }
        val deathSaves = when {
            targetHp > 0 -> encodedDeathSaves(0, 0, stable = false)
            current.hp <= 0 && delta < 0 -> encodedDeathSaves(
                successes = if (current.state == DndLifeState.STABLE) 0 else current.successes,
                failures = (current.failures + 1).coerceAtMost(3),
                stable = false
            )
            current.hp > 0 && targetHp == 0 -> encodedDeathSaves(0, 0, stable = false)
            else -> stats[DEATH_SAVES_KEY] ?: encodedDeathSaves(0, 0, stable = false)
        }
        return stats + mapOf(
            "hp" to targetHp.toString(),
            DEATH_SAVES_KEY to deathSaves
        )
    }

    fun resetDeathSaves(stats: Map<String, String>): Map<String, String> {
        return stats + (DEATH_SAVES_KEY to encodedDeathSaves(0, 0, stable = false))
    }

    private fun encodedDeathSaves(successes: Int, failures: Int, stable: Boolean): String {
        return buildJsonObject {
            put("successes", successes.coerceIn(0, 3))
            put("failures", failures.coerceIn(0, 3))
            put("isStable", stable)
        }.toString()
    }
}
