package com.murmur.app.ui.character

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.murmur.app.data.db.CharacterEntity
import com.murmur.app.data.db.LoreEntity
import com.murmur.app.ui.AppViewModel
import com.murmur.app.ui.components.Avatar
import com.murmur.app.ui.settings.SettingSection
import com.murmur.app.ui.settings.SettingText
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

/** 区分「Flow 还没发射」和「发射了但是 null」。 */
private data class Loaded(val value: CharacterEntity?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterEditScreen(
    vm: AppViewModel,
    characterId: String,
    onBack: () -> Unit,
) {
    // Room 的 Flow 首次发射之前是 null，"还没加载"和"数据库里没有"必须区分开：
    // 否则编辑已有角色时会先塞一个空白草稿，之后再也不刷新，一保存就把人设清空了。
    val loaded by remember(characterId) {
        vm.observeCharacter(characterId).map { Loaded(it) }
    }.collectAsStateWithLifecycle(null)

    val loreList by remember(characterId) { vm.observeLore(characterId) }
        .collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    var draft by remember(characterId) { mutableStateOf<CharacterEntity?>(null) }
    val existing = loaded?.value
    LaunchedEffect(loaded) {
        val snapshot = loaded ?: return@LaunchedEffect
        if (draft == null) {
            draft = snapshot.value ?: CharacterEntity(id = characterId, name = "")
        }
    }
    var editingLore by remember { mutableStateOf<LoreEntity?>(null) }

    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.setAvatar(characterId, uri) }

    val c = draft
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "新角色" else "编辑角色") },
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
            FloatingActionButton(onClick = {
                c?.let {
                    if (it.name.isBlank()) {
                        scope.launch { snackbar.showSnackbar("先给 TA 起个名字") }
                    } else {
                        vm.saveCharacter(it)
                        onBack()
                    }
                }
            }) { Icon(Icons.Default.Check, "保存") }
        },
    ) { padding ->
        if (c == null) return@Scaffold

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 100.dp, top = 8.dp),
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.clickable {
                        avatarPicker.launch(arrayOf("image/*"))
                    }) {
                        Avatar(c.name.ifBlank { "?" }, c.avatarUri, c.accentColor, 84.dp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "点头像换图",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                SettingSection("基本", initiallyExpanded = true) {
                    SettingText("名字", c.name, { draft = c.copy(name = it) })
                    SettingText(
                        "身份与外貌", c.description, { draft = c.copy(description = it) },
                        singleLine = false, minHeight = 130,
                        hint = "越具体越好：年龄、职业、长相、习惯动作、身上的细节",
                    )
                    SettingText(
                        "性格", c.personality, { draft = c.copy(personality = it) },
                        singleLine = false, minHeight = 120,
                        hint = "包括缺点和底线。只有优点的角色是纸片人",
                    )
                    SettingText(
                        "说话方式", c.speechStyle, { draft = c.copy(speechStyle = it) },
                        singleLine = false, minHeight = 110,
                        hint = "语速、句长、口头禅、怎么称呼你、生气时什么样。这一栏对长对话不走样最有用",
                    )
                    SettingText(
                        "开场情境", c.scenario, { draft = c.copy(scenario = it) },
                        singleLine = false, minHeight = 90,
                    )
                }
            }

            item {
                SettingSection("开场白") {
                    SettingText(
                        "第一句", c.firstMessage, { draft = c.copy(firstMessage = it) },
                        singleLine = false, minHeight = 140,
                        hint = "动作用 *星号* 包住。这段的文风会强烈影响之后所有回复",
                    )
                    c.alternateGreetings.forEachIndexed { i, g ->
                        SettingText(
                            "备选开场 ${i + 1}", g,
                            { v ->
                                draft = c.copy(
                                    alternateGreetings = c.alternateGreetings.toMutableList()
                                        .also { it[i] = v }
                                )
                            },
                            singleLine = false, minHeight = 100,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            draft = c.copy(alternateGreetings = c.alternateGreetings + "")
                        }) { Text("加一个备选开场") }
                        if (c.alternateGreetings.isNotEmpty()) {
                            TextButton(onClick = {
                                draft = c.copy(alternateGreetings = c.alternateGreetings.dropLast(1))
                            }) { Text("删掉最后一个") }
                        }
                    }
                }
            }

            item {
                SettingSection("示例对白", "教模型怎么说话，比任何形容词都管用") {
                    SettingText(
                        "示例", c.exampleDialogue, { draft = c.copy(exampleDialogue = it) },
                        singleLine = false, minHeight = 180,
                        hint = "格式：<START> 换行 {{user}}: … 换行 {{char}}: …　可以写多组",
                    )
                }
            }

            item {
                SettingSection("世界书", "命中关键词才注入，能装很多设定又不占常驻上下文") {
                    val own = loreList.filter { it.characterId == characterId }
                    own.forEach { e ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { editingLore = e },
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    e.name.ifBlank { e.keys.joinToString("、").ifBlank { "未命名条目" } },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    (if (e.constant) "常驻　" else "关键词：${e.keys.joinToString("、")}　") +
                                        e.content.take(40),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                    TextButton(onClick = {
                        editingLore = LoreEntity(
                            id = UUID.randomUUID().toString(),
                            characterId = characterId,
                        )
                    }) { Text("新增条目") }
                }
            }

            item {
                SettingSection("高级", "覆盖全局提示词（留空则用全局）") {
                    SettingText(
                        "该角色专用主提示词", c.systemPromptOverride,
                        { draft = c.copy(systemPromptOverride = it) },
                        singleLine = false, minHeight = 120,
                    )
                    SettingText(
                        "该角色专用尾部指令", c.postHistoryOverride,
                        { draft = c.copy(postHistoryOverride = it) },
                        singleLine = false, minHeight = 90,
                    )
                    SettingText(
                        "标签（逗号分隔）", c.tags.joinToString("，"),
                        { draft = c.copy(tags = it.split("，", ",").map { t -> t.trim() }.filter { t -> t.isNotEmpty() }) },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                vm.exportCharacter(characterId)?.let {
                                    clipboard.setText(AnnotatedString(it))
                                    snackbar.showSnackbar("角色卡 JSON 已复制到剪贴板")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("导出角色卡 JSON") }
                }
            }
        }
    }

    editingLore?.let { entry ->
        LoreEditDialog(
            entry = entry,
            onDismiss = { editingLore = null },
            onSave = { vm.saveLore(it); editingLore = null },
            onDelete = { vm.deleteLore(entry.id); editingLore = null },
        )
    }
}

