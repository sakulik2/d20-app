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
            "name" to stringSchema(),
            "stats" to valueMapSchema(),
            "bio" to stringSchema(),
            "items" to obj(
                "type" to JsonPrimitive("array"),
                "items" to obj(
                    "type" to JsonPrimitive("object"),
                    "additionalProperties" to JsonPrimitive(false),
                    "required" to array("name", "description", "category", "modifiers"),
                    "properties" to obj(
                        "name" to stringSchema(),
                        "description" to stringSchema(),
                        "category" to stringSchema(),
                        "modifiers" to valueMapSchema()
                    )
                )
            )
        )
    )

    private fun valueMapSchema(): JsonObject = obj(
        "type" to JsonPrimitive("object"),
        "additionalProperties" to obj(
            "type" to array("string", "number", "integer", "boolean", "null")
        )
    )

    private fun stringSchema(): JsonObject = obj("type" to JsonPrimitive("string"))

    private fun obj(vararg values: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
        JsonObject(linkedMapOf(*values))

    private fun array(vararg values: String): JsonArray =
        JsonArray(values.map(::JsonPrimitive))
}
