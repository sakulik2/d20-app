package xyz.sakulik.d20.app.ui.character

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.sakulik.d20.app.ui.base.*

/**
 * 演示：角色界面的状态
 */
data class CharacterUiState(
    val name: String = "未命名角色",
    val hp: Int = 10,
    val san: Int = 50
) : UiState

/**
 * 演示：角色界面的副作用事件
 */
sealed class CharacterUiEvent : UiEvent {
    data class ShowDiceDialog(val reason: String) : CharacterUiEvent()
    object ShowDeathWarning : CharacterUiEvent()
}

/**
 * 演示：ViewModel 实现
 */
class CharacterViewModel : BaseViewModel<CharacterUiState, CharacterUiEvent>(
    initialState = CharacterUiState()
) {
    fun takeDamage() {
        updateState { 
            val newHp = (it.hp - 2).coerceAtLeast(0)
            if (newHp == 0) sendEvent(CharacterUiEvent.ShowDeathWarning)
            it.copy(hp = newHp)
        }
    }

    fun requestSanityCheck() {
        sendEvent(CharacterUiEvent.ShowDiceDialog("需要进行理智检定"))
    }
}

/**
 * 演示：Compose UI 实现
 */
@Composable
fun CharacterDemoScreen(viewModel: CharacterViewModel) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 使用我们定义的收集器处理一次性事件
    CollectEvent(viewModel.uiEvent) { event ->
        when (event) {
            is CharacterUiEvent.ShowDiceDialog -> {
                // 在实际应用中这里可以弹出 Dialog
                println("触发掷骰弹窗: ${event.reason}")
            }
            is CharacterUiEvent.ShowDeathWarning -> {
                // 演示副作用：SnackBar
                scope.launch {
                    snackbarHostState.showSnackbar("警告：角色已陷入昏迷！")
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = state.name, style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "HP: ${state.hp}", color = MaterialTheme.colorScheme.error)
            Text(text = "SAN: ${state.san}", color = MaterialTheme.colorScheme.primary)

            Spacer(modifier = Modifier.height(24.dp))

            Button(onClick = { viewModel.takeDamage() }) {
                Text("受到 2 点伤害 (测试状态流)")
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedButton(onClick = { viewModel.requestSanityCheck() }) {
                Text("进行理智检定 (测试事件流)")
            }
        }
    }
}
