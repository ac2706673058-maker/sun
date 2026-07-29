package com.murmur.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.murmur.app.data.db.ApiProfileEntity
import com.murmur.app.ui.AppViewModel
import com.murmur.app.ui.settings.SettingText
import kotlinx.coroutines.launch

private val PRESETS = listOf(
    "DeepSeek" to "https://api.deepseek.com/v1",
    "OpenAI" to "https://api.openai.com/v1",
    "OpenRouter" to "https://openrouter.ai/api/v1",
    "硅基流动" to "https://api.siliconflow.cn/v1",
    "Moonshot" to "https://api.moonshot.cn/v1",
    "自建 / 本地" to "http://192.168.1.100:5000/v1",
)

@Composable
fun OnboardingScreen(vm: AppViewModel, onDone: () -> Unit) {
    var step by remember { mutableStateOf(0) }
    val profiles by vm.apiProfiles.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                        MaterialTheme.colorScheme.background,
                    )
                )
            )
    ) {
        when (step) {
            0 -> WelcomeStep(onNext = { step = 1 })
            1 -> AgeGateStep(
                onConfirm = {
                    vm.updateSettings { it.copy(ageConfirmed = true) }
                    step = 2
                },
                onBack = { step = 0 },
            )
            else -> ApiStep(
                vm = vm,
                onDone = {
                    vm.updateSettings { it.copy(onboarded = true) }
                    onDone()
                },
                onSkip = {
                    vm.updateSettings { it.copy(onboarded = true) }
                    onDone()
                },
            )
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("絮语", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(14.dp))
        Text(
            "一个只属于你的角色扮演客户端。",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        listOf(
            "接你自己的 API" to "任何 OpenAI 兼容的接口都行。Key 只存在这台手机上。",
            "长对话不走样" to "深度锚点、场景跟踪、长期记忆、归档梗概四件套一起上，聊几百轮人设也不会垮。",
            "客户端零过滤" to "不做任何关键词拦截。尺度完全取决于你接的服务端。",
            "全都在本地" to "对话、角色、记忆都存在设备里，没有账号，没有云端。",
        ).forEach { (title, body) ->
            Column(Modifier.padding(bottom = 20.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("开始") }
    }
}

@Composable
private fun AgeGateStep(onConfirm: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("先确认一件事", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(18.dp))
        Text(
            "这个应用用于成年人的虚构创作。内容尺度由你自己配置的服务端决定，客户端不做过滤。\n\n" +
                "唯一的硬性约束：所有出场角色均为成年人。这条写死在提示词里，不可关闭。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
            Text("我已年满 18 周岁，继续")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
    }
}

@Composable
private fun ApiStep(vm: AppViewModel, onDone: () -> Unit, onSkip: () -> Unit) {
    val profiles by vm.apiProfiles.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }

    // 输入框必须绑在同步更新的本地草稿上。
    // 直接绑 Room Flow 收上来的对象的话，打字快的时候第二个键会基于还没刷新的旧值 copy，
    // 前面敲的字符就没了 —— 填 Key 的时候这个尤其致命。
    var draft by remember { mutableStateOf<ApiProfileEntity?>(null) }
    LaunchedEffect(profiles) {
        if (draft == null || profiles.none { it.id == draft?.id }) {
            draft = profiles.firstOrNull { it.isActive } ?: profiles.firstOrNull()
        }
    }
    val profile = draft

    fun edit(updated: ApiProfileEntity) {
        draft = updated
        vm.saveProfile(updated)
    }

    if (profile == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        Text("接上你的 API", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "任何 OpenAI 兼容接口都可以。之后随时能在设置里改。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))

        Text("选个服务商快速填地址", style = MaterialTheme.typography.labelMedium)
        Column {
            PRESETS.chunked(2).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { (label, url) ->
                        AssistChip(
                            onClick = { edit(profile.copy(baseUrl = url)) },
                            label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        SettingText(
            "接口地址", profile.baseUrl,
            { edit(profile.copy(baseUrl = it.trim())) },
            placeholder = "https://api.example.com/v1",
        )
        SettingText(
            "API Key", profile.apiKey,
            { edit(profile.copy(apiKey = it.trim())) },
            isSecret = true,
        )
        SettingText(
            "模型名", profile.model,
            { edit(profile.copy(model = it.trim())) },
            placeholder = "deepseek-chat / gpt-4o / …",
        )

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                scope.launch {
                    testing = true
                    result = null
                    vm.testProfile(profile)
                        .onSuccess { result = "通了：${it.take(60)}"; ok = true }
                        .onFailure { result = it.message ?: "连不上"; ok = false }
                    testing = false
                }
            },
            enabled = !testing && profile.baseUrl.isNotBlank() && profile.model.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (testing) CircularProgressIndicator(
                Modifier.height(18.dp), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            ) else Text("测试连接")
        }

        result?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
            enabled = profile.baseUrl.isNotBlank() && profile.model.isNotBlank(),
        ) { Text("进去聊聊") }
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("先跳过，稍后再配", style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(30.dp))
    }
}
