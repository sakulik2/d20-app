package xyz.sakulik.d20.app.domain.worldview

import kotlinx.serialization.Serializable

const val LEGACY_WORLDVIEW_PROMPT_PENDING = "__legacy_worldview_prompt_pending__"

@Serializable
data class WorldviewManifest(
    val id: String,
    val name: String,
    val defaultWorldName: String = "",
    val author: String = "System",
    val version: String = "1.0.0",
    val compatibleRulesets: List<String> = listOf("any"),
    val tags: List<String> = emptyList(),
    val tone: String = "",
    val coreSetting: String = "",
    val customRules: String = "",
    val systemPromptPayload: String = "",
    val customMechanicsOverride: Map<String, String> = emptyMap()
)
