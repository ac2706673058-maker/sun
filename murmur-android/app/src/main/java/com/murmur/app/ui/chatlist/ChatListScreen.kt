package com.murmur.app.ui.chatlist

import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.murmur.app.data.db.ChatEntity
import com.murmur.app.ui.AppViewModel
import com.murmur.app.ui.components.Avatar

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatListScreen(vm: AppViewModel, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val chats by vm.chats.collectAsStateWithLifecycle()
    val characters by vm.characters.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<ChatEntity?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("所有对话") },
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
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(chats, key = { it.id }) { chat ->
                val char = characters.firstOrNull { it.id == chat.characterId }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onOpenChat(chat.id) },
                            onLongClick = { pending = chat },
                        )
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(char?.name ?: "?", char?.avatarUri, char?.accentColor ?: 0, 42.dp)
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(chat.title.ifBlank { char?.name ?: "对话" },
                                style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            if (chat.pinned) {
                                Spacer(Modifier.width(6.dp))
                                Text("· 置顶", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary)
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
    }

    pending?.let { chat ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(chat.title.ifBlank { "这段对话" }) },
            text = { Text("要对这段对话做什么？") },
            confirmButton = {
                TextButton(onClick = { vm.togglePinChat(chat.id); pending = null }) {
                    Text(if (chat.pinned) "取消置顶" else "置顶")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.deleteChat(chat.id); pending = null }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { pending = null }) { Text("取消") }
                }
            },
        )
    }
}
