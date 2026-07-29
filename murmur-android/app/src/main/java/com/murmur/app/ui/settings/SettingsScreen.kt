package com.murmur.app.ui.settings

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.murmur.app.data.db.ApiProfileEntity
import com.murmur.app.engine.PromptTemplates
import com.murmur.app.ui.AppViewModel
import kotlinx.coroutines.launch

private val ENDPOINT_PRESETS = listOf(
    "OpenAI" to "https://api.openai.com/v1",
    "DeepSeek" to "https://api.deepseek.com/v1",
    "OpenRouter" to "https://openrouter.ai/api/v1",
    "硅基流动" to "https://api.siliconflow.cn/v1",
    "Moonshot" to "https://api.moonshot.cn/v1",
    "智谱 GLM" to "https://open.bigmodel.cn/api/paas/v4",
    "本机/自建" to "http://192.168.1.100:5000/v1",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit, onOpenPersona: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val profiles by vm.apiProfiles.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var draft by remember { mutableStateOf<ApiProfileEntity?>(null) }
    LaunchedEffect(profiles) {
        if (draft == null || profiles.none { it.id == draft?.id }) {
            draft = profiles.firstOrNull { it.isActive } ?: profiles.firstOrNull()
        }
    }

    var testing by remember { mutableStateOf(false) }
    var fetchingModels by remember { mutableStateOf(false) }
    var modelOptions by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelMenu by remember { mutableStateOf(false) }
    var profileMenu by remember { mutableStateOf(false) }
    var editingPrompt by remember { mutableStateOf<String?>(null) }

    fun save(p: ApiProfileEntity) {
        draft = p
        vm.saveProfile(p)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 0.dp),
        ) {
            item {
                SettingSection(
                    "接口",
                    subtitle = draft?.let {
                        if (it.baseUrl.isBlank()) "还没配置" else "${it.name} · ${it.model.ifBlank { "未选模型" }}"
                    } ?: "还没配置",
                    initiallyExpanded = draft?.baseUrl.isNullOrBlank(),
                ) {
                    val p = draft
                    if (p == null) {
                        Text("加载中…", style = MaterialTheme.typography.bodySmall)
                    } else {
                        if (profiles.size > 1) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("当前：", style = MaterialTheme.typography.bodySmall)
                                Box {
                                    TextButton(onClick = { profileMenu = true }) { Text(p.name) }
                                    DropdownMenu(profileMenu, { profileMenu = false }) {
                                        profiles.forEach { prof ->
                                            DropdownMenuItem(
                                                text = { Text(prof.name) },
                                                onClick = {
                                                    profileMenu = false
                                                    draft = prof
                                                    vm.activateProfile(prof.id)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        SettingText("配置名", p.name, { save(p.copy(name = it)) })

                        Text(
                            "常用地址",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Column {
                            ENDPOINT_PRESETS.chunked(3).forEach { row ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    row.forEach { (label, url) ->
                                        androidx.compose.material3.AssistChip(
                                            onClick = { save(p.copy(baseUrl = url)) },
                                            label = {
                                                Text(label, style = MaterialTheme.typography.labelSmall)
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        SettingText(
                            "接口地址 (Base URL)", p.baseUrl, { save(p.copy(baseUrl = it.trim())) },
                            hint = "填到 /v1 就行，会自动补 /chat/completions",
                            placeholder = "https://api.example.com/v1",
                        )
                        SettingText(
                            "API Key", p.apiKey, { save(p.copy(apiKey = it.trim())) },
                            isSecret = true,
                            hint = "只存在这台手机上，不会上传到任何地方",
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                SettingText(
                                    "模型", p.model, { save(p.copy(model = it.trim())) },
                                    placeholder = "gpt-4o / deepseek-chat / …",
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        fetchingModels = true
                                        vm.fetchModels(p)
                                            .onSuccess {
                                                modelOptions = it
                                                if (it.isEmpty()) snackbar.showSnackbar("接口没返回模型列表，手动填模型名吧")
                                                else modelMenu = true
                                            }
                                            .onFailure { e ->
                                                snackbar.showSnackbar(e.message ?: "拉取失败")
                                            }
                                        fetchingModels = false
                                    }
                                },
                                enabled = !fetchingModels && p.baseUrl.isNotBlank(),
                            ) {
                                if (fetchingModels) {
                                    CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp)
                                } else Text("拉取模型列表")
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        testing = true
                                        vm.testProfile(p)
                                            .onSuccess { snackbar.showSnackbar("通了：${it.take(40)}") }
                                            .onFailure { e -> snackbar.showSnackbar(e.message ?: "连不上") }
                                        testing = false
                                    }
                                },
                                enabled = !testing && p.baseUrl.isNotBlank() && p.model.isNotBlank(),
                            ) {
                                if (testing) {
                                    CircularProgressIndicator(
                                        Modifier.height(16.dp), strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                } else Text("测试连接")
                            }
                        }
                        Box {
                            DropdownMenu(modelMenu, { modelMenu = false }) {
                                modelOptions.take(60).forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(m, style = MaterialTheme.typography.bodySmall) },
                                        onClick = { modelMenu = false; save(p.copy(model = m)) },
                                    )
                                }
                            }
                        }

                        SettingText(
                            "后台任务模型（可留空）", p.utilityModel,
                            { save(p.copy(utilityModel = it.trim())) },
                            hint = "摘要 / 记忆抽取 / 场景跟踪走这个模型。填个便宜的小模型能省不少钱，留空则复用主模型",
                        )

                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                val n = vm.newProfile()
                                draft = n
                                vm.saveProfile(n.copy(isActive = true))
                            }) { Text("新增一个配置") }
                            if (profiles.size > 1) {
                                TextButton(onClick = {
                                    vm.deleteProfile(p.id)
                                    draft = profiles.firstOrNull { it.id != p.id }
                                }) { Text("删除当前", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }

            item {
                SettingSection("生成参数", "手感调节：温度、长度、惩罚") {
                    val p = draft ?: return@SettingSection
                    SettingSlider(
                        "温度 temperature", p.temperature, 0f..2f,
                        hint = "低了呆板，高了发散。角色扮演一般 0.85–1.05",
                    ) { save(p.copy(temperature = it)) }
                    SettingSlider("top_p", p.topP, 0f..1f) { save(p.copy(topP = it)) }
                    SettingSlider(
                        "min_p", p.minP, 0f..0.5f,
                        hint = "本地推理端点常用，云端多数忽略",
                    ) { save(p.copy(minP = it)) }
                    SettingSlider(
                        "重复惩罚 frequency_penalty", p.frequencyPenalty, 0f..2f,
                        hint = "调高能压住复读，但太高会让角色丢掉口头禅",
                    ) { save(p.copy(frequencyPenalty = it)) }
                    SettingSlider("presence_penalty", p.presencePenalty, 0f..2f) {
                        save(p.copy(presencePenalty = it))
                    }
                    SettingIntSlider(
                        "单次回复上限", p.maxTokens, 128..4096, suffix = " tokens",
                        hint = "太小会把话说一半就断",
                    ) { save(p.copy(maxTokens = it)) }
                    SettingIntSlider(
                        "上下文长度", p.contextTokens, 2048..200000, suffix = " tokens",
                        hint = "按你的模型实际支持的填。填大了会被服务端拒绝",
                    ) { save(p.copy(contextTokens = it)) }
                    SettingSwitch(
                        "流式输出", "关掉后整段返回，某些端点不支持流式时用",
                        p.stream,
                    ) { save(p.copy(stream = it)) }
                    SettingSwitch(
                        "发送高级采样参数",
                        "top_k / min_p / repetition_penalty 等。遇到 400 报错就关掉",
                        p.sendAdvancedSampling,
                    ) { save(p.copy(sendAdvancedSampling = it)) }
                    SettingSwitch(
                        "合并 system 消息",
                        "有些端点只接受一条 system。开了之后锚点会改成方括号旁注",
                        p.mergeSystemMessages,
                    ) { save(p.copy(mergeSystemMessages = it)) }
                    SettingText(
                        "额外请求体 (JSON)", p.extraBodyJson,
                        { save(p.copy(extraBodyJson = it)) },
                        singleLine = false, minHeight = 80,
                        hint = "会并进请求体，可用来传端点特有的参数",
                    )
                    SettingText(
                        "额外请求头 (JSON)", p.extraHeadersJson,
                        { save(p.copy(extraHeadersJson = it)) },
                        singleLine = false, minHeight = 70,
                    )
                }
            }

            item {
                SettingSection(
                    "上下文引擎",
                    "决定角色会不会随着聊天变长而走样",
                    initiallyExpanded = false,
                ) {
                    SettingSwitch(
                        "深度锚点",
                        "在对话末尾重述身份与当前场景。这是长对话不崩人设的关键，别关",
                        settings.anchorEnabled,
                    ) { v -> vm.updateSettings { it.copy(anchorEnabled = v) } }
                    if (settings.anchorEnabled) {
                        SettingIntSlider(
                            "锚点插入深度", settings.anchorDepth, 0..4,
                            suffix = " 条之前",
                            hint = "插得越靠后影响越强，但也越容易打断对话的连贯感。默认 1",
                        ) { v -> vm.updateSettings { it.copy(anchorDepth = v) } }
                    }
                    SettingSwitch(
                        "场景状态跟踪",
                        "每轮后台更新地点/时间/穿着/姿态/情绪，塞进锚点。能治「刚才还在厨房现在突然在床上」",
                        settings.sceneTrackingEnabled,
                    ) { v -> vm.updateSettings { it.copy(sceneTrackingEnabled = v) } }
                    SettingSwitch(
                        "长期记忆",
                        "自动把重要事实抽出来存起来，按相关性回填。忘不掉你说过的话",
                        settings.memoryEnabled,
                    ) { v -> vm.updateSettings { it.copy(memoryEnabled = v) } }
                    SettingSwitch(
                        "归档梗概",
                        "被挤出窗口的旧对话压缩成前情提要，而不是直接丢掉",
                        settings.summaryEnabled,
                    ) { v -> vm.updateSettings { it.copy(summaryEnabled = v) } }
                    SettingSwitch(
                        "复读检测",
                        "发现角色开始重复用词时，在锚点里点名让它换个说法",
                        settings.antiRepeatEnabled,
                    ) { v -> vm.updateSettings { it.copy(antiRepeatEnabled = v) } }
                    SettingSwitch(
                        "示例对白常驻",
                        "把角色卡里的示例对白留在上下文里当语气锚。省 token 可以关",
                        settings.exampleDialogueEnabled,
                    ) { v -> vm.updateSettings { it.copy(exampleDialogueEnabled = v) } }

                    Spacer(Modifier.height(8.dp))
                    Text("回复长度倾向", style = MaterialTheme.typography.bodyMedium)
                    ChipRow(
                        options = listOf("简短", "适中", "详细", "沉浸"),
                        selected = when (settings.lengthHint) {
                            "short" -> "简短"; "long" -> "详细"; "immersive" -> "沉浸"; else -> "适中"
                        },
                    ) { label ->
                        val key = when (label) {
                            "简短" -> "short"; "详细" -> "long"; "沉浸" -> "immersive"; else -> "medium"
                        }
                        vm.updateSettings { it.copy(lengthHint = key) }
                    }

                    SettingSlider(
                        "历史保底占比", settings.historyFloorRatio, 0.2f..0.8f,
                        hint = "设定块再多也不能把对话历史挤没了。默认 50%",
                        format = { "${(it * 100).toInt()}%" },
                    ) { v -> vm.updateSettings { it.copy(historyFloorRatio = v) } }
                    SettingIntSlider(
                        "上下文里最多放几条记忆", settings.maxMemoriesInContext, 4..30,
                    ) { v -> vm.updateSettings { it.copy(maxMemoriesInContext = v) } }
                    SettingIntSlider(
                        "每几轮抽一次记忆", settings.memoryEveryNTurns, 2..12,
                        suffix = " 轮",
                    ) { v -> vm.updateSettings { it.copy(memoryEveryNTurns = v) } }
                    SettingIntSlider(
                        "攒够几条才压缩归档", settings.summarizeBatchSize, 2..20,
                        suffix = " 条",
                    ) { v -> vm.updateSettings { it.copy(summarizeBatchSize = v) } }
                }
            }

            item {
                SettingSection("提示词", "全局主提示词与尾部指令") {
                    Text(
                        "这两段是所有角色共用的底座；单个角色可以在角色编辑页里覆盖。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { editingPrompt = "main" },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("编辑主提示词") }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { editingPrompt = "post" },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("编辑尾部指令") }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { editingPrompt = "custom" },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("追加自定义要求") }
                }
            }

            item {
                SettingSection("我", "角色眼里的「你」是谁") {
                    OutlinedButton(onClick = onOpenPersona, modifier = Modifier.fillMaxWidth()) {
                        Text("编辑我的人设")
                    }
                }
            }

            item {
                SettingSection("外观与手感") {
                    SettingSwitch(
                        "平滑打字", "把流式返回的爆发式分块摊匀，看起来像真的在打字",
                        settings.streamingSmoothing,
                    ) { v -> vm.updateSettings { it.copy(streamingSmoothing = v) } }
                    SettingSwitch("触感反馈", null, settings.hapticsEnabled) { v ->
                        vm.updateSettings { it.copy(hapticsEnabled = v) }
                    }
                }
            }

            item {
                SettingSection("关于") {
                    Text(
                        "絮语 1.0\n\n" +
                            "所有数据只存在这台手机上：对话、角色、记忆、API Key 都不会离开设备，" +
                            "除了发给你自己配置的那个接口。\n\n" +
                            "客户端不做任何内容过滤——尺度取决于你接的服务端。\n" +
                            "唯一的硬性约束是所有角色均为成年人。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { Spacer(Modifier.height(40.dp)) }
        }
    }

    editingPrompt?.let { which ->
        val (title, value, reset) = when (which) {
            "main" -> Triple("主提示词", settings.mainPrompt, PromptTemplates.DEFAULT_MAIN)
            "post" -> Triple("尾部指令", settings.postHistory, PromptTemplates.DEFAULT_POST_HISTORY)
            else -> Triple("自定义追加要求", settings.customDirective, "")
        }
        PromptEditorDialog(
            title = title,
            initial = value,
            defaultValue = reset,
            onDismiss = { editingPrompt = null },
            onConfirm = { text ->
                vm.updateSettings {
                    when (which) {
                        "main" -> it.copy(mainPrompt = text)
                        "post" -> it.copy(postHistory = text)
                        else -> it.copy(customDirective = text)
                    }
                }
                editingPrompt = null
            },
        )
    }
}

@Composable
private fun PromptEditorDialog(
    title: String,
    initial: String,
    defaultValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "可用变量：{{char}} 角色名、{{user}} 你的名字、{{persona}} 你的人设",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(340.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("保存") } },
        dismissButton = {
            Row {
                if (defaultValue.isNotEmpty()) {
                    TextButton(onClick = { text = defaultValue }) { Text("恢复默认") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
