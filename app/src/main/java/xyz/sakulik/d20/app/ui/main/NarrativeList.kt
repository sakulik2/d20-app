package xyz.sakulik.d20.app.ui.main

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import xyz.sakulik.d20.app.ui.main.models.ChatMessageUiModel
import xyz.sakulik.d20.app.ui.main.models.MessageType
import xyz.sakulik.d20.app.ui.theme.TRPGTheme
import xyz.sakulik.d20.app.ui.common.SensoryController

/**
 * 模块 2：沉浸式打字机渲染器 (TypewriterText)
 * 流式内容与历史消息共用标准 Markdown AST 渲染器
 */
@Composable
fun TypewriterNarrative(
    content: String,
    isStreaming: Boolean
) {
    // 模拟光标闪烁
    val infiniteTransition = rememberInfiniteTransition(label = "Cursor")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Alpha"
    )

    Box {
        Column {
            RichMarkdownText(
                modifier = Modifier.fillMaxWidth(),
                markdown = content,
                style = MaterialTheme.typography.bodyLarge.copy(
                    lineHeight = 28.sp
                ),
                color = TRPGTheme.colors.onNarrativeSurface
            )
            
            if (isStreaming) {
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .size(width = 8.dp, height = 16.dp)
                        .background(
                            color = TRPGTheme.colors.onNarrativeSurface.copy(alpha = cursorAlpha)
                        )
                )
            }
        }
    }
}

/**
 * 模块 3：解耦的消息列表组件
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NarrativeMessageList(
    messages: List<ChatMessageUiModel>,
    streamingNarrative: String,
    onDeleteMessage: (String) -> Unit,
    sensoryController: SensoryController? = null,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    var isUserScrollingManually by remember { mutableStateOf(false) }
    
    // 监听键盘状态
    val isImeVisible = WindowInsets.isImeVisible
    
    // 删除确认对话框状态
    var messageIdToDelete by remember { mutableStateOf<String?>(null) }

    // 自动滚动逻辑：当消息数量变化、流式内容变化、或者键盘弹出时触发
    LaunchedEffect(messages.size, streamingNarrative.length, isImeVisible) {
        if (!isUserScrollingManually) {
            val targetIndex = (messages.size + if (streamingNarrative.isNotEmpty()) 0 else -1).coerceAtLeast(0)
            if (targetIndex >= 0) {
                listState.animateScrollToItem(targetIndex)
            }
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            val isAtBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 >= (messages.size - 1)
            isUserScrollingManually = !isAtBottom
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                MessageItem(
                    message = message,
                    onLongClick = { 
                        sensoryController?.hapticSoftTick()
                        messageIdToDelete = message.id 
                    }
                )
            }
            
            if (streamingNarrative.isNotEmpty()) {
                item(key = "streaming_live") {
                    TypewriterNarrative(
                        content = streamingNarrative,
                        isStreaming = true
                    )
                }
            }
        }

        // 删除确认对话框
        messageIdToDelete?.let { id ->
            AlertDialog(
                onDismissRequest = { messageIdToDelete = null },
                title = { Text("删除消息") },
                text = { Text("确定要从存档中永久删除这条消息吗？此操作无法撤销。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDeleteMessage(id)
                            messageIdToDelete = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                    ) {
                        Text("删除")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { messageIdToDelete = null }) {
                        Text("取消")
                    }
                },
                containerColor = TRPGTheme.colors.panelBackground,
                titleContentColor = TRPGTheme.colors.onNarrativeSurface,
                textContentColor = TRPGTheme.colors.onNarrativeSurface.copy(alpha = 0.8f)
            )
        }

        AnimatedVisibility(
            visible = isUserScrollingManually,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            SmallFloatingActionButton(
                onClick = { isUserScrollingManually = false },
                containerColor = TRPGTheme.colors.primaryAccent.copy(alpha = 0.8f),
                contentColor = Color.White,
                shape = CircleShape
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "最新")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
    message: ChatMessageUiModel,
    onLongClick: () -> Unit
) {
    val isUser = message.type == MessageType.UserAction
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { /* 单击逻辑留空或后续扩展 */ },
                onLongClick = onLongClick
            ),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        val isDiceCard = message.type == MessageType.DiceCheck ||
            message.content.contains("检定需求") ||
            message.content.contains("判定：") ||
            (message.content.startsWith(">") && message.content.contains("检定"))

        when {
            isDiceCard -> {
                DiceCheckCard(content = message.content)
            }
            message.type == MessageType.UserAction || message.type == MessageType.SystemNotice -> {
                Surface(
                    color = TRPGTheme.colors.panelBackground.copy(alpha = 0.8f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, TRPGTheme.colors.dividerColor.copy(alpha = 0.2f)),
                    modifier = Modifier.widthIn(max = 300.dp)
                ) {
                    RichMarkdownText(
                        markdown = message.content,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        style = if (isUser) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                        color = TRPGTheme.colors.onNarrativeSurface
                    )
                }
            }
            else -> {
                RichMarkdownText(
                    modifier = Modifier.fillMaxWidth(),
                    markdown = message.content,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        lineHeight = 28.sp
                    ),
                    color = TRPGTheme.colors.onNarrativeSurface
                )
            }
        }
    }
}

