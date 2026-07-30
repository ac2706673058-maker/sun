package com.murmur.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD9A7D0),
    onPrimary = Color(0xFF3A1F38),
    primaryContainer = Color(0xFF4A2E4B),
    onPrimaryContainer = Color(0xFFF6DDF0),
    secondary = Color(0xFF9CC0CE),
    onSecondary = Color(0xFF1E323A),
    secondaryContainer = Color(0xFF2C444E),
    onSecondaryContainer = Color(0xFFD6EAF2),
    tertiary = Color(0xFFE8B08D),
    background = Color(0xFF0E0B12),
    onBackground = Color(0xFFEDE6F0),
    surface = Color(0xFF141019),
    onSurface = Color(0xFFEDE6F0),
    surfaceVariant = Color(0xFF231C2E),
    onSurfaceVariant = Color(0xFFB6A9C0),
    surfaceContainer = Color(0xFF1A1522),
    surfaceContainerHigh = Color(0xFF221B2C),
    surfaceContainerHighest = Color(0xFF2A2136),
    outline = Color(0xFF524760),
    outlineVariant = Color(0xFF322A3E),
    error = Color(0xFFE9A0A0),
    onError = Color(0xFF41151B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF7C4B84),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF6DDF0),
    onPrimaryContainer = Color(0xFF33113A),
    secondary = Color(0xFF44646F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC9E7F3),
    tertiary = Color(0xFF8A5330),
    background = Color(0xFFFDF9FC),
    onBackground = Color(0xFF1E1A22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1E1A22),
    surfaceVariant = Color(0xFFF0E7F1),
    onSurfaceVariant = Color(0xFF5E5266),
    surfaceContainer = Color(0xFFF7F0F6),
    surfaceContainerHigh = Color(0xFFF1E9F1),
    surfaceContainerHighest = Color(0xFFEBE2EC),
    outline = Color(0xFF8F8398),
    outlineVariant = Color(0xFFDDD1DF),
)

/** 中文正文的行高要给足，不然长段落读起来发闷。 */
private fun body(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontSize = size.sp,
    lineHeight = height.sp,
    fontWeight = weight,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)

private val MurmurTypography = Typography(
    displaySmall = body(30, 38, FontWeight.Medium),
    headlineMedium = body(24, 32, FontWeight.Medium),
    headlineSmall = body(20, 28, FontWeight.Medium),
    titleLarge = body(19, 26, FontWeight.Medium),
    titleMedium = body(16, 24, FontWeight.Medium),
    titleSmall = body(14, 20, FontWeight.Medium),
    bodyLarge = body(16, 27),
    bodyMedium = body(15, 25),
    bodySmall = body(13, 20),
    labelLarge = body(14, 20, FontWeight.Medium),
    labelMedium = body(12, 16, FontWeight.Medium),
    labelSmall = body(11, 15, FontWeight.Medium),
)

/**
 * 固定深色。
 *
 * 两个原因：一是这类应用整套视觉都建立在暗底 + 大图 + 半透明气泡上，浅色下背景图会糊成一片；
 * 二是跟随系统时，设置还没从 DataStore 读回来的那一小段会露出接近纯白的 background，
 * 冷启动或进程被回收后返回就是一屏白。
 */
@Composable
fun MurmurTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = MurmurTypography,
        content = content,
    )
}

object MurmurShape {
    val bubble = 20.dp
    val card = 18.dp
    val field = 14.dp
}

/**
 * 没有配图时用来撑场面的一组渐变。
 * 按角色 id 的哈希取，同一个角色每次进来颜色是稳定的。
 */
object Palette {
    private val gradients: List<List<Color>> = listOf(
        listOf(Color(0xFF6D3B6B), Color(0xFF2B1B3D)),
        listOf(Color(0xFF2F4858), Color(0xFF1B2430)),
        listOf(Color(0xFF7A4A3A), Color(0xFF2E1D22)),
        listOf(Color(0xFF3B5A54), Color(0xFF19282A)),
        listOf(Color(0xFF4A3A72), Color(0xFF1D1830)),
        listOf(Color(0xFF74405A), Color(0xFF2A1A26)),
    )

    fun forKey(key: String): List<Color> {
        if (key.isEmpty()) return gradients[0]
        val h = key.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFF }
        return gradients[h % gradients.size]
    }

    fun accentFor(key: String, fallback: Color): Color =
        if (key.isEmpty()) fallback else forKey(key).first()
}
