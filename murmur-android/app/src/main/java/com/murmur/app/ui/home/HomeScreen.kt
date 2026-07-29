package com.murmur.app.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.murmur.app.data.db.CharacterEntity
import com.murmur.app.data.db.ChatEntity
import com.murmur.app.ui.AppViewModel
import com.murmur.app.ui.components.Avatar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    vm: AppViewModel,
    onOpenChat: (String) -> Unit,
    onEditCharacter: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val characters by vm.characters.collectAsStateWithLifecycle()
    val chats by vm.chats.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var addMenu by remember { mutableStateOf(false) }
    var pressedCharacter by remember { mutableStateOf<CharacterEntity?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            vm.importCard(uri)
                .onSuccess { snackbar.showSnackbar("角色卡导入成功") }
                .onFailure { snackbar.showSnackbar(it.message ?: "导入失败") }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("絮语", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    Box {
                        IconButton(onClick = { addMenu = true }) {
                            Icon(Icons.Default.Add, "新增角色")
                        }
                        DropdownMenu(addMenu, onDismissRequest = { addMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("从零创建一个角色") },
                                leadingIcon = { Icon(Icons.Default.Add, null) },
                                onClick = {
                                    addMenu = false
                                    onEditCharacter(vm.newCharacter().id)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("导入角色卡（PNG / JSON）") },
                                leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                                onClick = {
                                    addMenu = false
                                    importLauncher.launch(arrayOf("image/png", "application/json", "*/*"))
                                },
                            )
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            val recent = chats.firstOrNull()
            if (recent != null) {
                val char = characters.firstOrNull { it.id == recent.characterId }
                item {
                    ContinueCard(
                        chat = recent,
                        character = char,
                        onClick = { onOpenChat(recent.id) },
                    )
                }
            }

            item {
                SectionHeader("角色")
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(characters, key = { it.id }) { c ->
                        CharacterCard(
                            character = c,
                            onClick = {
                                scope.launch { onOpenChat(vm.openOrCreateChat(c.id)) }
                            },
                            onLongClick = { pressedCharacter = c },
                        )
                    }
                }
            }

            if (chats.size > 1) {
                item { SectionHeader("最近的对话") }
                items(chats, key = { it.id }) { chat ->
                    val char = characters.firstOrNull { it.id == chat.characterId }
                    ChatRow(
                        chat = chat,
                        character = char,
                        onClick = { onOpenChat(chat.id) },
                        onLongClick = { vm.togglePinChat(chat.id) },
                    )
                }
            }
        }
    }

    pressedCharacter?.let { c ->
        CharacterActionsDialog(
            character = c,
            onDismiss = { pressedCharacter = null },
            onEdit = { onEditCharacter(c.id); pressedCharacter = null },
            onNewChat = {
                scope.launch { onOpenChat(vm.newChat(c.id)) }
                pressedCharacter = null
            },
            onDuplicate = {
                scope.launch { vm.duplicateCharacter(c.id) }
                pressedCharacter = null
            },
            onDelete = {
                vm.deleteCharacter(c.id)
                pressedCharacter = null
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 22.dp, bottom = 12.dp),
    )
}

@Composable
private fun ContinueCard(chat: ChatEntity, character: CharacterEntity?, onClick: () -> Unit) {
    val accent = character?.accentColor?.takeIf { it != 0 }?.let { Color(it) }
        ?: MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Box {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(accent.copy(alpha = 0.30f), Color.Transparent)
                        )
                    )
            )
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(
                    character?.name ?: "?", character?.avatarUri,
                    character?.accentColor ?: 0, 52.dp,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        character?.name ?: chat.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        chat.lastPreview.ifBlank { "点开继续" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CharacterCard(
    character: CharacterEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val accent = character.accentColor.takeIf { it != 0 }?.let { Color(it) }
        ?: MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .width(132.dp)
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(
            Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Avatar(character.name, character.avatarUri, character.accentColor, 62.dp)
            Spacer(Modifier.height(10.dp))
            Text(
                character.name.ifBlank { "未命名" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                character.tags.firstOrNull() ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                maxLines = 1,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    chat: ChatEntity,
    character: CharacterEntity?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(character?.name ?: "?", character?.avatarUri, character?.accentColor ?: 0, 42.dp)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    character?.name ?: chat.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
                if (chat.pinned) {
                    Spacer(Modifier.width(6.dp))
                    Text("· 置顶", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                chat.lastPreview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CharacterActionsDialog(
    character: CharacterEntity,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onNewChat: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(character.name.ifBlank { "未命名角色" }) },
        text = {
            Column {
                DialogRow("编辑人设", onEdit)
                DialogRow("重新开一段对话", onNewChat)
                DialogRow("复制一份", onDuplicate)
                if (!character.builtin) {
                    DialogRow("删除角色", onDelete, destructive = true)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun DialogRow(label: String, onClick: () -> Unit, destructive: Boolean = false) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = if (destructive) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    )
}
