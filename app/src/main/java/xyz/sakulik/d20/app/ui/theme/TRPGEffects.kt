package xyz.sakulik.d20.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.random.Random

/**
 * 模块 3：背景噪声纹理效果
 * 为 UI 增加微小的质感，减少纯色的廉价感
 */
@Composable
fun BackgroundNoiseLayer(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        // 使用非常细小的噪点逻辑，这里用随机矩形点模拟
        // 在生产环境中，建议使用 Shader (RuntimeShader) 实现高性能噪声
        drawIntoCanvas { _ ->
            // 简单的随机散点模拟质感
            repeat(100) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.03f),
                    radius = Random.nextFloat() * 10f,
                    center = androidx.compose.ui.geometry.Offset(
                        Random.nextFloat() * size.width,
                        Random.nextFloat() * size.height
                    )
                )
            }
        }
    }
}
