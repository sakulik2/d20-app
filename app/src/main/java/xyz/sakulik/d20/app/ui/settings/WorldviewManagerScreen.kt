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
import androidx.compose.ui.unit.dp
import xyz.sakulik.d20.app.domain.common.updater.PluginUpdateState
import xyz.sakulik.d20.app.ui.theme.TRPGTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldviewManagerScreen(
    viewModel: PluginManagerViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设定集市 (Worldview)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.checkUpdates() }, enabled = !uiState.isChecking) {
                        Icon(Icons.Default.Refresh, contentDescription = "检查更新")
                    }
                }
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
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(uiState.errorMsg!!, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
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
                                "服务器当前没有可用设定集。",
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