@Composable
private fun LoreEditDialog(
    entry: LoreEntity,
    onDismiss: () -> Unit,
    onSave: (LoreEntity) -> Unit,
    onDelete: () -> Unit,
) {
    var e by remember { mutableStateOf(entry) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("世界书条目") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SettingText("名称", e.name, { e = e.copy(name = it) })
                SettingText(
                    "触发关键词（逗号分隔）", e.keys.joinToString("，"),
                    { v -> e = e.copy(keys = v.split("，", ",").map { it.trim() }.filter { it.isNotEmpty() }) },
                    hint = "最近几条消息里出现任一关键词就注入",
                )
                SettingText(
                    "内容", e.content, { e = e.copy(content = it) },
                    singleLine = false, minHeight = 130,
                )
                com.murmur.app.ui.settings.SettingSwitch(
                    "常驻", "不看关键词，永远注入", e.constant,
                ) { e = e.copy(constant = it) }
                com.murmur.app.ui.settings.SettingSwitch("启用", null, e.enabled) {
                    e = e.copy(enabled = it)
                }
                com.murmur.app.ui.settings.SettingIntSlider(
                    "扫描深度", e.scanDepth, 1..20, suffix = " 条",
                ) { e = e.copy(scanDepth = it) }
                com.murmur.app.ui.settings.SettingIntSlider(
                    "优先级", e.priority, 0..200,
                ) { e = e.copy(priority = it) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(e) }) { Text("保存") } },
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
