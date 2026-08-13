package xyz.sakulik.d20.app.ui.chat

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.sakulik.d20.app.data.model.ChatMessage
import xyz.sakulik.d20.app.data.model.GameEvent
import xyz.sakulik.d20.app.data.model.StreamState
import xyz.sakulik.d20.app.data.repository.LlmRepository
import xyz.sakulik.d20.app.ui.base.BaseViewModel
import xyz.sakulik.d20.app.ui.base.UiEvent
import xyz.sakulik.d20.app.ui.base.UiState

/**
 * 示例应用：如何在 ViewModel 中调用流式 Repository
 */
data class ChatUiState(
    val narrative: String = "",
    val isRolling: Boolean = false,
    val error: String? = null
) : UiState

sealed class ChatUiEvent : UiEvent {
    data class ExecuteGameEvents(val events: List<GameEvent>) : ChatUiEvent()
}

class ChatViewModel(
    private val repository: LlmRepository
) : BaseViewModel<ChatUiState, ChatUiEvent>(ChatUiState()) {

    fun sendPrompt(prompt: String) {
        val messages = listOf(ChatMessage(role = "user", content = prompt))
        
        viewModelScope.launch {
            // 清空旧描述，开始流式接收
            updateState { it.copy(narrative = "", error = null) }
            
            repository.chatStream("https://api.openai.com", messages).collect { state ->
                when (state) {
                    is StreamState.TextChunk -> {
                        // 收到文本碎片后立即追加到 UI 状态，实现打字机效果。
                        updateState { it.copy(narrative = it.narrative + state.delta) }
                    }
                    is StreamState.PreviewReplacement -> {
                        updateState { it.copy(narrative = state.narrative) }
                    }
                    is StreamState.Completed -> {
                        // 流结束，收到最终的游戏指令
                        sendEvent(ChatUiEvent.ExecuteGameEvents(state.response.gameEvents))
                    }
                    is StreamState.Error -> {
                        updateState { it.copy(error = state.throwable.message) }
                    }
                }
            }
        }
    }
}