@Composable
fun DiceCheckCard(
    content: String,
    modifier: Modifier = Modifier
) {
    val isRequirementNotice = content.contains("检定需求") || !content.contains("点数")

    val resultLevelCn = when {
        isRequirementNotice -> "待检定"
        content.contains("大成功") -> "大成功"
        content.contains("大失败") -> "大失败"
        content.contains("极难成功") -> "极难成功"
        content.contains("困难成功") -> "困难成功"
        content.contains("普通成功") -> "普通成功"
        content.contains("成功") -> "成功"
        else -> "失败"
    }

    val rollValueStr = if (content.contains("点数:")) {
        content.substringAfter("点数:").substringBefore(")").trim()
    } else if (content.contains("点数：")) {
        content.substringAfter("点数：").substringBefore(")").trim()
    } else {
        ""
    }

    val rawReason = content
        .removePrefix(">")
        .replace("**检定需求：", "")
        .replace("**", "")
        .substringBefore("：")
        .substringBefore(":")
        .substringBefore("(")
        .trim()

    val parsed = xyz.sakulik.d20.app.util.CheckReasonParser.parse(rawReason, if (isRequirementNotice) "" else resultLevelCn, rollValueStr)

    val isCriticalSuccess = resultLevelCn == "大成功"
    val isSuccess = resultLevelCn in setOf("成功", "普通成功", "困难成功", "极难成功")
    val isFailure = resultLevelCn == "失败"
    val isCriticalFailure = resultLevelCn == "大失败"

    val accentColor = when {
        isRequirementNotice -> Color(0xFF0284C7)
        isCriticalSuccess -> Color(0xFF2E7D32)
        isSuccess -> Color(0xFF388E3C)
        isCriticalFailure -> Color(0xFFB71C1C)
        isFailure -> Color(0xFFD32F2F)
        else -> Color(0xFFD32F2F)
    }

    val title = parsed.displayTitle
    val detail = if (isRequirementNotice) {
        if (content.contains("(")) "(" + content.substringAfter("(").trim() else "规则: 1d20"
    } else {
        val targetPart = when {
            content.contains("DC") -> "DC " + content.substringAfter("DC").substringBefore(")").trim()
            content.contains("目标值") -> "目标值 " + content.substringAfter("目标值").substringBefore(")").trim()
            else -> ""
        }
        val rollDetail = listOf(
            if (rollValueStr.isNotBlank()) "点数: $rollValueStr" else null,
            targetPart.ifBlank { null }
        ).filterNotNull().joinToString(", ")

        if (rollDetail.isNotBlank()) "判定：$resultLevelCn ($rollDetail)" else "判定：$resultLevelCn"
    }

    Surface(
        color = TRPGTheme.colors.panelBackground.copy(alpha = 0.95f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.4f)),
        shadowElevation = 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TRPGTheme.colors.onNarrativeSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TRPGTheme.colors.onNarrativeSurface.copy(alpha = 0.75f)
                )
            }

            Surface(
                color = accentColor.copy(alpha = 0.12f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, accentColor)
            ) {
                Text(
                    text = resultLevelCn,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
            }
        }
    }
}
