package xyz.sakulik.d20.app.ui.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    viewModel: SetupViewModel,
    onNavigateToWorldBuilder: (String, String) -> Unit,
    onNavigateToSettings: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    var campaignTitle by remember { mutableStateOf("") }

    // 处理一次性导航事件
    xyz.sakulik.d20.app.ui.base.CollectEvent(viewModel.uiEvent) { event ->
        when (event) {
            is SetupUiEvent.NavigateToWorldBuilder -> onNavigateToWorldBuilder(event.campaignId, event.rulesetId)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.Start
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        
        Text(
            text = "初始化您的",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.secondary
        )
        Text(
            text = "跑团引擎",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 40.sp
            ),
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (uiState.showApiConfig) {
            // API Key 输入框
            OutlinedTextField(
                value = uiState.apiKey,
                onValueChange = { viewModel.onApiKeyChange(it) },
                label = { Text("API密钥") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (uiState.isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    val image = Icons.Default.Lock
                    IconButton(onClick = { viewModel.onPasswordVisibilityToggle() }) {
                        Icon(imageVector = image, contentDescription = "切换可见性")
                    }
                },
                placeholder = { Text("sk-...") },
                singleLine = true
            )
            
            Spacer(modifier = Modifier.height(16.dp))

            // API Base URL 输入框
            OutlinedTextField(
                value = uiState.baseUrl,
                onValueChange = { viewModel.onBaseUrlChange(it) },
                label = { Text("API接口") },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("https://api.openai.com") },
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // AI Model 输入框
            OutlinedTextField(
                value = uiState.model,
                onValueChange = { viewModel.onModelChange(it) },
                label = { Text("模型名称") },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("gpt-5.5") },
                singleLine = true
            )

            Text(
                text = "密钥将加密保存在本地，App 绝不会将其上传到任何第三方服务器。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                color = MaterialTheme.colorScheme.outline
            )

            Spacer(modifier = Modifier.height(24.dp))
        } else {
            // 已配置提示
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Lock, 
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "API 已在本地安全锁定。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    if (onNavigateToSettings != null) {
                        TextButton(onClick = onNavigateToSettings) {
                            Text("修改设置")
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // 剧本标题输入
        OutlinedTextField(
            value = campaignTitle,
            onValueChange = { campaignTitle = it },
            label = { Text("剧本标题") },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("例如：迷失的矿坑") },
            singleLine = true
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "选择规则系统",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        // 核心任务：规则选择网格
        SystemSelector(
            systems = viewModel.getAvailableSystems(),
            selectedSystemId = uiState.selectedRulesetId,
            onSystemSelected = { viewModel.onSystemSelected(it) }
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (uiState.error != null) {
            Text(
                text = uiState.error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        Button(
            onClick = { viewModel.startAdventure(campaignTitle) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = !uiState.isLoading,
            shape = MaterialTheme.shapes.medium
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Text("开始冒险之旅", fontSize = 18.sp)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
