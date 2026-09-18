package com.example.novelreader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/* ===================================================================
 *  设计令牌（Design Tokens）
 *  原来 setContent 里是裸 MaterialTheme{}，等于用 Material 默认紫。
 *  这里补一套完整的 seed 色板 + 字阶，App 才第一次真正"有设计"。
 * =================================================================== */
object Ink {
    // —— 第 29 批：全站配色重构 ——
    // 旧版是 Material 默认紫(0xFF7C4DFF) + 靛蓝(0xFF3F51B5 / 0xFF283593)，
    // Hero 渐变终点更是沉闷的靛蓝 0xFF3949AB，观感偏"办公软件蓝"。
    // 现统一收敛为「紫罗兰 → 品紫」色系：任何一个令牌里都不再出现靛蓝。
    val Violet = Color(0xFF6C4DF6)
    val VioletDeep = Color(0xFF5B3FE0)
    val VioletSoft = Color(0xFFEEE9FF)
    val Indigo = Color(0xFF7B5CF0)
    val IndigoDeep = Color(0xFF4B32C4)
    val Magenta = Color(0xFF9B2FD6)
    val Teal = Color(0xFF00897B)
    val Amber = Color(0xFFFFA726)

    val LightBg = Color(0xFFF6F5FA)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceVariant = Color(0xFFECEAF4)
    val LightOutline = Color(0xFFD8D4E4)
    val LightText = Color(0xFF1B1A21)
    val LightSubText = Color(0xFF6E6A7C)

    val DarkBg = Color(0xFF0E0E13)
    val DarkSurface = Color(0xFF17171F)
    val DarkSurfaceVariant = Color(0xFF23232E)
    val DarkOutline = Color(0xFF3C3A48)
    val DarkText = Color(0xFFE9E7F1)
    val DarkSubText = Color(0xFF9A96A8)

    /**
     * 品牌渐变：紫罗兰 → 品紫。
     * 搜索页 Hero 头与渐变搜索按钮共用同一支渐变，保证全站同色源。
     * 两端与白字的对比度都 >= 5.5:1（headlineMedium 与 bodySmall 都够用）。
     */
    val BrandGradient = listOf(Color(0xFF6C4DF6), Color(0xFF9B2FD6))
}

private val LightColors = lightColorScheme(
    // 第 29 批：primary 直接取品牌渐变起点，按钮 / 选中态 / 进度圈与 Hero 同色，
    // 全站不再出现"两个紫打架"的跳色。
    primary = Ink.Violet,
    onPrimary = Color.White,
    primaryContainer = Ink.VioletSoft,
    onPrimaryContainer = Color(0xFF2A1B5E),
    secondary = Ink.Indigo,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E3FC),
    onSecondaryContainer = Color(0xFF241A52),
    tertiary = Ink.Teal,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB2DFDB),
    onTertiaryContainer = Color(0xFF00332E),
    background = Ink.LightBg,
    onBackground = Ink.LightText,
    surface = Ink.LightSurface,
    onSurface = Ink.LightText,
    surfaceVariant = Ink.LightSurfaceVariant,
    onSurfaceVariant = Ink.LightSubText,
    outline = Ink.LightOutline,
    outlineVariant = Color(0xFFE8E5F1),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9A4FF),
    onPrimary = Color(0xFF23134A),
    primaryContainer = Color(0xFF3A2A63),
    onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Color(0xFFC79BF0),
    onSecondary = Color(0xFF33144A),
    secondaryContainer = Color(0xFF432A63),
    onSecondaryContainer = Color(0xFFEFDDFF),
    tertiary = Color(0xFF4FD1C5),
    onTertiary = Color(0xFF00312C),
    tertiaryContainer = Color(0xFF0B4A44),
    onTertiaryContainer = Color(0xFFA9F2EA),
    background = Ink.DarkBg,
    onBackground = Ink.DarkText,
    surface = Ink.DarkSurface,
    onSurface = Ink.DarkText,
    surfaceVariant = Ink.DarkSurfaceVariant,
    onSurfaceVariant = Ink.DarkSubText,
    outline = Ink.DarkOutline,
    outlineVariant = Color(0xFF2C2B37),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

/** 重新定义字阶：标题更紧、正文更松，中文小屏可读性优先。 */
private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.5.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun NovelReaderTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}

/* ===================================================================
 *  阅读器底色预设
 *  colors.xml 里本来就备了 5 套底色，之前代码只用了黑白两套。
 *  这里把它们全部接上，并给每套配好正文色 / 次要色 / 分隔线 / 面板底色。
 * =================================================================== */
@Immutable
data class ReaderPalette(
    val id: String,
    val label: String,
    val bg: Color,
    val fg: Color,
    val sub: Color,
    val divider: Color,
    val panel: Color,
)

val ReaderPalettes: List<ReaderPalette> = listOf(
    ReaderPalette("paper", "纸张", Color(0xFFFFFFFF), Color(0xFF1A1A1A), Color(0xFF8C8C8C), Color(0xFFEBE8E2), Color(0xFFF2F0EB)),
    ReaderPalette("sepia", "米黄", Color(0xFFF5EFDC), Color(0xFF3B3527), Color(0xFF978C74), Color(0xFFE5DCC3), Color(0xFFEFE7CE)),
    ReaderPalette("parchment", "羊皮", Color(0xFFEFE3C8), Color(0xFF423A26), Color(0xFF9A8B6B), Color(0xFFDFD0AC), Color(0xFFE7D9B8)),
    ReaderPalette("gray", "灰调", Color(0xFFCFCFCF), Color(0xFF262626), Color(0xFF6F6F6F), Color(0xFFBEBEBE), Color(0xFFC4C4C4)),
    ReaderPalette("black", "夜间", Color(0xFF121212), Color(0xFFBCBCBC), Color(0xFF7C7C7C), Color(0xFF2A2A2A), Color(0xFF1C1C1C)),
)
