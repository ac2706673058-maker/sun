package com.murmur.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.murmur.app.ui.AppViewModel
import com.murmur.app.ui.chat.ChatScreen
import com.murmur.app.ui.chat.ChatViewModel
import com.murmur.app.ui.chat.PromptPreviewDialog
import com.murmur.app.ui.character.CharacterEditScreen
import com.murmur.app.ui.chatlist.ChatListScreen
import com.murmur.app.ui.home.HomeScreen
import com.murmur.app.ui.memory.MemoryScreen
import com.murmur.app.ui.onboarding.OnboardingScreen
import com.murmur.app.ui.persona.PersonaScreen
import com.murmur.app.ui.settings.SettingsScreen
import com.murmur.app.ui.theme.MurmurTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MurmurTheme {
                Surface(
                    Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) { MurmurRoot() }
            }
        }
    }
}

private object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val CHAT = "chat/{chatId}"
    const val CHAT_LIST = "chatList"
    const val CHARACTER = "character/{characterId}"
    const val SETTINGS = "settings"
    const val PERSONA = "persona"
    const val MEMORY = "memory/{chatId}"

    fun chat(id: String) = "chat/$id"
    fun character(id: String) = "character/$id"
    fun memory(id: String) = "memory/$id"
}

@Composable
private fun MurmurRoot() {
    val appVm: AppViewModel = viewModel()
    val chatVm: ChatViewModel = viewModel()
    val nav = rememberNavController()
    val boot by appVm.bootstrap.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showPrompt by remember { mutableStateOf(false) }

    // 等设置读回来再决定落到哪一页，否则老用户每次启动都会被丢回引导页
    val settings = boot ?: run {
        Box(Modifier.fillMaxSize())
        return
    }

    NavHost(
        navController = nav,
        startDestination = if (settings.onboarded) Routes.HOME else Routes.ONBOARDING,
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(appVm) {
                nav.navigate(Routes.HOME) {
                    popUpTo(Routes.ONBOARDING) { inclusive = true }
                }
            }
        }

        composable(Routes.HOME) {
            HomeScreen(
                vm = appVm,
                onOpenChat = { id ->
                    chatVm.open(id)
                    nav.navigate(Routes.chat(id))
                },
                onEditCharacter = { nav.navigate(Routes.character(it)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.CHAT) { entry ->
            val chatId = entry.arguments?.getString("chatId").orEmpty()
            LaunchedEffect(chatId) { if (chatId.isNotEmpty()) chatVm.open(chatId) }
            ChatScreen(
                vm = chatVm,
                onBack = { nav.popBackStack() },
                onOpenCharacter = { nav.navigate(Routes.character(it)) },
                onOpenMemory = { nav.navigate(Routes.memory(it)) },
                onOpenContext = { showPrompt = true },
                onOpenChatList = { nav.navigate(Routes.CHAT_LIST) },
                onNewChat = { characterId ->
                    scope.launch {
                        val id = appVm.newChat(characterId)
                        chatVm.open(id)
                        nav.navigate(Routes.chat(id)) {
                            popUpTo(Routes.HOME)
                        }
                    }
                },
            )
        }

        composable(Routes.CHAT_LIST) {
            ChatListScreen(
                vm = appVm,
                onBack = { nav.popBackStack() },
                onOpenChat = { id ->
                    chatVm.open(id)
                    nav.navigate(Routes.chat(id)) { popUpTo(Routes.HOME) }
                },
            )
        }

        composable(Routes.CHARACTER) { entry ->
            CharacterEditScreen(
                vm = appVm,
                characterId = entry.arguments?.getString("characterId").orEmpty(),
                onBack = { nav.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                vm = appVm,
                onBack = { nav.popBackStack() },
                onOpenPersona = { nav.navigate(Routes.PERSONA) },
            )
        }

        composable(Routes.PERSONA) {
            PersonaScreen(appVm) { nav.popBackStack() }
        }

        composable(Routes.MEMORY) { entry ->
            MemoryScreen(
                vm = appVm,
                chatId = entry.arguments?.getString("chatId").orEmpty(),
                onBack = { nav.popBackStack() },
            )
        }
    }

    if (showPrompt) {
        PromptPreviewDialog(
            text = chatVm.lastPromptPreview
                ?: "还没生成过。先发一条消息，这里就会显示这一轮实际拼给模型的完整提示词——" +
                "包括系统块、记忆、梗概、世界书和末尾的场景锚点。",
            onDismiss = { showPrompt = false },
        )
    }
}
