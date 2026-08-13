package xyz.sakulik.d20.app.ui.settings

import xyz.sakulik.d20.app.BuildConfig
import xyz.sakulik.d20.app.data.model.ConversationMemoryPolicy
import xyz.sakulik.d20.app.data.security.ApiProtocol
import xyz.sakulik.d20.app.data.security.LlmKeyManager
import xyz.sakulik.d20.app.data.security.ReasoningEffort
import xyz.sakulik.d20.app.ui.base.BaseViewModel
import xyz.sakulik.d20.app.ui.base.UiEvent
import xyz.sakulik.d20.app.ui.base.UiState

data class SettingsUiState(
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val apiProtocol: String = "DEFAULT",
    val reasoningEffort: String = ReasoningEffort.AUTO.name,
    val maxHistoryTurns: Int = ConversationMemoryPolicy.DEFAULT_RECENT_TURNS,
    val isPasswordVisible: Boolean = false,
    val isSaved: Boolean = false,
    val error: String? = null
) : UiState

sealed class SettingsUiEvent : UiEvent {
    object Back : SettingsUiEvent()
}

class SettingsViewModel(
    private val keyManager: LlmKeyManager
) : BaseViewModel<SettingsUiState, SettingsUiEvent>(SettingsUiState()) {

    init {
        updateState { it.copy(
            apiKey = keyManager.getKey() ?: "",
            baseUrl = keyManager.getBaseUrl(),
            model = keyManager.getModel(),
            apiProtocol = keyManager.getApiProtocol(),
            reasoningEffort = keyManager.getReasoningEffort(),
            maxHistoryTurns = keyManager.getMaxHistoryTurns()
        ) }
    }

    fun onApiKeyChange(newKey: String) {
        updateState { it.copy(apiKey = newKey, isSaved = false, error = null) }
    }

    fun onBaseUrlChange(newUrl: String) {
        updateState { it.copy(baseUrl = newUrl, isSaved = false, error = null) }
    }

    fun onModelChange(newModel: String) {
        updateState { it.copy(model = newModel, isSaved = false, error = null) }
    }

    fun onApiProtocolChange(newProtocol: String) {
        updateState { it.copy(apiProtocol = newProtocol, isSaved = false, error = null) }
    }

    fun onReasoningEffortChange(newEffort: String) {
        updateState {
            it.copy(
                reasoningEffort = ReasoningEffort.fromStored(newEffort).name,
                isSaved = false,
                error = null
            )
        }
    }

    fun onMaxHistoryTurnsChange(newTurns: Int) {
        updateState {
            it.copy(
                maxHistoryTurns = ConversationMemoryPolicy.sanitizeRecentTurns(newTurns),
                isSaved = false,
                error = null
            )
        }
    }

    fun togglePasswordVisibility() {
        updateState { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    fun saveSettings() {
        val state = uiState.value
        val apiKey = state.apiKey.trim()
        val baseUrl = state.baseUrl.trim().trimEnd('/')
        val model = state.model.trim()
        val protocol = runCatching { ApiProtocol.valueOf(state.apiProtocol) }.getOrNull()

        val error = when {
            apiKey.isBlank() -> "API Key 不能为空"
            baseUrl.isBlank() -> "API Base URL 不能为空"
            !isValidApiUrl(baseUrl) -> if (BuildConfig.DEBUG) {
                "API Base URL 必须是有效的 HTTP 或 HTTPS 地址"
            } else {
                "Release 版本的 API Base URL 必须使用 HTTPS"
            }
            model.isBlank() -> "AI 模型名称不能为空"
            protocol == null -> "API 协议配置无效"
            else -> null
        }
        if (error != null) {
            updateState { it.copy(isSaved = false, error = error) }
            return
        }
        val selectedProtocol = requireNotNull(protocol)

        keyManager.saveKey(apiKey)
        keyManager.saveBaseUrl(baseUrl)
        keyManager.saveModel(model)
        keyManager.saveApiProtocol(selectedProtocol.name)
        keyManager.saveMaxHistoryTurns(state.maxHistoryTurns)
        val reasoningEffort = ReasoningEffort.fromStored(state.reasoningEffort)
        keyManager.saveReasoningEffort(reasoningEffort.name)
        updateState {
            it.copy(
                apiKey = apiKey,
                baseUrl = baseUrl,
                model = model,
                apiProtocol = selectedProtocol.name,
                reasoningEffort = reasoningEffort.name,
                isSaved = true,
                error = null
            )
        }
    }

    private fun isValidApiUrl(value: String): Boolean {
        return runCatching {
            val uri = java.net.URI(value)
            val allowedSchemes = if (BuildConfig.DEBUG) setOf("http", "https") else setOf("https")
            uri.scheme?.lowercase() in allowedSchemes && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
    }
}
