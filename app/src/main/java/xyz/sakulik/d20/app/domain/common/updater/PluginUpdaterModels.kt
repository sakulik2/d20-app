package xyz.sakulik.d20.app.domain.common.updater

import kotlinx.serialization.Serializable

private val SAFE_PLUGIN_ID = Regex("^[a-z][a-z0-9_]{1,63}$")

internal fun isSafePluginId(id: String): Boolean = SAFE_PLUGIN_ID.matches(id)

internal fun requireSafePluginId(id: String) {
    require(isSafePluginId(id)) { "插件 ID 不合法：$id" }
}

internal fun isSupportedPluginVersion(version: String): Boolean {
    return PLUGIN_VERSION.matches(version)
}

private val PLUGIN_VERSION = Regex("^(0|[1-9]\\d*)(\\.(0|[1-9]\\d*)){0,2}$")

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
