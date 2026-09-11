package com.openclaw.clawagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Stop
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 深色主题色板(与旧 View 版一致)。 */
object ClawColors {
    val Bg = Color(0xFF0f1117)
    val BubbleUser = Color(0xFF3b4a6b)
    val BubbleAssistant = Color(0xFF1a1d27)
    val TextPrimary = Color(0xFFe2e8f0)
    val TextSecondary = Color(0xFF64748b)
    val Accent = Color(0xFF60a5fa)
    val Danger = Color(0xFFef4444)
}

/** 聊天主屏:顶栏(分支/角色/设置)+ 消息列表 + 欢迎态 + 输入栏。 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    onIntent: (ChatIntent) -> Unit,
    onOpenSettings: () -> Unit,
    onAttachClick: () -> Unit,
    onAttachLongClick: () -> Unit,
    messagesList: @Composable () -> Unit,
) {
    var showBranchPicker by remember { mutableStateOf(false) }
    var showRolePicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }
    var showLongPressMenu by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        containerColor = ClawColors.Bg,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ClawColors.Bg)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🦀", fontSize = 20.sp)
                TextButton(onClick = { showBranchPicker = true }) {
                    Text("🌿 ${state.activeBranchName}", color = ClawColors.TextPrimary, fontSize = 14.sp)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showRolePicker = true }) {
                    Text("🎭 ${state.roleLabel}", color = ClawColors.Accent, fontSize = 13.sp)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, "设置", tint = ClawColors.TextSecondary)
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            if (state.showWelcome) {
                WelcomeBlock(modifier = Modifier.weight(1f))
            } else {
                // 消息列表委托给调用者注入的 RecyclerView(复用 MessageAdapter
                // 的 Markdown/表格/长按管线,零 parity 损失)。
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    messagesList()
                }
            }
            InputBar(
                state = state,
                onSend = { onIntent(ChatIntent.SendMessage(it)) },
                onStop = { onIntent(ChatIntent.StopGeneration) },
                onAttachClick = onAttachClick,
                onAttachLongClick = onAttachLongClick,
            )
        }
    }

    if (showBranchPicker) {
        BranchPickerDialog(
            branches = state.branches,
            onDismiss = { showBranchPicker = false },
            onSelect = {
                showBranchPicker = false
                onIntent(ChatIntent.SwitchBranch(it))
            },
            onDelete = {
                showBranchPicker = false
                showDeleteConfirm = it
            },
            onNewBranch = {
                showBranchPicker = false
                onIntent(ChatIntent.NewBranch)
            },
        )
    }

    showDeleteConfirm?.let { branchId ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("删除分支") },
            text = { Text("删除该分支？其子分支将并入上级分支。") },
            confirmButton = {
                Button(onClick = {
                    showDeleteConfirm = null
                    onIntent(ChatIntent.DeleteBranch(branchId))
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) { Text("取消") }
            },
        )
    }

    if (showRolePicker) {
        RolePickerDialog(
            currentKey = state.roleKey,
            onDismiss = { showRolePicker = false },
            onSelect = { key ->
                showRolePicker = false
                onIntent(ChatIntent.SetRole(key))
            },
        )
    }

    showLongPressMenu?.let { position ->
        AlertDialog(
            onDismissRequest = { showLongPressMenu = null },
            title = { Text("消息操作") },
            text = { Text("复制该消息原文,或从此处分叉出新分支。") },
            confirmButton = {
                TextButton(onClick = {
                    showLongPressMenu = null
                    onIntent(ChatIntent.CopyAt(position))
                }) { Text("复制") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showLongPressMenu = null
                    onIntent(ChatIntent.ForkAt(position))
                }) { Text("从此分叉") }
            },
        )
    }
}

@Composable
private fun WelcomeBlock(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🦀", fontSize = 48.sp)
            Spacer(Modifier.size(12.dp))
            Text("和 Claw 说点什么...", color = ClawColors.TextSecondary, fontSize = 15.sp)
            Text(
                "开启 Agent 模式,它真的会干活",
                color = ClawColors.TextSecondary.copy(alpha = 0.7f),
                fontSize = 12.sp,
            )
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InputBar(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onAttachClick: () -> Unit,
    onAttachLongClick: () -> Unit,
) {
    var text by remember { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ClawColors.Bg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onAttachClick)
                .combinedClickable(onClick = onAttachClick, onLongClick = onAttachLongClick)
                .padding(8.dp),
            text = if (state.stagedImageCount > 0) "📷${state.stagedImageCount}" else "📷",
            color = if (state.stagedImageCount > 0) Color(0xFFfbbf24) else ClawColors.Accent,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("和 Claw 说点什么...", color = ClawColors.TextSecondary, fontSize = 14.sp) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = ClawColors.TextPrimary,
                unfocusedTextColor = ClawColors.TextPrimary,
                cursorColor = ClawColors.Accent,
                focusedBorderColor = ClawColors.Accent,
                unfocusedBorderColor = Color(0xFF2a2f3a),
            ),
            shape = RoundedCornerShape(12.dp),
            maxLines = 4,
        )
        IconButton(
            onClick = {
                if (state.isSending) {
                    onStop()
                } else {
                    onSend(text.trim())
                    text = ""
                }
            },
        ) {
            if (state.isSending) {
                Icon(Icons.AutoMirrored.Filled.Stop, "停止", tint = ClawColors.Danger)
            } else {
                Icon(Icons.AutoMirrored.Filled.Send, "发送", tint = ClawColors.Accent)
            }
        }
    }
}

@Composable
fun BranchPickerDialog(
    branches: List<BranchUi>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNewBranch: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分支") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                branches.forEach { b ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { onSelect(b.id) }) {
                            Text(
                                (if (b.isActive) "✅ " else "") + "${b.name}（${b.messageCount}条）",
                                color = ClawColors.TextPrimary,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (!b.isActive) {
                            TextButton(onClick = { onDelete(b.id) }) {
                                Text("删除", color = ClawColors.Danger, fontSize = 12.sp)
                            }
                        }
                    }
                }
                TextButton(onClick = onNewBranch) {
                    Icon(Icons.Filled.Add, null, tint = ClawColors.Accent)
                    Text("新建分支", color = ClawColors.Accent)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
fun RolePickerDialog(
    currentKey: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val roles = listOf(
        "general" to "🦀 通用助手",
        "code_expert" to "💻 代码专家",
        "translator" to "🌍 翻译官",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择角色") },
        text = {
            Column {
                roles.forEach { (key, label) ->
                    TextButton(onClick = { onSelect(key) }) {
                        Text(
                            (if (key == currentKey) "✅ " else "") + label,
                            color = ClawColors.TextPrimary,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
