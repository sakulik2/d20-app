package xyz.sakulik.d20.app.ui.setup

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
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
    data class NavigateToCreation(
        val campaignId: String,
        val rulesetId: String
    ) : WorldBuilderUiEvent()
    data class Error(val message: String) : WorldBuilderUiEvent()
}

class WorldBuilderViewModel(
    private val context: android.content.Context,
    private val campaignDao: CampaignDao,
    private val keyManager: LlmKeyManager,
    private val repository: LlmRepository
) : BaseViewModel<WorldBuilderUiState, WorldBuilderUiEvent>(WorldBuilderUiState()) {
    private var initializedDraftKey: Pair<String, String>? = null

    fun init(campaignId: String, rulesetId: String) {
        val ruleset = RulesetRegistry.getRuleset(context, rulesetId)
        if (ruleset == null) {
            initializedDraftKey = null
            val message = "规则包 $rulesetId 无法加载，请返回并检查规则包。"
            updateState {
                it.copy(
                    campaignId = campaignId,
                    rulesetId = rulesetId,
                    rulesetName = rulesetId,
                    presets = emptyList(),
                    isLoading = false,
                    error = message
                )
            }
            sendEvent(WorldBuilderUiEvent.Error(message))
            return
        }
        val systemPresets = ruleset.worldviewPresets
        val canonicalRulesetId = ruleset.id
        val pluginRepository = PluginRepository(context)
        val allPluginIds = pluginRepository.listPluginIds(PluginType.WORLDVIEW)
        val pluginPresets = allPluginIds.mapNotNull { pluginId ->
            WorldviewProvider.loadManifest(pluginRepository, pluginId)
        }.filter { preset ->
            "any" in preset.compatibleRulesets ||
                canonicalRulesetId in preset.compatibleRulesets ||
                rulesetId in preset.compatibleRulesets
        }
        val allPresets = (systemPresets + pluginPresets)
            .associateBy(WorldviewManifest::id)
            .values
            .sortedBy(WorldviewManifest::name)
        val draftKey = campaignId to canonicalRulesetId

        if (initializedDraftKey == draftKey) {
            updateState {
                it.copy(
                    rulesetId = canonicalRulesetId,
                    rulesetName = ruleset.name,
                    presets = allPresets
                )
            }
            return
        }
        initializedDraftKey = draftKey

        updateState {
            it.copy(
                campaignId = campaignId,
                rulesetId = canonicalRulesetId,
                rulesetName = ruleset.name,
                presets = allPresets,
                worldName = "未命名世界",
                isLoading = true,
                error = null
            )
        }

        viewModelScope.launch {
            try {
                val campaign = campaignDao.getCampaignById(campaignId)
                if (initializedDraftKey != draftKey) return@launch
                if (campaign == null) {
                    initializedDraftKey = null
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
                if (initializedDraftKey != draftKey) return@launch
                initializedDraftKey = null
                updateState { it.copy(isLoading = false, error = exception.message) }
                sendEvent(WorldBuilderUiEvent.Error("加载世界设定失败: ${exception.message}"))
            }
        }
    }

    fun onWorldNameChange(name: String) = updateState { it.copy(worldName = name, error = null) }
    fun onToneChange(tone: String) = updateState { it.copy(selectedTone = tone, error = null) }
    fun onSettingChange(setting: String) = updateState { it.copy(coreSetting = setting, error = null) }
    fun onCustomRulesChange(rules: String) = updateState { it.copy(customRules = rules, error = null) }

    fun applyPreset(preset: WorldviewManifest) {
        updateState { it.copy(
            worldName = preset.name,
            selectedWorldviewId = preset.id,
            selectedTone = preset.tone,
            coreSetting = preset.coreSetting,
            customRules = preset.customRules,
            error = null
        ) }
    }

    fun polishSetting() {
        val state = uiState.value
        if (state.isPolishing) return
        val originalSetting = state.coreSetting
        if (originalSetting.isBlank()) {
            sendEvent(WorldBuilderUiEvent.Error("请先填写核心设定，再使用 AI 润色。"))
            return
        }
        if (originalSetting.length > MAX_CORE_SETTING_LENGTH) {
            sendEvent(WorldBuilderUiEvent.Error("核心设定不能超过 $MAX_CORE_SETTING_LENGTH 个字符。"))
            return
        }
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

        updateState { it.copy(isPolishing = true, error = null) }
        viewModelScope.launch {
            try {
                val baseUrl = keyManager.getBaseUrl()
                val chatMessages = listOf(xyz.sakulik.d20.app.data.model.ChatMessage("user", prompt))
                val polishedText = StringBuilder()
                repository.chatRaw(baseUrl, chatMessages).collect { delta ->
                    if (delta.startsWith("错误:")) {
                        throw IllegalStateException(delta.removePrefix("错误:").trim())
                    }
                    polishedText.append(delta)
                    if (polishedText.length > MAX_CORE_SETTING_LENGTH) {
                        throw IllegalStateException("润色结果超过 $MAX_CORE_SETTING_LENGTH 个字符")
                    }
                }
                val result = polishedText.toString().trim()
                if (result.isBlank()) {
                    throw IllegalStateException("模型没有返回有效内容")
                }
                val currentState = uiState.value
                val sourceStillCurrent = currentState.coreSetting == originalSetting &&
                    currentState.worldName == state.worldName &&
                    currentState.selectedTone == state.selectedTone &&
                    currentState.customRules == state.customRules &&
                    currentState.rulesetId == state.rulesetId
                if (sourceStillCurrent) {
                    updateState { it.copy(coreSetting = result, error = null) }
                } else {
                    sendEvent(WorldBuilderUiEvent.Error("润色期间设定已被修改，已保留你的编辑。"))
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (e: Exception) {
                sendEvent(WorldBuilderUiEvent.Error("AI 润色失败: ${e.message ?: "未知错误"}"))
            } finally {
                updateState { it.copy(isPolishing = false) }
            }
        }
    }

    fun confirmAndContinue() {
        val state = uiState.value
        if (state.isLoading) return
        val normalized = state.copy(
            worldName = state.worldName.trim(),
            selectedTone = state.selectedTone.trim(),
            coreSetting = state.coreSetting.trim(),
            customRules = state.customRules.trim()
        )
        val validationError = validate(normalized)
        if (validationError != null) {
            updateState { it.copy(error = validationError) }
            sendEvent(WorldBuilderUiEvent.Error(validationError))
            return
        }
        updateState {
            normalized.copy(isLoading = true, error = null)
        }
        viewModelScope.launch {
            try {
                val campaign = campaignDao.getCampaignById(normalized.campaignId)
                if (campaign != null) {
                    val updatedCampaign = campaign.copy(
                        worldName = normalized.worldName,
                        worldviewId = normalized.selectedWorldviewId,
                        tone = normalized.selectedTone,
                        coreSetting = normalized.coreSetting,
                        customRules = normalized.customRules,
                        lastUpdated = System.currentTimeMillis()
                    )
                    campaignDao.updateCampaign(updatedCampaign)
                    updateState { it.copy(isLoading = false) }
                    sendEvent(
                        WorldBuilderUiEvent.NavigateToCreation(
                            campaignId = normalized.campaignId,
                            rulesetId = normalized.rulesetId
                        )
                    )
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

    private fun validate(state: WorldBuilderUiState): String? = when {
        state.campaignId.isBlank() -> "剧本草稿 ID 无效，请返回后重试。"
        state.rulesetId.isBlank() -> "规则包 ID 无效，请返回后重试。"
        state.worldName.isBlank() -> "请填写世界名称。"
        state.worldName.length > MAX_WORLD_NAME_LENGTH ->
            "世界名称不能超过 $MAX_WORLD_NAME_LENGTH 个字符。"
        state.selectedTone.length > MAX_TONE_LENGTH ->
            "游戏基调不能超过 $MAX_TONE_LENGTH 个字符。"
        state.coreSetting.isBlank() -> "请填写核心设定。"
        state.coreSetting.length > MAX_CORE_SETTING_LENGTH ->
            "核心设定不能超过 $MAX_CORE_SETTING_LENGTH 个字符。"
        state.customRules.length > MAX_CUSTOM_RULES_LENGTH ->
            "附加房规不能超过 $MAX_CUSTOM_RULES_LENGTH 个字符。"
        else -> null
    }

    private companion object {
        const val MAX_WORLD_NAME_LENGTH = 80
        const val MAX_TONE_LENGTH = 200
        const val MAX_CORE_SETTING_LENGTH = 6_000
        const val MAX_CUSTOM_RULES_LENGTH = 3_000
    }
}
