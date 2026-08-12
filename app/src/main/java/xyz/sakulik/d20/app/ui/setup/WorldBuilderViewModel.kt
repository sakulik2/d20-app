package xyz.sakulik.d20.app.ui.setup

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import xyz.sakulik.d20.app.data.local.CampaignDao
import xyz.sakulik.d20.app.data.repository.LlmRepository
import xyz.sakulik.d20.app.data.security.LlmKeyManager
import xyz.sakulik.d20.app.domain.rules.RulesetRegistry
import xyz.sakulik.d20.app.domain.common.updater.PluginRepository
import xyz.sakulik.d20.app.domain.common.updater.PluginType
import xyz.sakulik.d20.app.domain.worldview.WorldviewManifest
import xyz.sakulik.d20.app.domain.worldview.WorldviewProvider
import xyz.sakulik.d20.app.ui.base.BaseViewModel
import xyz.sakulik.d20.app.ui.base.UiEvent
import xyz.sakulik.d20.app.ui.base.UiState

data class WorldBuilderUiState(
    val campaignId: String = "",
    val rulesetId: String = "",
    val rulesetName: String = "",
    val worldName: String = "",
    val selectedWorldviewId: String? = null,
    val selectedTone: String = "",
    val coreSetting: String = "",
    val customRules: String = "",
    val presets: List<WorldviewManifest> = emptyList(),
    val isLoading: Boolean = false,
    val isPolishing: Boolean = false,
    val error: String? = null
) : UiState

sealed class WorldBuilderUiEvent : UiEvent {
    object NavigateToCreation : WorldBuilderUiEvent()
    data class Error(val message: String) : WorldBuilderUiEvent()
}

class WorldBuilderViewModel(
    private val context: android.content.Context,
    private val campaignDao: CampaignDao,
    private val keyManager: LlmKeyManager,
    private val repository: LlmRepository
) : BaseViewModel<WorldBuilderUiState, WorldBuilderUiEvent>(WorldBuilderUiState()) {

    fun init(campaignId: String, rulesetId: String) {
        val ruleset = RulesetRegistry.getRuleset(context, rulesetId)
        val systemPresets = ruleset?.worldviewPresets ?: emptyList()
        
        // 1. 加载所有独立的 Worldview 插件
        val repository = PluginRepository(context)
        val allPluginIds = repository.listPluginIds(PluginType.WORLDVIEW)
        
        val pluginPresets = allPluginIds.mapNotNull { pluginId ->
            repository.loadPluginJson(PluginType.WORLDVIEW, pluginId)?.let { 
                WorldviewProvider.parseManifest(it.first)
            }
        }.filter { it.compatibleRulesets.contains(rulesetId) || it.compatibleRulesets.contains("any") }

        // 2. 最终列表：系统内置 + 外部插件
        val allPresets = (systemPresets + pluginPresets)

        updateState {
            it.copy(
                campaignId = campaignId,
                rulesetId = rulesetId,
                rulesetName = ruleset?.name ?: rulesetId,
                presets = allPresets,
                worldName = "未命名世界",
                isLoading = true,
                error = null
            )
        }

        viewModelScope.launch {
            try {
                val campaign = campaignDao.getCampaignById(campaignId)
                if (campaign == null) {
                    updateState { it.copy(isLoading = false, error = "找不到对应的剧本草稿") }
                    sendEvent(WorldBuilderUiEvent.Error("找不到对应的剧本草稿，请返回后重试。"))
                    return@launch
                }

                updateState {
                    it.copy(
                        worldName = campaign.worldName.ifBlank { "未命名世界" },
                        selectedWorldviewId = campaign.worldviewId,
                        selectedTone = campaign.tone,
                        coreSetting = campaign.coreSetting,
                        customRules = campaign.customRules,
                        isLoading = false
                    )
                }
            } catch (exception: Exception) {
                updateState { it.copy(isLoading = false, error = exception.message) }
                sendEvent(WorldBuilderUiEvent.Error("加载世界设定失败: ${exception.message}"))
            }
        }
    }

    fun onWorldNameChange(name: String) = updateState { it.copy(worldName = name) }
    fun onToneChange(tone: String) = updateState { it.copy(selectedTone = tone) }
    fun onSettingChange(setting: String) = updateState { it.copy(coreSetting = setting) }
    fun onCustomRulesChange(rules: String) = updateState { it.copy(customRules = rules) }

    fun applyPreset(preset: WorldviewManifest) {
        updateState { it.copy(
            worldName = preset.name,
            selectedWorldviewId = preset.id,
            selectedTone = preset.tone,
            coreSetting = preset.coreSetting,
            customRules = preset.customRules
        ) }
    }

    fun polishSetting() {
        val state = uiState.value
        val prompt = """
            你是一位高级文案与 TRPG 背景设定专家。请根据以下提供的世界观信息，扩写并优化“核心设定描述”。
            
            [当前环境信息]
            - 规则系统：${state.rulesetName}
            - 世界名称：${state.worldName}
            - 游戏基调：${state.selectedTone}
            - 房规/特殊要求：${state.customRules}
            
            [待优化内容]
            ${state.coreSetting}
            
            请结合以上所有背景信息，将“待优化内容”扩写成一段约 200 字左右、充满氛围感、严谨且富有想象力的世界观设定。
            要求：
            1. 风格必须与“游戏基调”一致。
            2. 如果有“房规”，请在设定中体现其对世界的影响。
            3. 请直接返回扩写后的文本，不要带有任何前缀或解释。
        """.trimIndent()

        viewModelScope.launch {
            updateState { it.copy(isPolishing = true) }
            try {
                val baseUrl = keyManager.getBaseUrl()
                val chatMessages = listOf(xyz.sakulik.d20.app.data.model.ChatMessage("user", prompt))
                
                var polishedText = ""
                repository.chatRaw(baseUrl, chatMessages).collect { delta ->
                    if (delta.startsWith("错误:")) {
                        sendEvent(WorldBuilderUiEvent.Error(delta))
                        return@collect
                    }
                    polishedText += delta
                    updateState { it.copy(coreSetting = polishedText) }
                }
                updateState { it.copy(isPolishing = false) }
            } catch (e: Exception) {
                updateState { it.copy(isPolishing = false) }
                sendEvent(WorldBuilderUiEvent.Error("AI 润色失败: ${e.message}"))
            }
        }
    }

    fun confirmAndContinue() {
        val state = uiState.value
        viewModelScope.launch {
            try {
                updateState { it.copy(isLoading = true) }
                val campaign = campaignDao.getCampaignById(state.campaignId)
                if (campaign != null) {
                    val updatedCampaign = campaign.copy(
                        worldName = state.worldName,
                        worldviewId = state.selectedWorldviewId,
                        tone = state.selectedTone,
                        coreSetting = state.coreSetting,
                        customRules = state.customRules,
                        lastUpdated = System.currentTimeMillis()
                    )
                    campaignDao.updateCampaign(updatedCampaign)
                    updateState { it.copy(isLoading = false) }
                    sendEvent(WorldBuilderUiEvent.NavigateToCreation)
                } else {
                    updateState { it.copy(isLoading = false) }
                    sendEvent(WorldBuilderUiEvent.Error("找不到对应的剧本草稿，请返回后重试。"))
                }
            } catch (e: Exception) {
                updateState { it.copy(isLoading = false) }
                sendEvent(WorldBuilderUiEvent.Error("保存失败: ${e.message}"))
            }
        }
    }
}
