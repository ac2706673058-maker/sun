package com.murmur.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.murmur.app.ui.components.RoleplayText

/**
 * 实时候选回复。
 *
 * 交互刻意分两档：点整行 = 直接说出去（大多数时候就是想快点接下去），
 * 点右边的笔 = 填进输入框自己改（想借个开头再展开）。
 * 这两件事要是合成一个，快速推进和精细控制会互相打架。
 */
@Composable
fun SuggestionBar(
    suggestions: List<String>,
    loading: Boolean,
    enabled: Boolean,
    onPick: (String) -> Unit,
    onEdit: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = loading || suggestions.isNotEmpty()
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 3 },
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 6.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (loading) "正在想你会怎么说…" else "你可以这样说",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (!loading && suggestions.isNotEmpty()) {
                    Text(
                        "换一批",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = enabled, onClick = onRefresh)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            if (loading && suggestions.isEmpty()) {
                repeat(3) { i -> ShimmerRow(delayMs = i * 140) }
            } else {
                suggestions.forEachIndexed { i, text ->
                    SuggestionRow(
                        text = text,
                        index = i,
                        enabled = enabled,
                        onPick = { onPick(text) },
                        onEdit = { onEdit(text) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    text: String,
    index: Int,
    enabled: Boolean,
    onPick: () -> Unit,
    onEdit: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        accent.copy(alpha = 0.16f),
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                    )
                )
            )
            .border(
                width = 1.dp,
                color = accent.copy(alpha = 0.22f),
                shape = RoundedCornerShape(15.dp),
            )
            .clickable(enabled = enabled, onClick = onPick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .padding(start = 11.dp)
                .size(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent.copy(alpha = 0.55f + index * 0.12f))
        )
        RoleplayText(
            text = text,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 11.dp, vertical = 11.dp),
            baseColor = MaterialTheme.colorScheme.onSurface,
            actionColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(
            onClick = onEdit,
            enabled = enabled,
            modifier = Modifier.size(38.dp),
        ) {
            Icon(
                Icons.Default.Edit,
                "改一改再发",
                Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ShimmerRow(delayMs: Int) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val a by transition.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.62f,
        animationSpec = infiniteRepeatable(
            animation = tween(780, delayMillis = delayMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "a",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(15.dp))
            .alpha(a)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    )
}
