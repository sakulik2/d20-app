package xyz.sakulik.d20.app.ui.main.models

import java.util.UUID

/**
 * 模块 1：增强型消息模型 (UI 层使用)
 */
data class ChatMessageUiModel(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val type: MessageType,
    val isStreaming: Boolean = false
)

enum class MessageType {
    Narrative,    // AI 叙事
    UserAction,   // 玩家行动
    SystemNotice, // 系统提示
    DiceCheck     // 掷骰检定卡片
}
