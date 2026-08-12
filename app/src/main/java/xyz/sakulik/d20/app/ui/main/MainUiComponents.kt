package xyz.sakulik.d20.app.ui.main

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 模块 1：打字机文本组件
 * 模拟逐字显示效果，并在内容更新时自动滚动
 */
@Composable
fun TypewriterText(
    text: String,
    speedMillis: Long = 30,
    onFinish: () -> Unit = {}
) {
    var displayedText by remember { mutableStateOf("") }
    
    // 当输入文本改变时，重置并开始打字
    LaunchedEffect(text) {
        if (text.length > displayedText.length) {
            // 只从差异处开始打字 (为了配合 SSE 流式追加)
            val start = displayedText.length
            for (i in start until text.length) {
                displayedText += text[i]
                delay(speedMillis)
            }
            onFinish()
        } else if (text.isEmpty()) {
            displayedText = ""
        }
    }

    Text(
        text = displayedText,
        style = MaterialTheme.typography.bodyLarge,
        lineHeight = 28.sp
    )
}

/**
 * 模块 2：动态属性进度条
 */
@Composable
fun StatProgressBar(
    name: String,
    value: Int,
    maxValue: Int = 100,
    color: Color = MaterialTheme.colorScheme.primary
) {
    // 进度平滑过渡动画
    val animatedProgress by animateFloatAsState(
        targetValue = value.toFloat() / maxValue,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "StatProgress"
    )

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = name, style = MaterialTheme.typography.labelMedium)
            Text(text = "$value/$maxValue", style = MaterialTheme.typography.labelSmall)
        }
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = color,
            trackColor = color.copy(alpha = 0.2f),
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}

/**
 * 模块 4：判定全屏反馈层
 */
@Composable
fun JudgmentOverlay(
    level: String?, // Success, Failure, CriticalSuccess, CriticalFailure
    onDismiss: () -> Unit
) {
    if (level == null) return

    val color = when (level) {
        "CriticalSuccess" -> Color(0xFFFFD700) // 金色
        "ExtremeSuccess" -> Color(0xFFFFD700)
        "HardSuccess" -> Color(0xFF66BB6A)
        "RegularSuccess" -> Color(0xFF4CAF50)
        "Success" -> Color(0xFF4CAF50) // 绿色
        "Failure" -> Color(0xFFF44336) // 红色
        "CriticalFailure" -> Color(0xFF8B0000) // 深红
        else -> Color.Gray
    }

    val text = when (level) {
        "CriticalSuccess" -> "极难成功 / 大成功！"
        "ExtremeSuccess" -> "极难成功"
        "HardSuccess" -> "困难成功"
        "RegularSuccess" -> "普通成功"
        "Success" -> "成功"
        "Failure" -> "失败"
        "CriticalFailure" -> "大失败！"
        else -> ""
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color.copy(alpha = 0.1f))
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = true,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut()
        ) {
            Surface(
                color = color,
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 8.dp
            ) {
                Text(
                    text = text,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                )
            }
        }
    }

    // 2秒后自动消失
    LaunchedEffect(level) {
        delay(2000)
        onDismiss()
    }
}
