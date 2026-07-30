package com.murmur.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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
