package com.murmur.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.murmur.app.data.db.CharacterEntity
import com.murmur.app.ui.components.RoleplayText

/** 通用开局，和具体角色无关，纯粹换个处境。 */
private val GENERIC = listOf(
    "久别重逢" to "很久没见了。你们在一个都没想到的地方撞见彼此。",
    "深夜来访" to "凌晨两点，门被敲响了。",
    "被困一处" to "电梯停在了两层之间，只有你们两个人。",
    "第二天早上" to "醒来时天已经亮了，昨晚的事谁都还没提。",
    "吵架之后" to "上一次不欢而散之后，你们第一次重新面对面。",
    "雨天等车" to "雨太大了，两人挤在同一个屋檐下。",
)

/**
 * 开局选择。
 *
 * 同一个角色反复从同一句开场白开始，聊几次就腻了；
 * 换处境比换角色便宜得多，也是最容易让人愿意再开一段的东西。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartSheet(
    character: CharacterEntity,
    onDismiss: () -> Unit,
    onGreeting: (Int) -> Unit,
    onScenario: (String) -> Unit,
) {
    var custom by remember { mutableStateOf("") }
    val greetings = remember(character.id) {
        (listOf(character.firstMessage) + character.alternateGreetings).filter { it.isNotBlank() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("和 ${character.name} 重新开一段", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "选一个开场，或者自己写一个处境。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (greetings.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "角色自带的开场",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                greetings.forEachIndexed { i, g ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { onGreeting(i) }
                            .padding(14.dp),
                    ) {
                        RoleplayText(
                            text = g.lineSequence().firstOrNull { it.isNotBlank() }?.take(70).orEmpty(),
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "换个处境",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            GENERIC.chunked(2).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { (label, body) ->
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .clickable { onScenario(body) }
                                .padding(vertical = 12.dp, horizontal = 10.dp),
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = custom,
                onValueChange = { custom = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                placeholder = {
                    Text(
                        "自己写：比如「你在她家楼下站了半个小时，终于按了门铃。」",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onScenario(custom) },
                enabled = custom.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("就从这里开始") }
        }
    }
}
