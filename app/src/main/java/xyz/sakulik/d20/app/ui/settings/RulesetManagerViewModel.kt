package xyz.sakulik.d20.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.sakulik.d20.app.domain.rules.RulesetRegistry
import xyz.sakulik.d20.app.domain.common.updater.*
import xyz.sakulik.d20.app.domain.worldview.WorldviewProvider

data class PluginManagerUiState(
    val isChecking: Boolean = false,
    val plugins: List<PluginUpdateState> = emptyList(),
    val downloadProgressMap: Map<String, Float> = emptyMap(),
    val errorMsg: String? = null
)

class PluginManagerViewModel(
    application: Application,
    private val pluginType: PluginType
) : AndroidViewModel(application) {

    private val localRepo = PluginRepository(application)
    private val downloader = ManualDownloader(localRepo)
    private val checker = UpdateChecker(localRepo) { type, id ->
        if (type == PluginType.RULESET) {
            RulesetRegistry.getRuleset(application, id)?.version
        } else {
            localRepo.loadPluginJson(type, id)?.first
                ?.let(WorldviewProvider::parseManifest)
                ?.version
        }
    }

    private val _uiState = MutableStateFlow(PluginManagerUiState())
    val uiState: StateFlow<PluginManagerUiState> = _uiState.asStateFlow()

    init {
        checkUpdates()
    }

    fun checkUpdates() {
        _uiState.update { it.copy(isChecking = true, errorMsg = null) }
        viewModelScope.launch {
            checker.checkUpdates(pluginType, PluginSources.indexUrl(pluginType)).collect { result ->
                when (result) {
                    is UpdateCheckResult.Success -> {
                        _uiState.update {
                            it.copy(
                                isChecking = false,
                                plugins = result.states,
                                errorMsg = null
                            )
                        }
                    }
                    is UpdateCheckResult.Error -> {
                        _uiState.update {
                            it.copy(isChecking = false, errorMsg = result.message)
                        }
                    }
                }
            }
        }
    }

    fun downloadPlugin(entry: RemotePluginEntry) {
        val id = entry.id
        viewModelScope.launch {
            downloader.downloadPlugin(entry) { downloadedId ->
                // 下载成功后的回调：清理缓存
                RulesetRegistry.evictCache(downloadedId)
            }.collect { state ->
                when (state) {
                    is DownloadState.Progress -> {
                        _uiState.update { 
                            val newMap = it.downloadProgressMap.toMutableMap()
                            newMap[id] = state.percent
                            it.copy(downloadProgressMap = newMap)
                        }
                    }
                    is DownloadState.Success -> {
                        _uiState.update { 
                            val newMap = it.downloadProgressMap.toMutableMap()
                            newMap.remove(id)
                            it.copy(downloadProgressMap = newMap)
                        }
                        checkUpdates()
                    }
                    is DownloadState.Error -> {
                        _uiState.update { 
                            val newMap = it.downloadProgressMap.toMutableMap()
                            newMap.remove(id)
                            it.copy(downloadProgressMap = newMap, errorMsg = "下载 [$id] 失败: ${state.message}")
                        }
                    }
                }
            }
        }
    }
    
    fun clearError() {
        _uiState.update { it.copy(errorMsg = null) }
    }
}
