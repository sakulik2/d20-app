package xyz.sakulik.d20.app.ui.archive

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.sakulik.d20.app.data.local.CampaignEntity
import java.text.SimpleDateFormat
import java.util.*

/**
 * 存档管理/剧本选择界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(
    viewModel: ArchiveViewModel,
    onNavigateToSetup: () -> Unit,
    onOpenCampaign: (CampaignEntity, Boolean) -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var campaignToDelete by remember { mutableStateOf<CampaignEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的冒险剧本", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (uiState.campaigns.isEmpty()) {
                EmptyArchiveView(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.campaigns, key = { it.id }) { campaign ->
                        CampaignCard(
                            campaign = campaign,
                            isReady = campaign.id in uiState.readyCampaignIds,
                            integrityStatus = uiState.validationResults[campaign.id],
                            onClick = {
                                onOpenCampaign(
                                    campaign,
                                    campaign.id in uiState.readyCampaignIds
                                )
                            },
                            onLongClick = { campaignToDelete = campaign }
                        )
                    }
                }
            }
            FloatingActionButton(
                onClick = onNavigateToSetup,
                containerColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
            }
        }
    }

    // 校验结果弹窗
    uiState.activeMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.clearActiveMessage() },
            title = { Text("存档完整性报告") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearActiveMessage() }) {
                    Text("确定")
                }
            }
        )
    }

    // 删除与校验操作对话框
    campaignToDelete?.let { campaign ->
        val isReady = campaign.id in uiState.readyCampaignIds
        AlertDialog(
            onDismissRequest = { campaignToDelete = null },
            title = { Text("存档操作") },
            text = { Text("选择针对剧本《${campaign.title}》的操作：") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isReady) {
                        TextButton(
                            onClick = {
                                viewModel.validateSingleCampaign(campaign.id)
                                campaignToDelete = null
                            }
                        ) {
                            Text("校验完整性")
                        }
                    }
                    TextButton(
                        onClick = {
                            viewModel.deleteCampaign(campaign.id)
                            campaignToDelete = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("删除存档")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { campaignToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun StatusBadge(status: xyz.sakulik.d20.app.domain.common.validator.IntegrityStatus) {
    val (bgColor, textColor, text) = when (status) {
        is xyz.sakulik.d20.app.domain.common.validator.IntegrityStatus.Healthy -> 
            Triple(androidx.compose.ui.graphics.Color(0xFFE8F5E9), androidx.compose.ui.graphics.Color(0xFF2E7D32), "完整")
        is xyz.sakulik.d20.app.domain.common.validator.IntegrityStatus.Repaired -> 
            Triple(androidx.compose.ui.graphics.Color(0xFFFFF8E1), androidx.compose.ui.graphics.Color(0xFFF57F17), "已修补")
        is xyz.sakulik.d20.app.domain.common.validator.IntegrityStatus.Critical -> 
            Triple(androidx.compose.ui.graphics.Color(0xFFFFEBEE), androidx.compose.ui.graphics.Color(0xFFC62828), "异常")
    }

    Surface(
        color = bgColor,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.padding(start = 6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun EmptyArchiveView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.List,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.outlineVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "荒芜的虚空中暂无故事",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.outline
        )
        Text(
            text = "点击下方按钮，谱写你的第一段英雄史诗",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CampaignCard(
    campaign: CampaignEntity,
    isReady: Boolean,
    integrityStatus: xyz.sakulik.d20.app.domain.common.validator.IntegrityStatus?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val systemIcon = if (campaign.systemId == "dnd_5e") Icons.AutoMirrored.Filled.List else Icons.Default.Star
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    val dateStr = sdf.format(Date(campaign.lastUpdated))

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(systemIcon, contentDescription = null)
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = campaign.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (!isReady) {
                        DraftBadge()
                    } else if (integrityStatus != null) {
                        StatusBadge(integrityStatus)
                    }
                }
                val context = LocalContext.current
                val rulesetTitle = remember(campaign.systemId) {
                    xyz.sakulik.d20.app.domain.rules.RulesetRegistry.getRuleset(context, campaign.systemId)?.name 
                        ?: campaign.systemId.uppercase()
                }
                Text(
                    text = if (isReady) rulesetTitle else "$rulesetTitle · 继续创建",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            Text(
                text = dateStr,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun DraftBadge() {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.padding(start = 6.dp)
    ) {
        Text(
            text = "草稿",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
