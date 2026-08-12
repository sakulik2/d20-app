package xyz.sakulik.d20.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 模块 2：规则集与主题风格的映射表 (Theme Palette Picker)
 */
@Composable
fun provideTRPGTheme(rulesetId: String, style: TRPGThemeStyle = TRPGThemeStyle.AUTO): TRPGColors {
    // 基础 MD3 色彩 fallback
    val m3Colors = TRPGColors(
        primaryAccent = MaterialTheme.colorScheme.primary,
        narrativeSurface = MaterialTheme.colorScheme.surface,
        panelBackground = MaterialTheme.colorScheme.surfaceVariant,
        dividerColor = MaterialTheme.colorScheme.outlineVariant,
        statHp = Color(0xFFE57373),
        statSan = Color(0xFF64B5F6),
        statMp = Color(0xFF9575CD),
        judgmentSuccess = Color(0xFF81C784),
        judgmentFailure = Color(0xFFE57373),
        onNarrativeSurface = MaterialTheme.colorScheme.onSurface,
        isDark = isSystemInDarkTheme()
    )

    if (style == TRPGThemeStyle.MATERIAL3) return m3Colors

    // 如果是指定了特殊风格，则直接返回风格配色
    val chosenColors = when (style) {
        TRPGThemeStyle.OBSIDIAN -> TRPGColors(
            primaryAccent = Color(0xFFD4AF37),      // 曜金
            narrativeSurface = Color(0xFF0F0F0F),  // 极黑
            panelBackground = Color(0xFF1C1C1C),   // 深炭
            dividerColor = Color(0xFF333333),      // 灰
            statHp = Color(0xFFCF6679),
            statSan = Color(0xFF03DAC6),
            statMp = Color(0xFFBB86FC),
            judgmentSuccess = Color(0xFF03DAC6),
            judgmentFailure = Color(0xFFCF6679),
            onNarrativeSurface = Color(0xFFE0E0E0),
            isDark = true
        )
        TRPGThemeStyle.FROST -> TRPGColors(
            primaryAccent = Color(0xFF00E5FF),      // 荧光青
            narrativeSurface = Color(0xFF001122),  // 冰洋深蓝
            panelBackground = Color(0xFF001A33),   // 冰晶蓝
            dividerColor = Color(0xFF003D66),      // 蓝晶
            statHp = Color(0xFFFF5252),
            statSan = Color(0xFF00E676),
            statMp = Color(0xFF2979FF),
            judgmentSuccess = Color(0xFF00E676),
            judgmentFailure = Color(0xFFFF5252),
            onNarrativeSurface = Color(0xFFE3F2FD),
            isDark = true
        )
        TRPGThemeStyle.CRIMSON -> TRPGColors(
            primaryAccent = Color(0xFFFF1744),      // 祭祀红
            narrativeSurface = Color(0xFF120000),  // 凝固鲜血
            panelBackground = Color(0xFF1F0000),   // 幽邃红
            dividerColor = Color(0xFF420000),      // 砖红
            statHp = Color(0xFFD50000),
            statSan = Color(0xFF651FFF),
            statMp = Color(0xFF00B0FF),
            judgmentSuccess = Color(0xFF00E676),
            judgmentFailure = Color(0xFFD50000),
            onNarrativeSurface = Color(0xFFFFEBEE),
            isDark = true
        )
        else -> null
    }

    if (chosenColors != null) return chosenColors

    // 最后才尝试根据 RulesetId 匹配 AUTO 风格
    return when (rulesetId) {
        "dnd_5e" -> TRPGColors(
            primaryAccent = Color(0xFFFFD700),      // 古金
            narrativeSurface = Color(0xFF1A120B),  // 深古卷褐
            panelBackground = Color(0xFF2C1E12),   // 木质深褐
            dividerColor = Color(0xFF8B4513),      // 鞍褐
            statHp = Color(0xFFB22222),           // 砖红
            statSan = Color(0xFF4682B4),          // 钢蓝 (力量)
            statMp = Color(0xFF9370DB),           // 适中紫
            judgmentSuccess = Color(0xFFFFD700),
            judgmentFailure = Color(0xFF8B0000),
            onNarrativeSurface = Color(0xFFE5D3B3), // 羊皮纸淡黄
            isDark = true
        )
        "coc_7e" -> TRPGColors(
            primaryAccent = Color(0xFF00FF41),      // 极客/粘液绿
            narrativeSurface = Color(0xFF0D0D0D),  // 深海黑
            panelBackground = Color(0xFF161B22),   // 冰冷工业灰
            dividerColor = Color(0xFF30363D),      // 钢灰
            statHp = Color(0xFF2EA043),           // 医疗绿
            statSan = Color(0xFF8B5CF6),          // 理智紫
            statMp = Color(0xFF1D4ED8),           // 意志蓝
            judgmentSuccess = Color(0xFF00FF41),
            judgmentFailure = Color(0xFF8B0000),
            onNarrativeSurface = Color(0xFFD1D5DB), // 浅灰
            isDark = true
        )
        else -> m3Colors
    }
}

