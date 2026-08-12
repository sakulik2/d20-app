package xyz.sakulik.d20.app.domain.common.updater

import kotlinx.serialization.Serializable

enum class PluginType(val dirName: String) {
    RULESET("rulesets"),
    WORLDVIEW("worldviews")
}

@Serializable
data class RemotePluginIndex(
    val plugins: List<RemotePluginEntry>
)

@Serializable
data class RemotePluginEntry(
    val id: String,
    val type: PluginType,
    val name: String,
    val description: String,
    val version: String,
    val downloadUrl: String,
    val sha256: String
)

sealed interface UpdateCheckResult {
    data class Success(val states: List<PluginUpdateState>) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

sealed class PluginUpdateState {
    data class UpToDate(val id: String, val type: PluginType, val currentVersion: String) : PluginUpdateState()
    data class UpdateAvailable(val id: String, val type: PluginType, val oldVersion: String?, val newVersion: String, val entry: RemotePluginEntry) : PluginUpdateState()
    data class NotInstalled(val id: String, val type: PluginType, val entry: RemotePluginEntry) : PluginUpdateState()
}
