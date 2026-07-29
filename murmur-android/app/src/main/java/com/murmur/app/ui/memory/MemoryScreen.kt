package com.murmur.app.ui.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.murmur.app.data.db.MemoryEntity
import com.murmur.app.data.db.SceneState
import com.murmur.app.ui.AppViewModel
import com.murmur.app.ui.settings.SettingIntSlider
import com.murmur.app.ui.settings.SettingSection
import com.murmur.app.ui.settings.SettingSwitch
import com.murmur.app.ui.settings.SettingText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(vm: AppViewModel, chatId: String, onBack: () -> Unit) {
    val memories by vm.observeMemories(chatId).collectAsStateWithLifecycle(emptyList())
    val chat by vm.observeChat(chatId).collectAsStateWithLifecycle(null)
    var editing by remember { mutableStateOf<MemoryEntity?>(null) }
    var editingSummary by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("TA 记住的事") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = vm.newMemory(chatId) }) {
                Icon(Icons.Default.Add, "手动加一条")
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 100.dp, top = 8.dp),
        ) {
            item {
                val scene = SceneState.parse(chat?.sceneStateJson ?: "")
                SettingSection(
                    "此刻的场景",
                    subtitle = if (scene.isEmpty) "还没有记录" else scene.location.ifBlank { scene.mood },
                    initiallyExpanded = !scene.isEmpty,
                ) {
                    if (scene.isEmpty) {
                        Text(
                            "聊几轮之后，这里会自动记录你们现在在哪、什么时候、TA 穿着什么、什么情绪、还有哪些没说完的话。" +
                                "这些会在每次生成时贴到对话末尾，防止聊着聊着场景就飘了。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        SceneRow("地点", scene.location)
                        SceneRow("时间", scene.time)
                        SceneRow("姿态", scene.pose)
                        SceneRow("穿着", scene.outfit)
                        SceneRow("情绪", scene.mood)
                        if (scene.threads.isNotEmpty()) {
                            SceneRow("悬念", scene.threads.joinToString("；"))
                        }
                    }
                }
            }

            item {
                SettingSection(
                    "前情梗概",
                    subtitle = if (chat?.rollingSummary.isNullOrBlank()) "还没有归档内容"
                    else "${chat?.rollingSummary?.length ?: 0} 字",
                ) {
                    Text(
                        chat?.rollingSummary?.ifBlank { "对话还不够长。等更早的消息被挤出上下文窗口，它们会被压缩成梗概存在这里，而不是直接丢掉。" }
                            ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { editingSummary = true }) { Text("手动编辑") }
                }
            }

            item {
                Text(
                    "长期记忆　${memories.size} 条",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 22.dp, top = 20.dp, bottom = 8.dp),
                )
            }

            if (memories.isEmpty()) {
                item {
                    Text(
                        "还没有。聊上几轮之后，重要的事实（名字、约定、喜好、经历）会被自动抽出来存在这里，" +
                            "并且在相关话题出现时自动回填到上下文。你也可以手动加。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 22.dp),
                    )
                }
            }

            items(memories, key = { it.id }) { m ->
                MemoryRow(m, onClick = { editing = m }, onTogglePin = {
                    vm.saveMemory(m.copy(pinned = !m.pinned))
                })
            }
        }
    }

    editing?.let { m ->
        MemoryEditDialog(
            memory = m,
            onDismiss = { editing = null },
            onSave = { vm.saveMemory(it); editing = null },
            onDelete = { vm.deleteMemory(m.id); editing = null },
        )
    }

    if (editingSummary) {
        var text by remember { mutableStateOf(chat?.rollingSummary.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editingSummary = false },
            title = { Text("前情梗概") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateSummary(chatId, text)
                    editingSummary = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingSummary = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SceneRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(48.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MemoryRow(m: MemoryEntity, onClick: () -> Unit, onTogglePin: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    m.text,
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = if (!m.enabled) TextDecoration.LineThrough else null,
                    color = if (!m.enabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${m.category} · 重要度 ${m.importance}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onTogglePin) {
                Icon(
                    Icons.Default.PushPin, "固定",
                    tint = if (m.pinned) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
        }
    }
}

@Composable
private fun MemoryEditDialog(
    memory: MemoryEntity,
    onDismiss: () -> Unit,
    onSave: (MemoryEntity) -> Unit,
    onDelete: () -> Unit,
) {
    var m by remember { mutableStateOf(memory) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记忆") },
        text = {
            Column {
                SettingText("内容", m.text, { m = m.copy(text = it) }, singleLine = false, minHeight = 100)
                SettingText("分类", m.category, { m = m.copy(category = it) })
                SettingIntSlider("重要度", m.importance, 1..5) { m = m.copy(importance = it) }
                SettingSwitch("启用", null, m.enabled) { m = m.copy(enabled = it) }
                SettingSwitch("固定（永远优先进上下文）", null, m.pinned) { m = m.copy(pinned = it) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(m) }) { Text("保存") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
