package xyz.sakulik.d20.app.ui.base

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import androidx.compose.ui.platform.LocalLifecycleOwner

/**
 * Compose 端事件收集助手
 * 专门用于在 UI 层监听 ViewModel 发出的一次性 [UiEvent]
 * 内部结合 LaunchedEffect 和 repeatOnLifecycle 确保生命周期安全
 */
@Composable
fun <E : UiEvent> CollectEvent(
    eventFlow: Flow<E>,
    onEvent: (E) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    
    LaunchedEffect(eventFlow, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            eventFlow.collect { event ->
                onEvent(event)
            }
        }
    }
}
