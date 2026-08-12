package xyz.sakulik.d20.app.domain.rules.action

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ResourceLedger {
    fun canApply(stats: Map<String, String>, changes: Map<String, Int>): Boolean {
        return apply(stats, changes) != null
    }

    fun apply(stats: Map<String, String>, changes: Map<String, Int>): Map<String, String>? {
        if (changes.isEmpty()) return stats
        return runCatching {
            var updatedResources = Json.parseToJsonElement(
                stats["resources"].orEmpty()
            ).jsonObject
            changes.forEach { (path, delta) ->
                updatedResources = updatePath(updatedResources, path.split('.'), delta)
                    ?: return null
            }
            stats + ("resources" to updatedResources.toString())
        }.getOrNull()
    }

    private fun updatePath(
        root: JsonObject,
        path: List<String>,
        delta: Int
    ): JsonObject? {
        if (path.size != 2 || path.first() != "spell_slots") return null
        val group = root[path.first()]?.jsonObject ?: return null
        val entry = group[path.last()]?.jsonObject ?: return null
        val current = entry["current"]?.jsonPrimitive?.intOrNull ?: return null
        val max = entry["max"]?.jsonPrimitive?.intOrNull ?: return null
        val next = current + delta
        if (next !in 0..max) return null
        val updatedEntry = JsonObject(entry + ("current" to JsonPrimitive(next)))
        val updatedGroup = JsonObject(group + (path.last() to updatedEntry))
        return JsonObject(root + (path.first() to updatedGroup))
    }
}
