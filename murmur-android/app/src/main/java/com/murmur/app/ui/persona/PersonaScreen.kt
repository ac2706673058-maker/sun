package com.murmur.app.ui.persona

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.murmur.app.data.db.PersonaEntity
import com.murmur.app.ui.AppViewModel
import com.murmur.app.ui.settings.SettingSwitch
import com.murmur.app.ui.settings.SettingText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaScreen(vm: AppViewModel, onBack: () -> Unit) {
    val personas by vm.personas.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<PersonaEntity?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("我的人设") },
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
            FloatingActionButton(onClick = { editing = vm.newPersona() }) {
                Icon(Icons.Default.Add, "新增")
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(top = 8.dp, bottom = 100.dp),
        ) {
            item {
                Text(
                    "角色眼里的你是谁。写清楚之后，TA 会知道该怎么称呼你、了解你的身份和处境，" +
                        "而不是每次都从零开始猜。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
            items(personas, key = { it.id }) { p ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                        .clickable { editing = p },
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name.ifBlank { "未命名" }, style = MaterialTheme.typography.bodyLarge)
                            if (p.description.isNotBlank()) {
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    p.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                            }
                        }
                        if (p.isDefault) {
                            Icon(
                                Icons.Default.Check, "当前使用",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { p ->
        var d by remember(p.id) { mutableStateOf(p) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("人设") },
            text = {
                Column {
                    SettingText("你的名字", d.name, { d = d.copy(name = it) })
                    SettingText(
                        "关于你", d.description, { d = d.copy(description = it) },
                        singleLine = false, minHeight = 130,
                        hint = "年龄、身份、和 TA 的关系、你希望被怎么对待。写不写都行，但写了效果差别很大",
                    )
                    SettingSwitch("设为当前使用", null, d.isDefault) { d = d.copy(isDefault = it) }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.savePersona(d); editing = null }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    if (personas.size > 1) {
                        TextButton(onClick = { vm.deletePersona(p.id); editing = null }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = { editing = null }) { Text("取消") }
                }
            },
        )
    }
}
