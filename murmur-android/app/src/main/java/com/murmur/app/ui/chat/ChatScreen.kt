package com.murmur.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.murmur.app.data.db.MessageEntity
import com.murmur.app.ui.components.Avatar
import com.murmur.app.ui.components.ChatBackdrop
import com.murmur.app.ui.components.EdgeFade
import com.murmur.app.ui.components.RoleplayText
import com.murmur.app.ui.components.TypingDots
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
    onOpenCharacter: (String) -> Unit,
    onOpenMemory: (String) -> Unit,
    onOpenContext: () -> Unit,
    onOpenChatList: () -> Unit,
    onNewChat: (String) -> Unit,
    onSwitchChat: (String) -> Unit,
) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.appSettings.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var actionTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var editTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val visible = remember(state.messages) { state.messages.filter { it.content.isNotBlank() } }
    val reversed = remember(visible) { visible.asReversed() }

    // 背景优先级：这段对话专属 → 角色的 → 全局默认
    val backgroundUri = state.chat?.backgroundUri
        ?: state.character?.backgroundUri
        ?: settings.globalBackgroundUri
    val gradientKey = state.character?.id.orEmpty()
    val bubbleAlpha = settings.bubbleAlpha

    val atBottom by remember {
        derivedStateOf { listState.firstVisibleItemIndex <= 1 }
    }
    // 只在新增消息时滚。流式增长交给 reverseLayout 吸底，
    // 把 streamingText 当 key 会让这个 effect 每帧重启。
    LaunchedEffect(visible.size) {
        if (atBottom && listState.layoutInfo.totalItemsCount > 0) {
            listState.animateScrollToItem(0)
        }
    }

    ChatBackdrop(
        backgroundUri = backgroundUri,
        gradientKey = gradientKey,
        dim = settings.backgroundDim,
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0),
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                state.character?.let { onOpenCharacter(it.id) }
                            },
                        ) {
                            state.character?.let { c ->
                                Avatar(c.name, c.avatarUri, c.accentColor, 36.dp)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        c.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        if (vm.isGenerating) "正在回复…" else "${visible.size} 条消息",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, "更多")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
                )
            },
            bottomBar = {
                Column(Modifier.imePadding()) {
                    AnimatedVisibility(
                        visible = vm.error != null,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        ErrorBanner(
                            message = vm.error.orEmpty(),
                            onRetry = { vm.retryLast() },
                            onDismiss = { vm.error = null },
                        )
                    }
                    if (settings.suggestionsEnabled && !vm.isGenerating) {
                        SuggestionBar(
                            suggestions = vm.suggestions,
                            loading = vm.suggestionsLoading,
                            enabled = !vm.isGenerating,
                            onPick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                vm.sendSuggestion(it)
                            },
                            onEdit = { vm.editSuggestion(it) },
                            onRefresh = { vm.refreshSuggestions(force = true) },
                        )
                    }
                    ChatInputBar(
                        value = vm.input,
                        onValueChange = { vm.input = it },
                        isGenerating = vm.isGenerating,
                        onSend = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            vm.send()
                        },
                        onStop = { vm.stop() },
                        onNudge = { vm.nudge() },
                        onImpersonate = { vm.impersonate() },
                        onContinue = {
                            visible.lastOrNull { it.role == "assistant" }
                                ?.let { vm.continueMessage(it.id) }
                        },
                        onSuggest = { vm.refreshSuggestions(force = true) },
                    )
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (vm.isGenerating || vm.streamingText.isNotEmpty()) {
                        item(key = "streaming") {
                            StreamingBubble(
                                text = vm.streamingText,
                                reasoning = vm.reasoningText,
                                characterName = state.character?.name.orEmpty(),
                                avatarUri = state.character?.avatarUri,
                                accent = state.character?.accentColor ?: 0,
                                bubbleAlpha = bubbleAlpha,
                                isImpersonating = vm.generatingMode is GenMode.Impersonate,
                            )
                        }
                    }
                    messageItems(reversed) { indexInReversed, msg ->
                        val isLast = indexInReversed == 0
                        MessageBubble(
                            message = msg,
                            characterName = state.character?.name.orEmpty(),
                            avatarUri = state.character?.avatarUri,
                            accent = state.character?.accentColor ?: 0,
                            bubbleAlpha = bubbleAlpha,
                            isLastAssistant = isLast && msg.role == "assistant" && !vm.isGenerating,
                            onLongPress = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                actionTarget = msg
                            },
                            onSwipeTo = { vm.swipeTo(msg.id, it) },
                            onRegenerate = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.regenerate(msg.id)
                            },
                        )
                    }
                }

                EdgeFade(
                    modifier = Modifier.fillMaxWidth().height(22.dp).align(Alignment.TopCenter),
                    fromTop = true,
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.65f),
                )

                AnimatedVisibility(
                    visible = !atBottom,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    FilledTonalIconButton(onClick = {
                        scope.launch { listState.animateScrollToItem(0) }
                    }) {
                        Icon(Icons.Default.KeyboardArrowDown, "回到底部")
                    }
                }
            }
        }
    }

    actionTarget?.let { target ->
        MessageActionsSheet(
            message = target,
            onDismiss = { actionTarget = null },
            onEdit = { editTarget = target; actionTarget = null },
            onRegenerate = { vm.regenerate(target.id); actionTarget = null },
            onContinue = { vm.continueMessage(target.id); actionTarget = null },
            onDelete = { vm.deleteMessage(target.id); actionTarget = null },
            onDeleteFromHere = { vm.deleteFromHere(target.idx); actionTarget = null },
            onTogglePin = { vm.togglePin(target.id); actionTarget = null },
            onToggleHide = { vm.toggleHide(target.id); actionTarget = null },
            onBranch = {
                // 走导航而不是只切 VM：否则路由参数还停在旧会话上
                scope.launch { vm.branchFrom(target.idx)?.let { onSwitchChat(it) } }
                actionTarget = null
            },
            onDeleteSwipe = { vm.deleteSwipe(target.id); actionTarget = null },
        )
    }

    editTarget?.let { target ->
        EditMessageDialog(
            initial = target.content,
            onDismiss = { editTarget = null },
            onConfirm = { vm.editMessage(target.id, it); editTarget = null },
        )
    }

    if (menuOpen) {
        ChatMenuSheet(
            onDismiss = { menuOpen = false },
            onOpenCharacter = { state.character?.let { onOpenCharacter(it.id) }; menuOpen = false },
            onOpenMemory = { vm.chatId?.let { onOpenMemory(it) }; menuOpen = false },
            onOpenContext = { onOpenContext(); menuOpen = false },
            onOpenChatList = { onOpenChatList(); menuOpen = false },
            onNewChat = { state.character?.let { onNewChat(it.id) }; menuOpen = false },
            contextReport = vm.contextReport,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.messageItems(
    list: List<MessageEntity>,
    content: @Composable (Int, MessageEntity) -> Unit,
) = items(count = list.size, key = { list[it].id }) { i -> content(i, list[i]) }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: MessageEntity,
    characterName: String,
    avatarUri: String?,
    accent: Int,
    bubbleAlpha: Float,
    isLastAssistant: Boolean,
    onLongPress: () -> Unit,
    onSwipeTo: (Int) -> Unit,
    onRegenerate: () -> Unit,
) {
    val isUser = message.role == "user"
    val hasSwipes = message.swipes.size > 1

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            Avatar(characterName, avatarUri, accent, 34.dp)
            Spacer(Modifier.width(9.dp))
        }
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            val shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isUser) 20.dp else 6.dp,
                bottomEnd = if (isUser) 6.dp else 20.dp,
            )
            Surface(
                shape = shape,
                color = if (isUser)
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = bubbleAlpha)
                else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = bubbleAlpha),
                modifier = Modifier
                    // 背景图上气泡是半透明的，描一道细边才不会糊在一起
                    .border(
                        width = 1.dp,
                        color = if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        else Color.White.copy(alpha = 0.07f),
                        shape = shape,
                    )
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = onLongPress,
                    )
                    .then(
                        if (hasSwipes && !isUser) {
                            Modifier.pointerInput(message.id, message.activeSwipe) {
                                var total = 0f
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        if (abs(total) > 60f) {
                                            val next = if (total < 0) message.activeSwipe + 1
                                            else message.activeSwipe - 1
                                            if (next in message.swipes.indices) onSwipeTo(next)
                                        }
                                        total = 0f
                                    },
                                ) { _, dragAmount -> total += dragAmount }
                            }
                        } else Modifier
                    ),
            ) {
                Column(Modifier.padding(horizontal = 15.dp, vertical = 11.dp)) {
                    if (message.pinned) {
                        Text(
                            "已固定在上下文",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    RoleplayText(
                        text = message.content,
                        baseColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                        actionColor = if (isUser)
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!isUser && (hasSwipes || isLastAssistant)) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (hasSwipes) {
                        IconButton(
                            onClick = { onSwipeTo(message.activeSwipe - 1) },
                            enabled = message.activeSwipe > 0,
                            modifier = Modifier.size(28.dp),
                        ) { Icon(Icons.Default.ChevronLeft, "上一个", Modifier.size(18.dp)) }
                        Text(
                            "${message.activeSwipe + 1}/${message.swipes.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(
                            onClick = {
                                if (message.activeSwipe == message.swipes.size - 1) onRegenerate()
                                else onSwipeTo(message.activeSwipe + 1)
                            },
                            modifier = Modifier.size(28.dp),
                        ) { Icon(Icons.Default.ChevronRight, "下一个", Modifier.size(18.dp)) }
                    }
                    if (isLastAssistant) {
                        IconButton(onClick = onRegenerate, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.Refresh, "重新生成",
                                Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        if (isUser) Spacer(Modifier.width(6.dp))
    }
}

@Composable
private fun StreamingBubble(
    text: String,
    reasoning: String,
    characterName: String,
    avatarUri: String?,
    accent: Int,
    bubbleAlpha: Float,
    isImpersonating: Boolean,
) {
    if (isImpersonating) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "正在替你想说什么…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Avatar(characterName, avatarUri, accent, 34.dp)
        Spacer(Modifier.width(9.dp))
        val shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = bubbleAlpha),
            modifier = Modifier
                .widthIn(max = 320.dp)
                .border(1.dp, Color.White.copy(alpha = 0.07f), shape),
        ) {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 11.dp)) {
                if (reasoning.isNotBlank() && text.isBlank()) {
                    Text(
                        reasoning.takeLast(140),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(6.dp))
                }
                if (text.isBlank()) TypingDots() else RoleplayText(text = text)
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "重试",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onRetry),
                )
                Text(
                    "关闭",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }
        }
    }
}
