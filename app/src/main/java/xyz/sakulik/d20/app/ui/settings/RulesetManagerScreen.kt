package xyz.sakulik.d20.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import xyz.sakulik.d20.app.domain.common.updater.PluginUpdateState
import xyz.sakulik.d20.app.domain.common.updater.RemotePluginEntry
import xyz.sakulik.d20.app.ui.theme.TRPGTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesetManagerScreen(
    viewModel: PluginManagerViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("引擎规则市场") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.checkUpdates() }, enabled = !uiState.isChecking) {
                        Icon(Icons.Default.Refresh, contentDescription = "检查更新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingVals ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingVals)) {
            if (uiState.isChecking) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (uiState.errorMsg != null) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(
                                        uiState.errorMsg!!,
                                        modifier = Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    TextButton(onClick = viewModel::clearError) {
                                        Text("关闭")
                                    }
                                }
                            }
                        }
                    }

                    if (uiState.errorMsg == null && uiState.plugins.isEmpty()) {
                        item {
                            Text(
                                "服务器当前没有可用规则包。规则包只能由开发者发布并从此受控源安装。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    items(uiState.plugins) { state ->
                        PluginItemCard(
                            state = state,
                            progress = uiState.downloadProgressMap[when(state) {
                                is PluginUpdateState.UpToDate -> state.id
                                is PluginUpdateState.UpdateAvailable -> state.id
                                is PluginUpdateState.NotInstalled -> state.id
                            }],
                            onDownload = { entry ->
                                viewModel.downloadPlugin(entry)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PluginItemCard(
    state: PluginUpdateState,
    progress: Float?,
    onDownload: (entry: RemotePluginEntry) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                when (state) {
                    is PluginUpdateState.UpToDate -> {
                        Text(state.id, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text("当前版本: ${state.currentVersion}", style = MaterialTheme.typography.bodySmall)
                    }
                    is PluginUpdateState.UpdateAvailable -> {
                        Text(state.entry.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(state.entry.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("有新版本 -> ${state.newVersion}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    }
                    is PluginUpdateState.NotInstalled -> {
                        Text(state.entry.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(state.entry.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("云端可用: ${state.entry.version}", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // 右侧按钮/状态区域
            if (progress != null) {
                // 下载中
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(36.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            } else {
                when (state) {
                    is PluginUpdateState.UpToDate -> {
                        Icon(Icons.Default.CheckCircle, contentDescription = "已最新", tint = MaterialTheme.colorScheme.primary)
                    }
                    is PluginUpdateState.UpdateAvailable -> {
                        Button(onClick = { onDownload(state.entry) }) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("更新")
                        }
                    }
                    is PluginUpdateState.NotInstalled -> {
                        OutlinedButton(onClick = { onDownload(state.entry) }) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("安装")
                        }
                    }
                }
            }
        }
    }
}
