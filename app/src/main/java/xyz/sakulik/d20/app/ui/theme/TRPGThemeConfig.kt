package xyz.sakulik.d20.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * 定义主题的可选风格
 */
enum class TRPGThemeStyle {
    AUTO,      // 自动自适应规则
    MATERIAL3, // 原生 MD3 风格
    OBSIDIAN,  // 黑金 Obsidian
    FROST,     // 冰霜 Frost
    CRIMSON    // 绯红 Crimson
}

/**
 * 模块 1：定义自定义主题属性类 (TRPGColors)
 */
@Immutable
data class TRPGColors(
    val primaryAccent: Color,      // 强调色 (按钮、高亮)
    val narrativeSurface: Color,   // 叙事区主背景
    val panelBackground: Color,    // 底部面板背景
    val dividerColor: Color,      // 装饰性分割线
    val statHp: Color,            // 生命值条颜色
    val statSan: Color,           // 理智值条颜色
    val statMp: Color,            // 魔法/意念条颜色
    val judgmentSuccess: Color,   // 成功颜色
    val judgmentFailure: Color,   // 失败颜色
    val onNarrativeSurface: Color, // 叙事文字颜色
    val isDark: Boolean
)

/**
 * 模块 1：定义自定义主题属性类 (TRPGTypography)
 */
@Immutable
data class TRPGTypography(
    val narrativeBody: TextStyle, // 叙事正文字体
    val diceNumber: TextStyle,    // 骰子数字专属字体
    val titleSerif: TextStyle     // 规则标题 (衬线体)
)

val LocalTRPGColors = staticCompositionLocalOf {
    TRPGColors(
        primaryAccent = Color.Unspecified,
        narrativeSurface = Color.Unspecified,
        panelBackground = Color.Unspecified,
        dividerColor = Color.Unspecified,
        statHp = Color.Unspecified,
        statSan = Color.Unspecified,
        statMp = Color.Unspecified,
        judgmentSuccess = Color.Unspecified,
        judgmentFailure = Color.Unspecified,
        onNarrativeSurface = Color.Unspecified,
        isDark = true
    )
}

val LocalTRPGTypography = staticCompositionLocalOf {
    TRPGTypography(
        narrativeBody = TextStyle.Default,
        diceNumber = TextStyle.Default,
        titleSerif = TextStyle.Default
    )
}
