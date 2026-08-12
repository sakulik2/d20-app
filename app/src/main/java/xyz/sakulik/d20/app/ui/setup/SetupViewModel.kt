package xyz.sakulik.d20.app.ui.setup

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import xyz.sakulik.d20.app.data.local.CampaignDao
import xyz.sakulik.d20.app.data.local.CampaignEntity
import xyz.sakulik.d20.app.data.local.CharacterDao
import xyz.sakulik.d20.app.data.security.LlmKeyManager
import xyz.sakulik.d20.app.domain.rules.RulesetRegistry
import xyz.sakulik.d20.app.ui.base.BaseViewModel
import xyz.sakulik.d20.app.ui.base.UiEvent
import xyz.sakulik.d20.app.ui.base.UiState
import java.util.UUID

/**
 * 设置页面的 UI 状态
 */
data class SetupUiState(
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = "gpt-3.5-turbo",
    val selectedRulesetId: String = "coc_7e",
    val isPasswordVisible: Boolean = false,
    val showApiConfig: Boolean = true,
    val isLoading: Boolean = false,
    val error: String? = null
) : UiState

/**
 * 设置页面的副作用事件
 */
sealed class SetupUiEvent : UiEvent {
    data class NavigateToWorldBuilder(val campaignId: String, val rulesetId: String) : SetupUiEvent()
}

class SetupViewModel(
    private val context: android.content.Context,
    private val keyManager: LlmKeyManager,
    private val campaignDao: CampaignDao
) : BaseViewModel<SetupUiState, SetupUiEvent>(SetupUiState()) {

    init {
        val hasKey = keyManager.hasKey()
        updateState { it.copy(
            showApiConfig = !hasKey,
            apiKey = if(hasKey) "********" else "", // 占位符
            baseUrl = keyManager.getBaseUrl(), // 从 keyManager 获取实际保存的地址
            model = keyManager.getModel()
        ) }
    }

    // 动态获取全量安装的规则系统元数据列表 (支持用户从规则更新中心导入的自定义规则包)
    fun getAvailableSystems(): List<xyz.sakulik.d20.app.domain.rules.SystemMetadata> {
        return RulesetRegistry.getMetadataList(context)
    }

    fun onApiKeyChange(newKey: String) {
        updateState { it.copy(apiKey = newKey, error = null) }
    }

    fun onBaseUrlChange(newUrl: String) {
        updateState { it.copy(baseUrl = newUrl, error = null) }
    }

    fun onModelChange(newModel: String) {
        updateState { it.copy(model = newModel, error = null) }
    }

    fun onPasswordVisibilityToggle() {
        updateState { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    fun onSystemSelected(systemId: String) {
        updateState { it.copy(selectedRulesetId = systemId) }
    }

    /**
     * 开始冒险：保存 Key 并创建新剧本
     */
    fun startAdventure(campaignTitle: String) {
        val state = uiState.value
        val apiKey = state.apiKey.trim()
        val baseUrl = state.baseUrl.trim().trimEnd('/')
        val model = state.model.trim()
        val error = when {
            state.showApiConfig && apiKey.isBlank() -> "API Key 不能为空"
            baseUrl.isBlank() -> "API Base URL 不能为空"
            !isValidHttpUrl(baseUrl) -> "API Base URL 必须是有效的 HTTP 或 HTTPS 地址"
            model.isBlank() -> "AI 模型名称不能为空"
            else -> null
        }
        if (error != null) {
            updateState { it.copy(error = error) }
            return
        }

        viewModelScope.launch {
            try {
                updateState { it.copy(isLoading = true, error = null) }
 
                // 1. 安全保存 Key 和 Base URL (仅当显示时)
                if (state.showApiConfig) {
                    keyManager.saveKey(apiKey)
                    keyManager.saveBaseUrl(baseUrl)
                    keyManager.saveModel(model)
                }

                // 2. 创建新剧本 (Campaign)
                val campaignId = UUID.randomUUID().toString()
                val newCampaign = CampaignEntity(
                    id = campaignId,
                    title = campaignTitle.trim().ifBlank { "未命名剧本" },
                    systemId = state.selectedRulesetId
                )

                campaignDao.insertCampaign(newCampaign)

                // 3. 跳转到世界观构建界面
                sendEvent(SetupUiEvent.NavigateToWorldBuilder(campaignId, state.selectedRulesetId))
            } catch (e: Exception) {
                updateState { it.copy(isLoading = false, error = "初始化失败: ${e.message}") }
            }
        }
    }

    private fun isValidHttpUrl(value: String): Boolean {
        return runCatching {
            val uri = java.net.URI(value)
            uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
    }
}
