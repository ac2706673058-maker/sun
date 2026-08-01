package com.murmur.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.murmur.app.ui.theme.Palette
import java.io.File

/**
 * 有图用图，没图用一组按 key 稳定取出的渐变。
 * 关键是"没配图也不能是一块灰"——大部分角色用户不会去配图，
 * 默认状态就是这个应用的实际观感。
 */
@Composable
fun CoverArt(
    imageUri: String?,
    gradientKey: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val file = remember(imageUri) { imageUri?.let { File(it) }?.takeIf { it.exists() } }
    Box(modifier) {
        if (file != null) {
            AsyncImage(
                model = file,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val colors = remember(gradientKey) { Palette.forKey(gradientKey) }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(colors))
            )
        }
    }
}

/**
 * 聊天页背景：图 + 一层自上而下加深的压暗。
 * 压暗必须够，否则浅色照片上白字直接读不了；底部比顶部更暗，
 * 因为输入区和候选项都在下面。
 */
@Composable
fun ChatBackdrop(
    backgroundUri: String?,
    gradientKey: String,
    dim: Float,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val base = MaterialTheme.colorScheme.background
    Box(modifier.fillMaxSize()) {
        CoverArt(
            imageUri = backgroundUri,
            gradientKey = gradientKey,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            base.copy(alpha = (dim - 0.12f).coerceIn(0f, 1f)),
                            base.copy(alpha = dim.coerceIn(0f, 1f)),
                            base.copy(alpha = (dim + 0.18f).coerceIn(0f, 1f)),
                        )
                    )
                )
        )
        content()
    }
}

/**
 * 首页背后缓慢流动的极光。
 *
 * 用几团大半径的径向渐变错峰漂移来做，不用 blur——
 * RenderEffect 要 API 31，而且实时模糊在中端机上很贵。
 * 半径开得足够大时，径向渐变本身就是软的，观感一样而且几乎不耗电。
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, seed: String = "") {
    val transition = rememberInfiniteTransition(label = "aurora")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(38_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "t",
    )
    val palette = remember(seed) { Palette.forKey(seed) }
    val warm = MaterialTheme.colorScheme.primary
    val cool = MaterialTheme.colorScheme.secondary

    Canvas(modifier) {
        val w = size.width
        val h = size.height

        fun blob(cx: Float, cy: Float, r: Float, color: Color, alpha: Float) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = r,
                ),
                radius = r,
                center = Offset(cx, cy),
            )
        }

        blob(w * (0.18f + 0.16f * t), h * (0.10f + 0.05f * t), w * 0.85f, warm, 0.30f)
        blob(w * (0.92f - 0.22f * t), h * (0.04f + 0.10f * t), w * 0.70f, palette[0], 0.34f)
        blob(w * (0.55f + 0.10f * (1 - t)), h * (0.30f - 0.08f * t), w * 0.60f, cool, 0.16f)
    }
}

/** 顶部/底部的渐隐，让滚动内容不要硬切在标题栏和输入栏边上。 */
@Composable
fun EdgeFade(
    modifier: Modifier = Modifier,
    fromTop: Boolean = true,
    color: Color = MaterialTheme.colorScheme.background,
) {
    val colors = if (fromTop) listOf(color, Color.Transparent)
    else listOf(Color.Transparent, color)
    Box(modifier.background(Brush.verticalGradient(colors)))
}
