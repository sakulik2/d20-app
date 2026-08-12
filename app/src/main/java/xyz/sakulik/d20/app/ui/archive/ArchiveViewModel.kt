package xyz.sakulik.d20.app.ui.archive

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import xyz.sakulik.d20.app.data.local.CampaignDao
import xyz.sakulik.d20.app.data.local.CampaignEntity
import xyz.sakulik.d20.app.data.local.CharacterDao
import xyz.sakulik.d20.app.domain.common.validator.CampaignIntegrityValidator
import xyz.sakulik.d20.app.domain.common.validator.IntegrityStatus
import xyz.sakulik.d20.app.ui.base.BaseViewModel
import xyz.sakulik.d20.app.ui.base.UiEvent
import xyz.sakulik.d20.app.ui.base.UiState

/**
 * 存档列表 UI 状态
 */
data class ArchiveUiState(
    val campaigns: List<CampaignEntity> = emptyList(),
    val readyCampaignIds: Set<String> = emptySet(),
    val validationResults: Map<String, IntegrityStatus> = emptyMap(),
    val isLoading: Boolean = true,
    val activeMessage: String? = null
) : UiState

/**
 * 存档列表 UI 事件
 */
sealed class ArchiveUiEvent : UiEvent {
    data class NavigateToCampaign(val campaignId: String) : ArchiveUiEvent()
    object NavigateToCreate : ArchiveUiEvent()
}

class ArchiveViewModel(
    private val campaignDao: CampaignDao,
    private val characterDao: CharacterDao,
    private val validator: CampaignIntegrityValidator? = null
) : BaseViewModel<ArchiveUiState, ArchiveUiEvent>(ArchiveUiState()) {

    init {
        observeCampaigns()
    }

    private fun observeCampaigns() {
        viewModelScope.launch {
            combine(
                campaignDao.getAllCampaigns(),
                characterDao.observeCampaignIds()
                    .map { campaignIds -> campaignIds.toSet() }
                    .distinctUntilChanged()
            ) { campaigns, readyCampaignIds ->
                campaigns to readyCampaignIds
            }.collect { (campaigns, readyCampaignIds) ->
                updateState {
                    it.copy(
                        campaigns = campaigns,
                        readyCampaignIds = readyCampaignIds,
                        validationResults = it.validationResults.filterKeys(readyCampaignIds::contains),
                        isLoading = false
                    )
                }
                validateAllCampaigns(campaigns.filter { it.id in readyCampaignIds })
            }
        }
    }

    private suspend fun validateAllCampaigns(list: List<CampaignEntity>) {
        if (validator == null) return
        val resultsMap = uiState.value.validationResults.toMutableMap()
        list.forEach { campaign ->
            val status = try {
                validator.validateAndRepair(campaign.id)
            } catch (exception: Exception) {
                IntegrityStatus.Critical("校验失败：${exception.message ?: "未知错误"}")
            }
            resultsMap[campaign.id] = status
        }
        updateState { it.copy(validationResults = resultsMap) }
    }

    fun validateSingleCampaign(campaignId: String) {
        if (validator == null) return
        viewModelScope.launch {
            val status = try {
                validator.validateAndRepair(campaignId)
            } catch (exception: Exception) {
                IntegrityStatus.Critical("校验失败：${exception.message ?: "未知错误"}")
            }
            val resultsMap = uiState.value.validationResults.toMutableMap()
            resultsMap[campaignId] = status
            
            val message = when (status) {
                is IntegrityStatus.Healthy -> "存档状态完整，未发现数据缺失。"
                is IntegrityStatus.Repaired -> "已自动修补损坏项：\n• " + status.repairLog.joinToString("\n• ")
                is IntegrityStatus.Critical -> "存档数据异常：${status.reason}"
            }
            updateState { it.copy(validationResults = resultsMap, activeMessage = message) }
        }
    }

    fun clearActiveMessage() {
        updateState { it.copy(activeMessage = null) }
    }

    fun onCampaignClick(id: String) {
        sendEvent(ArchiveUiEvent.NavigateToCampaign(id))
    }

    fun onCreateClick() {
        sendEvent(ArchiveUiEvent.NavigateToCreate)
    }

    fun deleteCampaign(id: String) {
        viewModelScope.launch {
            campaignDao.deleteCampaign(id)
        }
    }
}
