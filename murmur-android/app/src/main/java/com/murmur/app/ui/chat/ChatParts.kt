package com.murmur.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.murmur.app.data.db.MessageEntity
import com.murmur.app.engine.BudgetReport

@Composable
fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    isGenerating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onNudge: () -> Unit,
    onImpersonate: () -> Unit,
    onContinue: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Default.Add, "更多动作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("让 TA 继续说") },
                        leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                        onClick = { menuOpen = false; onNudge() },
                    )
                    DropdownMenuItem(
                        text = { Text("接着上一条写下去") },
                        leadingIcon = { Icon(Icons.Default.AutoAwesome, null) },
                        onClick = { menuOpen = false; onContinue() },
                    )
                    DropdownMenuItem(
                        text = { Text("替我想一句") },
                        leadingIcon = { Icon(Icons.Default.Psychology, null) },
                        onClick = { menuOpen = false; onImpersonate() },
                    )
                }
            }

            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp, max = 150.dp),
                placeholder = { Text("说点什么…", style = MaterialTheme.typography.bodyMedium) },
                shape = RoundedCornerShape(24.dp),
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
            )

            Spacer(Modifier.width(8.dp))

            FilledIconButton(
                onClick = if (isGenerating) onStop else onSend,
                enabled = isGenerating || value.isNotBlank(),
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(
                    if (isGenerating) Icons.Default.Stop else Icons.Default.Send,
                    if (isGenerating) "停止" else "发送",
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionsSheet(
    message: MessageEntity,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onRegenerate: () -> Unit,
    onContinue: () -> Unit,
    onDelete: () -> Unit,
    onDeleteFromHere: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleHide: () -> Unit,
    onBranch: () -> Unit,
    onDeleteSwipe: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            SheetRow(Icons.Default.ContentCopy, "复制") {
                clipboard.setText(AnnotatedString(message.content)); onDismiss()
            }
            SheetRow(Icons.Default.Edit, "编辑", onClick = onEdit)
            if (message.role == "assistant") {
                SheetRow(Icons.Default.Refresh, "重新生成（保留当前为候选）", onClick = onRegenerate)
                SheetRow(Icons.Default.AutoAwesome, "接着往下写", onClick = onContinue)
                if (message.swipes.size > 1) {
                    SheetRow(Icons.Default.DeleteSweep, "删掉当前这个候选", onClick = onDeleteSwipe)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            SheetRow(
                Icons.Default.PushPin,
                if (message.pinned) "取消固定" else "固定在上下文里（永不被裁掉）",
                onClick = onTogglePin,
            )
            SheetRow(
                Icons.Default.VisibilityOff,
                if (message.hidden) "重新纳入上下文" else "从上下文中隐藏（保留显示）",
                onClick = onToggleHide,
            )
            SheetRow(Icons.AutoMirrored.Filled.CallSplit, "从这里分叉出新对话", onClick = onBranch)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            SheetRow(Icons.Default.Delete, "删除这条", destructive = true, onClick = onDelete)
            SheetRow(
                Icons.Default.DeleteSweep, "删除这条及之后所有",
                destructive = true, onClick = onDeleteFromHere,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatMenuSheet(
    onDismiss: () -> Unit,
    onOpenCharacter: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenContext: () -> Unit,
    onOpenChatList: () -> Unit,
    onNewChat: () -> Unit,
    contextReport: BudgetReport?,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            contextReport?.let { r ->
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("上下文占用", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "${r.used} / ${r.limit} tokens",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (r.used.toFloat() / r.limit.coerceAtLeast(1)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "历史 ${r.historyIncluded} 条在窗口内" +
                            (if (r.historyDropped > 0) "，${r.historyDropped} 条已归档进梗概" else "") +
                            (if (r.memoriesUsed > 0) "　·　记忆 ${r.memoriesUsed} 条" else ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
            }
            SheetRow(Icons.Default.Person, "角色资料 / 编辑人设", onClick = onOpenCharacter)
            SheetRow(Icons.Default.Psychology, "TA 记住的事", onClick = onOpenMemory)
            SheetRow(Icons.Default.Insights, "看看这轮到底发了什么给模型", onClick = onOpenContext)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            SheetRow(Icons.Default.Forum, "切换 / 管理对话", onClick = onOpenChatList)
            SheetRow(Icons.Default.Add, "和 TA 重新开一段", onClick = onNewChat)
        }
    }
}

@Composable
private fun SheetRow(
    icon: ImageVector,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = color)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
fun EditMessageDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑这条消息") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 380.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun PromptPreviewDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("实际发送给模型的内容") },
        text = {
            SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("好") } },
    )
}
