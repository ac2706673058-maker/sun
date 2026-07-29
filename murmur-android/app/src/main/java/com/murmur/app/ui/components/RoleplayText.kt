package com.murmur.app.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * 角色扮演文本的排版。
 *
 * 动作（*星号*）压暗并斜体、对白提亮，读长回复时眼睛能立刻分出"谁在说"和"在做什么"，
 * 这是把一坨纯文本变成可读小说的最低成本手段，也是市面产品普遍没做的地方。
 */
@Composable
fun RoleplayText(
    text: String,
    modifier: Modifier = Modifier,
    baseColor: Color = MaterialTheme.colorScheme.onSurface,
    actionColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    quoteColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val annotated = remember(text, baseColor, actionColor, quoteColor) {
        buildRoleplayAnnotated(text, baseColor, actionColor, quoteColor)
    }
    Text(text = annotated, modifier = modifier, style = LocalTextStyle.current)
}

fun buildRoleplayAnnotated(
    text: String,
    baseColor: Color,
    actionColor: Color,
    quoteColor: Color,
): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = text.length
    val plain = StringBuilder()

    fun flush() {
        if (plain.isNotEmpty()) {
            withStyle(SpanStyle(color = baseColor)) { append(plain.toString()) }
            plain.setLength(0)
        }
    }

    while (i < n) {
        val c = text[i]
        when {
            c == '*' && i + 1 < n && text[i + 1] == '*' -> {
                val close = text.indexOf("**", i + 2)
                if (close > i + 2) {
                    flush()
                    withStyle(SpanStyle(color = baseColor, fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, close))
                    }
                    i = close + 2
                } else {
                    plain.append(c); i++
                }
            }

            c == '*' -> {
                val close = text.indexOf('*', i + 1)
                if (close > i + 1) {
                    flush()
                    withStyle(
                        SpanStyle(
                            color = actionColor,
                            fontStyle = FontStyle.Italic,
                        )
                    ) {
                        append(text.substring(i + 1, close).trim())
                    }
                    i = close + 1
                } else {
                    plain.append(c); i++
                }
            }

            c == '"' || c == '“' || c == '「' -> {
                val closeChar = when (c) {
                    '“' -> '”'
                    '「' -> '」'
                    else -> '"'
                }
                val close = text.indexOf(closeChar, i + 1)
                if (close > i) {
                    flush()
                    withStyle(SpanStyle(color = quoteColor, fontWeight = FontWeight.Medium)) {
                        append(text.substring(i, close + 1))
                    }
                    i = close + 1
                } else {
                    plain.append(c); i++
                }
            }

            else -> {
                plain.append(c); i++
            }
        }
    }
    flush()
}
