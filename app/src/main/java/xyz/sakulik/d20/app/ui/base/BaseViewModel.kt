package xyz.sakulik.d20.app.ui.base

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * 跑团应用全通用的 ViewModel 基类
 * 强制执行 UDF (单向数据流) 模式
 * 
 * @param S UI 状态类型
 * @param E UI 一次性事件类型
 * @property initialState 初始 UI 状态
 */
abstract class BaseViewModel<S : UiState, E : UiEvent>(
    initialState: S
) : ViewModel() {

    // 1. 状态 (State) - 使用 StateFlow 管理
    // StateFlow 具有粘性，适合保存“当前是什么样子”的数据（如 HP: 10）
    private val _uiState = MutableStateFlow(initialState)
    val uiState: StateFlow<S> = _uiState.asStateFlow()

    // 2. 事件 (Event) - 使用 Channel 管理
    // Channel 确保事件“只被消费一次”，适合处理逻辑完成后的“动作”（如“跳转到结算”）
    private val _uiEvent = Channel<E>(Channel.BUFFERED)
    val uiEvent: Flow<E> = _uiEvent.receiveAsFlow()

    /**
     * 更新 UI 状态 (原子操作)
     * 使用 .update 确保在并发环境下状态转换的线程安全
     */
    protected fun updateState(reducer: (S) -> S) {
        _uiState.update { reducer(it) }
    }

    /**
     * 发送一次性副作用事件
     */
    protected fun sendEvent(event: E) {
        viewModelScope.launch {
            _uiEvent.send(event)
        }
    }
}
