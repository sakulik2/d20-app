package xyz.sakulik.d20.app.data.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object AiResponseSchema {
    val value: JsonObject = obj(
        "type" to JsonPrimitive("object"),
        "additionalProperties" to JsonPrimitive(false),
        "required" to array("narrative", "game_events"),
        "properties" to obj(
            "narrative" to stringSchema(),
            "game_events" to obj(
                "type" to JsonPrimitive("array"),
                "maxItems" to JsonPrimitive(12),
                "items" to obj(
                    "anyOf" to JsonArray(
                        listOf(
                            requireRollSchema(),
                            addItemSchema(),
                            startCombatSchema()
                        )
                    )
                )
            )
        )
    )

    private fun requireRollSchema(): JsonObject = eventSchema(
        type = "require_roll",
        fields = linkedMapOf(
            "expression" to stringSchema(),
            "threshold" to nullableIntegerSchema(),
            "reason" to stringSchema(),
            "action_id" to nullableStringSchema(),
            "stat_id" to nullableStringSchema(),
            "target_value" to nullableIntegerSchema(),
            "modifier" to nullableIntegerSchema(),
            "target_id" to nullableStringSchema(),
            "slot_level" to nullableIntegerSchema(),
            "weapon_id" to nullableStringSchema(),
            "spell_id" to nullableStringSchema()
        )
    )

    private fun addItemSchema(): JsonObject = eventSchema(
        type = "add_item",
        fields = linkedMapOf(
            "name" to stringSchema(),
            "description" to stringSchema(),
            "category" to stringSchema(),
            "modifiers" to obj(
                "type" to JsonPrimitive("object"),
                "additionalProperties" to JsonPrimitive(false),
                "properties" to obj()
            )
        )
    )

    private fun startCombatSchema(): JsonObject = eventSchema(
        type = "start_combat",
        fields = linkedMapOf(
            "combatants" to obj(
                "type" to JsonPrimitive("array"),
                "minItems" to JsonPrimitive(1),
                "maxItems" to JsonPrimitive(20),
                "items" to combatantSchema()
            )
        )
    )

    private fun combatantSchema(): JsonObject {
        val fields = linkedMapOf(
            "id" to stringSchema(),
            "name" to stringSchema(),
            "initiative" to integerSchema(),
            "ac" to integerSchema(),
            "hp" to integerSchema(),
            "max_hp" to integerSchema(),
            "resistances" to stringArraySchema(),
            "vulnerabilities" to stringArraySchema(),
            "immunities" to stringArraySchema(),
            "saving_throws" to obj(
                "type" to JsonPrimitive("object"),
                "additionalProperties" to JsonPrimitive(false),
                "properties" to obj()
            ),
            "attributes" to obj(
                "type" to JsonPrimitive("object"),
                "additionalProperties" to JsonPrimitive(false),
                "properties" to obj()
            )
        )
        return strictObject(fields)
    }

    private fun eventSchema(
        type: String,
        fields: LinkedHashMap<String, JsonObject> = linkedMapOf()
    ): JsonObject {
        val properties = linkedMapOf<String, JsonObject>()
        properties["type"] = obj(
            "type" to JsonPrimitive("string"),
            "enum" to array(type)
        )
        properties.putAll(fields)
        return strictObject(properties)
    }

    private fun strictObject(fields: LinkedHashMap<String, JsonObject>): JsonObject = obj(
        "type" to JsonPrimitive("object"),
        "additionalProperties" to JsonPrimitive(false),
        "required" to JsonArray(fields.keys.map { JsonPrimitive(it) }),
        "properties" to JsonObject(fields)
    )

    private fun stringSchema(): JsonObject = obj("type" to JsonPrimitive("string"))

    private fun integerSchema(): JsonObject = obj("type" to JsonPrimitive("integer"))

    private fun nullableStringSchema(): JsonObject = obj(
        "type" to array("string", "null")
    )

    private fun nullableIntegerSchema(): JsonObject = obj(
        "type" to array("integer", "null")
    )

    private fun stringArraySchema(): JsonObject = obj(
        "type" to JsonPrimitive("array"),
        "items" to stringSchema()
    )

    private fun obj(vararg values: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
        JsonObject(linkedMapOf(*values))

    private fun array(vararg values: String): JsonArray =
        JsonArray(values.map { JsonPrimitive(it) })
}
