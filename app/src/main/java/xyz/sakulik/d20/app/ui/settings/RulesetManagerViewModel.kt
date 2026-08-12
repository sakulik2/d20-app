package xyz.sakulik.d20.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
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
    val isDownloadingAll: Boolean = false,
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
            WorldviewProvider.loadManifest(localRepo, id)?.version
        }
    }

    private val _uiState = MutableStateFlow(PluginManagerUiState())
    val uiState: StateFlow<PluginManagerUiState> = _uiState.asStateFlow()
    private var checkJob: Job? = null

    init {
        checkUpdates()
    }

    fun checkUpdates(preserveError: Boolean = false) {
        if (checkJob?.isActive == true) return
        _uiState.update {
            it.copy(isChecking = true, errorMsg = it.errorMsg.takeIf { preserveError })
        }
        checkJob = viewModelScope.launch {
            checker.checkUpdates(pluginType, PluginSources.indexUrl(pluginType)).collect { result ->
                when (result) {
                    is UpdateCheckResult.Success -> {
                        _uiState.update {
                            it.copy(
                                isChecking = false,
                                plugins = result.states,
                                errorMsg = it.errorMsg.takeIf { preserveError }
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
        if (_uiState.value.downloadProgressMap.containsKey(id)) return
        _uiState.update {
            it.copy(downloadProgressMap = it.downloadProgressMap + (id to 0f))
        }
        viewModelScope.launch {
            if (download(entry)) checkUpdates()
        }
    }

    fun downloadAllAvailable() {
        val entries = _uiState.value.plugins.mapNotNull { state ->
            when (state) {
                is PluginUpdateState.UpdateAvailable -> state.entry
                is PluginUpdateState.NotInstalled -> state.entry
                is PluginUpdateState.UpToDate -> null
            }
        }
        if (entries.isEmpty() || _uiState.value.isDownloadingAll) return

        _uiState.update { it.copy(isDownloadingAll = true, errorMsg = null) }
        viewModelScope.launch {
            var allSucceeded = true
            try {
                entries.forEach { entry ->
                    if (!_uiState.value.downloadProgressMap.containsKey(entry.id)) {
                        _uiState.update {
                            it.copy(downloadProgressMap = it.downloadProgressMap + (entry.id to 0f))
                        }
                        if (!download(entry)) allSucceeded = false
                    }
                }
            } finally {
                _uiState.update { it.copy(isDownloadingAll = false) }
            }
            checkUpdates(preserveError = !allSucceeded)
        }
    }

    private suspend fun download(entry: RemotePluginEntry): Boolean {
        val id = entry.id
        var succeeded = false
        downloader.downloadPlugin(entry) { downloadedId ->
            if (entry.type == PluginType.RULESET) {
                RulesetRegistry.evictCache(downloadedId)
            }
        }.collect { state ->
            when (state) {
                is DownloadState.Progress -> {
                    _uiState.update {
                        it.copy(downloadProgressMap = it.downloadProgressMap + (id to state.percent))
                    }
                }
                is DownloadState.Success -> {
                    succeeded = true
                    _uiState.update {
                        it.copy(downloadProgressMap = it.downloadProgressMap - id)
                    }
                }
                is DownloadState.Error -> {
                    _uiState.update {
                        it.copy(
                            downloadProgressMap = it.downloadProgressMap - id,
                            errorMsg = "下载 [$id] 失败: ${state.message}",
                        )
                    }
                }
            }
        }
        return succeeded
    }
    
    fun clearError() {
        _uiState.update { it.copy(errorMsg = null) }
    }
}
