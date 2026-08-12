package xyz.sakulik.d20.app.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.sakulik.d20.app.data.model.ConversationMemoryPolicy
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
    var showDebugDiceTesting by remember { mutableStateOf(true) }
    var pendingNavigation by remember { mutableStateOf<(() -> Unit)?>(null) }
    val navigateAfterDebugCleanup: (() -> Unit) -> Unit = { navigate ->
        if (pendingNavigation == null) {
            pendingNavigation = navigate
            showDebugDiceTesting = false
        }
    }

    LaunchedEffect(showDebugDiceTesting, pendingNavigation) {
        if (!showDebugDiceTesting) {
            withFrameNanos { }
            pendingNavigation?.invoke()
            pendingNavigation = null
        }
    }

    BackHandler {
        navigateAfterDebugCleanup(onBack)
    }

    CollectEvent(viewModel.uiEvent) { event ->
        when (event) {
            is SettingsUiEvent.Back -> navigateAfterDebugCleanup(onBack)
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
                    IconButton(onClick = { navigateAfterDebugCleanup(onBack) }) {
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
                        Text(
                            text = "近期原文回合",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${uiState.maxHistoryTurns} 轮 · 约 ${ConversationMemoryPolicy.recentCharacterBudget(uiState.maxHistoryTurns) / 1000} 千字",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1.4f)
                        )
                    }

                    Slider(
                        value = uiState.maxHistoryTurns.toFloat(),
                        onValueChange = { viewModel.onMaxHistoryTurnsChange(it.toInt()) },
                        valueRange = ConversationMemoryPolicy.MIN_RECENT_TURNS.toFloat()..
                            ConversationMemoryPolicy.MAX_RECENT_TURNS.toFloat(),
                        steps = 9
                    )

                    Text(
                        "这里只控制逐字发送给模型的近期完整回合。较早对话不会删除，而会进入本地提取式摘要。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )

                    HorizontalDivider()

                    MemoryFeatureRow(
                        label = "较早对话",
                        value = "自动摘要 · 最多 ${ConversationMemoryPolicy.SUMMARY_CHARACTER_BUDGET / 1000} 千字"
                    )
                    MemoryFeatureRow(
                        label = "世界书",
                        value = "最多 ${ConversationMemoryPolicy.LORE_MAX_ENTRIES} 条 · ${ConversationMemoryPolicy.LORE_CHARACTER_BUDGET / 1000} 千字"
                    )
                    MemoryFeatureRow(
                        label = "聊天原文",
                        value = "本地数据库完整保留"
                    )

                    Text(
                        "数值越大，近期细节越完整，但请求更慢且消耗更多 Token；摘要和世界书会在字符预算内自动补充长期信息。",
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
                        onClick = { navigateAfterDebugCleanup(onNavigateToUpdater) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("规则包更新中心")
                    }
                }
            }

            ReleaseAppUpdateSection()

            if (showDebugDiceTesting) {
                DebugDiceTestingSection()
            }

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

@Composable
private fun MemoryFeatureRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.6f)
        )
    }
}

