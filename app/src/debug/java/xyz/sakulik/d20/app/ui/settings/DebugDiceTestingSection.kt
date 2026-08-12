package xyz.sakulik.d20.app.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.sakulik.d20.app.domain.rules.dynamic.DiceType
import xyz.sakulik.d20.app.ui.common.PolyhedralDice3D
import xyz.sakulik.d20.app.ui.common.SensoryController
import kotlin.random.Random

@Composable
internal fun DebugDiceTestingSection() {
    val context = LocalContext.current
    val sensory = remember { SensoryController.getInstance(context) }
    val scope = rememberCoroutineScope()
    var selectedDice by remember { mutableStateOf(DiceType.D20) }
    var isRolling by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Int?>(null) }
    val modelName = when (selectedDice) {
        DiceType.D4 -> "正四面体 · 4 个三角形面"
        DiceType.D6 -> "立方体 · 6 个正方形面"
        DiceType.D8 -> "正八面体 · 8 个三角形面"
        DiceType.D10 -> "五方偏方面体 · 10 个鸢形面"
        DiceType.D12 -> "正十二面体 · 12 个五边形面"
        DiceType.D20 -> "正二十面体 · 20 个三角形面"
        DiceType.D100 -> "两枚十面骰 · 十位与个位"
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "3D 骰子调试实验室",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "仅 Debug 构建可见",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = selectedDice.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DiceType.values().forEach { dice ->
                    FilterChip(
                        selected = selectedDice == dice,
                        onClick = {
                            if (isRolling) return@FilterChip
                            selectedDice = dice
                            testResult = null
                        },
                        enabled = !isRolling,
                        label = { Text(dice.name, fontSize = 11.sp) }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp),
                contentAlignment = Alignment.Center
            ) {
                key(selectedDice) {
                    PolyhedralDice3D(
                        diceType = selectedDice,
                        isRolling = isRolling,
                        finalValue = testResult,
                        size = 156.dp,
                        onLandImpact = sensory::hapticLandImpact
                    )
                }
            }

            Text(
                text = modelName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            testResult?.let { result ->
                val status = when (result) {
                    selectedDice.sides -> "最大值"
                    1 -> "最小值"
                    else -> "普通结果"
                }
                Text(
                    text = "结果：$result（$status） · 可拖动查看模型",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val current = testResult ?: selectedDice.sides
                        testResult = if (current <= 1) selectedDice.sides else current - 1
                    },
                    enabled = !isRolling,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (selectedDice == DiceType.D100) "上一结果" else "上一面")
                }
                OutlinedButton(
                    onClick = {
                        val current = testResult ?: selectedDice.sides
                        testResult = if (current >= selectedDice.sides) 1 else current + 1
                    },
                    enabled = !isRolling,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (selectedDice == DiceType.D100) "下一结果" else "下一面")
                }
            }

            Button(
                onClick = {
                    if (isRolling) return@Button
                    scope.launch {
                        isRolling = true
                        testResult = null
                        sensory.playSound("dice_clatter")
                        sensory.hapticDiceRolling()
                        delay(1000)
                        testResult = Random.nextInt(1, selectedDice.sides + 1)
                        isRolling = false
                    }
                },
                enabled = !isRolling,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isRolling) "掷骰中..." else "掷出 ${selectedDice.name}")
            }
        }
    }
}