/**
 * 模块 3：实现自定义 Theme Provider 组件 (带动画平滑过渡)
 */
@Composable
fun TRPGTheme(
    rulesetId: String,
    style: TRPGThemeStyle = TRPGThemeStyle.AUTO,
    content: @Composable () -> Unit
) {
    val targetColors = provideTRPGTheme(rulesetId, style)
    
    // 性能优化：首屏加载时禁用动画，避免与导航动画冲突
    var skipAnimation by remember(rulesetId, style) { mutableStateOf(true) }
    LaunchedEffect(rulesetId, style) {
        kotlinx.coroutines.delay(100) // 等待首帧渲染
        skipAnimation = false
    }

    val animationSpec = if (skipAnimation) snap<Color>() else tween<Color>(500)
    
    // 平滑过渡动画 (关键逻辑：animateColorAsState)
    val animatedPrimary = animateColorAsState(targetColors.primaryAccent, animationSpec, label = "primary")
    val animatedSurface = animateColorAsState(targetColors.narrativeSurface, animationSpec, label = "surface")
    val animatedPanel = animateColorAsState(targetColors.panelBackground, animationSpec, label = "panel")
    val animatedDivider = animateColorAsState(targetColors.dividerColor, animationSpec, label = "divider")
    
    val animatedColors = targetColors.copy(
        primaryAccent = animatedPrimary.value,
        narrativeSurface = animatedSurface.value,
        panelBackground = animatedPanel.value,
        dividerColor = animatedDivider.value,
        statHp = animateColorAsState(targetColors.statHp, animationSpec).value,
        statSan = animateColorAsState(targetColors.statSan, animationSpec).value,
        statMp = animateColorAsState(targetColors.statMp, animationSpec).value,
        judgmentSuccess = animateColorAsState(targetColors.judgmentSuccess, animationSpec).value,
        judgmentFailure = animateColorAsState(targetColors.judgmentFailure, animationSpec).value
    )

    // 构建字体系统
    val typography = TRPGTypography(
        narrativeBody = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = if (rulesetId == "dnd_5e") FontFamily.Serif else FontFamily.Default,
            lineHeight = 28.sp
        ),
        diceNumber = MaterialTheme.typography.displayLarge.copy(
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace
        ),
        titleSerif = MaterialTheme.typography.headlineMedium.copy(
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold
        )
    )

    // 同步 Material3 基础主题
    val colorScheme = if (animatedColors.isDark) darkColorScheme() else lightColorScheme()
    
    CompositionLocalProvider(
        LocalTRPGColors provides animatedColors,
        LocalTRPGTypography provides typography
    ) {
        MaterialTheme(
            colorScheme = colorScheme.copy(
                primary = animatedColors.primaryAccent,
                surface = animatedColors.narrativeSurface,
                onSurface = animatedColors.onNarrativeSurface
            ),
            content = content
        )
    }
}

/**
 * 便捷访问单例
 */
object TRPGTheme {
    val colors: TRPGColors
        @Composable
        @ReadOnlyComposable
        get() = LocalTRPGColors.current

    val typography: TRPGTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalTRPGTypography.current
}
