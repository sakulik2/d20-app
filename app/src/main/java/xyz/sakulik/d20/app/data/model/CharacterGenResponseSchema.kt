package xyz.sakulik.d20.app.data.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object CharacterGenResponseSchema {
    val value: JsonObject = obj(
        "type" to JsonPrimitive("object"),
        "additionalProperties" to JsonPrimitive(false),
        "required" to array("name", "stats", "bio", "items"),
        "properties" to obj(
            "name" to stringSchema(maxLength = 40),
            "stats" to valueMapSchema(),
            "bio" to stringSchema(maxLength = 600),
            "items" to obj(
                "type" to JsonPrimitive("array"),
                "minItems" to JsonPrimitive(1),
                "maxItems" to JsonPrimitive(5),
                "items" to obj(
                    "type" to JsonPrimitive("object"),
                    "additionalProperties" to JsonPrimitive(false),
                    "required" to array("name", "description", "category", "modifiers"),
                    "properties" to obj(
                        "name" to stringSchema(maxLength = 40),
                        "description" to stringSchema(maxLength = 120),
                        "category" to stringSchema(maxLength = 20),
                        "modifiers" to valueMapSchema(maxProperties = 10)
                    )
                )
            )
        )
    )

    private fun valueMapSchema(maxProperties: Int? = null): JsonObject = JsonObject(
        buildMap {
            put("type", JsonPrimitive("object"))
            put(
                "additionalProperties",
                obj("type" to array("string", "number", "integer", "boolean", "null"))
            )
            maxProperties?.let { put("maxProperties", JsonPrimitive(it)) }
        }
    )

    private fun stringSchema(maxLength: Int? = null): JsonObject = JsonObject(
        buildMap {
            put("type", JsonPrimitive("string"))
            maxLength?.let { put("maxLength", JsonPrimitive(it)) }
        }
    )

    private fun obj(vararg values: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
        JsonObject(linkedMapOf(*values))

    private fun array(vararg values: String): JsonArray =
        JsonArray(values.map(::JsonPrimitive))
}
