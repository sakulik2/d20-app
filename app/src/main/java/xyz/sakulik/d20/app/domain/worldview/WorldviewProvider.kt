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

    private fun isValidManifest(manifest: WorldviewManifest): Boolean {
        return isSafePluginId(manifest.id) &&
            manifest.name.isNotBlank() &&
            isSupportedPluginVersion(manifest.version) &&
            manifest.compatibleRulesets.isNotEmpty() &&
            manifest.compatibleRulesets.all { rulesetId ->
                rulesetId == "any" || isSafePluginId(rulesetId)
            }
    }
}
