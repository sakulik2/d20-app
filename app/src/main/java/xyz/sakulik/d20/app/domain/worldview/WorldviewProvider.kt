package xyz.sakulik.d20.app.domain.worldview

import kotlinx.serialization.json.Json
import xyz.sakulik.d20.app.domain.common.updater.PluginRepository
import xyz.sakulik.d20.app.domain.common.updater.PluginType
import xyz.sakulik.d20.app.domain.common.updater.isSafePluginId
import xyz.sakulik.d20.app.domain.common.updater.isSupportedPluginVersion

object WorldviewProvider {
    private val json = Json { ignoreUnknownKeys = false }

    fun parseManifest(jsonStr: String): WorldviewManifest? {
        return try {
            json.decodeFromString<WorldviewManifest>(jsonStr).takeIf(::isValidManifest)
        } catch (e: Exception) {
            null
        }
    }

    fun loadManifest(repository: PluginRepository, id: String): WorldviewManifest? {
        return repository.loadFirstValid(PluginType.WORLDVIEW, id) { jsonStr ->
            parseManifest(jsonStr)?.takeIf { manifest -> manifest.id == id }
        }?.value
    }

    internal fun isValidManifest(manifest: WorldviewManifest): Boolean {
        return isSafePluginId(manifest.id) &&
            manifest.name.isNotBlank() &&
            manifest.name.length <= MAX_NAME_LENGTH &&
            manifest.defaultWorldName.length <= MAX_NAME_LENGTH &&
            isSupportedPluginVersion(manifest.version) &&
            manifest.compatibleRulesets.isNotEmpty() &&
            manifest.compatibleRulesets.all { rulesetId ->
                rulesetId == "any" || isSafePluginId(rulesetId)
            } &&
            manifest.tone.length <= MAX_TONE_LENGTH &&
            manifest.coreSetting.isNotBlank() &&
            manifest.coreSetting.length <= MAX_SETTING_LENGTH &&
            manifest.systemPromptPayload.length <= MAX_PROMPT_LENGTH &&
            manifest.customRules.length <= MAX_CUSTOM_RULES_LENGTH &&
            listOf(
                manifest.name,
                manifest.defaultWorldName,
                manifest.tone,
                manifest.coreSetting,
                manifest.systemPromptPayload,
                manifest.customRules
            ).none(::containsPromptStructure) &&
            manifest.customMechanicsOverride.isEmpty()
    }

    internal fun isCompatibleWith(
        manifest: WorldviewManifest,
        rulesetId: String,
        vararg aliases: String
    ): Boolean {
        val candidateIds = setOf(rulesetId, *aliases)
        return "any" in manifest.compatibleRulesets ||
            manifest.compatibleRulesets.any(candidateIds::contains)
    }

    private const val MAX_NAME_LENGTH = 80
    private const val MAX_TONE_LENGTH = 200
    private const val MAX_SETTING_LENGTH = 6_000
    private const val MAX_PROMPT_LENGTH = 3_000
    private const val MAX_CUSTOM_RULES_LENGTH = 3_000

    private fun containsPromptStructure(value: String): Boolean {
        return PROMPT_STRUCTURE.containsMatchIn(value)
    }

    private val PROMPT_STRUCTURE = Regex("</?[A-Z][A-Z0-9_]*>")
}
