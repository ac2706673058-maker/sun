package com.murmur.app.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.murmur.app.data.db.CharacterEntity
import com.murmur.app.data.db.ChatEntity
import com.murmur.app.ui.AppViewModel
import com.murmur.app.ui.components.Avatar
import com.murmur.app.ui.components.AuroraBackground
import com.murmur.app.ui.components.CoverArt
import com.murmur.app.ui.theme.Palette
import kotlinx.coroutines.launch
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    vm: AppViewModel,
    onOpenChat: (String) -> Unit,
    onStartScenario: (String) -> Unit,
    onEditCharacter: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val characters by vm.characters.collectAsStateWithLifecycle()
    val chats by vm.chats.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var addMenu by remember { mutableStateOf(false) }
    var pressedCharacter by remember { mutableStateOf<CharacterEntity?>(null) }
    var startFor by remember { mutableStateOf<CharacterEntity?>(null) }

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
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
        AuroraBackground(Modifier.fillMaxWidth().height(420.dp))
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 36.dp),
        ) {
            item {
                HomeHeader(
                    onAdd = { addMenu = true },
                    onOpenSettings = onOpenSettings,
                    addMenu = addMenu,
                    onDismissMenu = { addMenu = false },
                    onCreate = { addMenu = false; onEditCharacter(vm.newCharacter().id) },
                    onImport = {
                        addMenu = false
                        importLauncher.launch(arrayOf("image/png", "application/json", "*/*"))
                    },
                )
            }

            val recent = chats.firstOrNull()
            if (recent != null) {
                val char = characters.firstOrNull { it.id == recent.characterId }
                item {
                    ContinueHero(
                        chat = recent,
                        character = char,
                        onClick = { onOpenChat(recent.id) },
                    )
                }
            }

            if (characters.isNotEmpty()) {
                item { SectionHeader("角色", "长按可编辑、复制或删除") }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(13.dp),
                    ) {
                        items(characters, key = { it.id }) { c ->
                            CharacterCard(
                                character = c,
                                onClick = { scope.launch { onOpenChat(vm.openOrCreateChat(c.id)) } },
                                onLongClick = { pressedCharacter = c },
                            )
                        }
                    }
                }
            } else {
                item { EmptyCharacters(onCreate = { onEditCharacter(vm.newCharacter().id) }) }
            }

            if (chats.size > 1) {
                item { SectionHeader("最近的对话", null) }
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
    }

    startFor?.let { c ->
        StartSheet(
            character = c,
            onDismiss = { startFor = null },
            onGreeting = { idx ->
                startFor = null
                scope.launch { onOpenChat(vm.newChat(c.id, idx)) }
            },
            onScenario = { text ->
                startFor = null
                scope.launch { onStartScenario(vm.newChatWithScenario(c.id, text)) }
            },
        )
    }

    pressedCharacter?.let { c ->
        CharacterActionsDialog(
            character = c,
            onDismiss = { pressedCharacter = null },
            onEdit = { onEditCharacter(c.id); pressedCharacter = null },
            onNewChat = {
                pressedCharacter = null
                startFor = c
            },
            onDuplicate = {
                scope.launch { vm.duplicateCharacter(c.id) }
                pressedCharacter = null
            },
            onDelete = { vm.deleteCharacter(c.id); pressedCharacter = null },
        )
    }
}

@Composable
private fun HomeHeader(
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit,
    addMenu: Boolean,
    onDismissMenu: () -> Unit,
    onCreate: () -> Unit,
    onImport: () -> Unit,
) {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = when (hour) {
        in 5..10 -> "早上好"
        in 11..13 -> "中午好"
        in 14..17 -> "下午好"
        in 18..22 -> "晚上好"
        else -> "夜深了"
    }
    val sub = when (hour) {
        in 5..10 -> "今天想和谁说说话"
        in 11..13 -> "抽空聊两句"
        in 14..17 -> "有人一直在等你"
        in 18..22 -> "这会儿正合适"
        else -> "还没睡的话，有人陪着"
    }

    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 22.dp, end = 10.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                greeting,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "新增角色") }
            DropdownMenu(addMenu, onDismissRequest = onDismissMenu) {
                DropdownMenuItem(
                    text = { Text("从零创建一个角色") },
                    leadingIcon = { Icon(Icons.Default.Add, null) },
                    onClick = onCreate,
                )
                DropdownMenuItem(
                    text = { Text("导入角色卡（PNG / JSON）") },
                    leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                    onClick = onImport,
                )
            }
        }
        IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "设置") }
    }
}

/** 首页头卡：大图 + 自下而上的压暗 + 上次说到哪。整个应用第一眼看到的就是它。 */
@Composable
private fun ContinueHero(chat: ChatEntity, character: CharacterEntity?, onClick: () -> Unit) {
    val key = character?.id.orEmpty()
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .aspectRatio(1.55f)
            .clip(RoundedCornerShape(26.dp))
            .clickable(onClick = onClick),
    ) {
        CoverArt(
            imageUri = character?.portraitUri ?: character?.backgroundUri ?: character?.avatarUri,
            gradientKey = key,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color.Black.copy(alpha = 0.35f),
                        1f to Color.Black.copy(alpha = 0.82f),
                    )
                )
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(20.dp),
        ) {
            Text(
                "继续",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                character?.name ?: chat.title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                chat.lastPreview.ifBlank { "点开继续" },
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.82f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(18.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f))
                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp), tint = Color.White)
        }
    }
}

@Composable
private fun SectionHeader(text: String, hint: String?) {
    Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 12.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall)
        hint?.let {
            Spacer(Modifier.height(2.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

/** 竖版卡片，比一排小圆头像更有存在感，也给没配图的角色留了渐变兜底。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CharacterCard(
    character: CharacterEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        Modifier
            .width(132.dp)
            .aspectRatio(0.72f)
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        CoverArt(
            imageUri = character.portraitUri ?: character.avatarUri,
            gradientKey = character.id,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.5f to Color.Black.copy(alpha = 0.2f),
                        1f to Color.Black.copy(alpha = 0.8f),
                    )
                )
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
        ) {
            Text(
                character.name.ifBlank { "未命名" },
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            character.tags.firstOrNull()?.let { tag ->
                Spacer(Modifier.height(3.dp))
                Text(
                    tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1,
                )
            }
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
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
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
                        Text(
                            "· 置顶",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
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
}

@Composable
private fun EmptyCharacters(onCreate: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "一个角色也没有了",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "可以从零写一个，也可以导入现成的角色卡。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onCreate) { Text("创建一个") }
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(character.name.ifBlank { "未命名角色" }) },
        text = {
            Column {
                DialogRow("编辑人设与配图", onEdit)
                DialogRow("换个开局，重新聊", onNewChat)
                DialogRow("复制一份", onDuplicate)
                DialogRow("删除角色", onDelete, destructive = true)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
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
