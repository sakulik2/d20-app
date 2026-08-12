package xyz.sakulik.d20.app.ui.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.sakulik.d20.app.ui.base.CollectEvent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onNavigateToUpdater: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()

    CollectEvent(viewModel.uiEvent) { event ->
        when (event) {
            is SettingsUiEvent.Back -> onBack()
        }
    }

    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) {
            snackbarHostState.showSnackbar("设置已保存")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 分类 1: 接口设置
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "接口设置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    OutlinedTextField(
                        value = uiState.apiKey,
                        onValueChange = { viewModel.onApiKeyChange(it) },
                        label = { Text("LLM API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (uiState.isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { viewModel.togglePasswordVisibility() }) {
                                Icon(Icons.Default.Lock, contentDescription = null)
                            }
                        },
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = uiState.baseUrl,
                        onValueChange = { viewModel.onBaseUrlChange(it) },
                        label = { Text("API 接口地址") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = uiState.model,
                        onValueChange = { viewModel.onModelChange(it) },
                        label = { Text("AI 模型名称") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    var protocolExpanded by remember { mutableStateOf(false) }
                    val protocols = listOf(
                        "DEFAULT" to "默认",
                        "ANTHROPIC" to "Anthropic Messages",
                        "RESPONSES" to "OpenAI Responses",
                        "CHAT_COMPLETIONS" to "OpenAI Chat Completions"
                    )
                    val currentLabel = protocols.find { it.first == uiState.apiProtocol }?.second ?: "默认"

                    ExposedDropdownMenuBox(
                        expanded = protocolExpanded,
                        onExpandedChange = { protocolExpanded = !protocolExpanded }
                    ) {
                        OutlinedTextField(
                            value = currentLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("API 协议手动覆盖") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = protocolExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = protocolExpanded,
                            onDismissRequest = { protocolExpanded = false }
                        ) {
                            protocols.forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        viewModel.onApiProtocolChange(key)
                                        protocolExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 分类 2: 跑团记忆
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "跑团记忆",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("上下文历史轮数", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${uiState.maxHistoryTurns} 轮",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    Slider(
                        value = uiState.maxHistoryTurns.toFloat(),
                        onValueChange = { viewModel.onMaxHistoryTurnsChange(it.toInt()) },
                        valueRange = 4f..20f,
                        steps = 15
                    )

                    Text(
                        "限制发送给 AI 的历史对话轮数。数值越小响应越快、越省 Token；配合本地世界书 RAG 自动注入，不影响剧情连贯性。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }

            // 分类 3: 规则扩展
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "规则扩展",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    OutlinedButton(
                        onClick = onNavigateToUpdater,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("规则包更新中心")
                    }
                }
            }

            DebugDiceTestingSection()

            if (uiState.error != null) {
                Text(
                    text = uiState.error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.saveSettings() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("保存修改", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

