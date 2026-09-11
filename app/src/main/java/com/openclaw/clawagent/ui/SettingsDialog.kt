package com.openclaw.clawagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openclaw.clawagent.provider.Provider
import com.openclaw.clawagent.provider.ProviderCatalog
import com.openclaw.clawagent.provider.ProviderHealth
import com.openclaw.clawagent.provider.ProviderHealthCache
import com.openclaw.clawagent.provider.ProviderHealthChecker
import com.openclaw.clawagent.provider.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 工具配置二级对话框的友好名(原 View 版 companion 常量,随对话框迁 Compose)。 */
val TOOL_LABELS = mapOf(
    "calculator" to "🧮 计算器",
    "current_time" to "🕐 实时时钟",
    "notes" to "📓 持久笔记(Agent 记忆)",
    "http_get" to "🌐 网页抓取",
    "web_search" to "🔍 联网搜索",
    "task_plan" to "📋 任务规划",
    "device_info" to "🔋 设备信息",
    "clipboard" to "📋 剪贴板",
    "notify" to "🔔 系统通知",
    "remind" to "⏰ 定时提醒",
    "open_url" to "🌍 打开网页",
)

/**
 * v4.1:设置对话框的 Compose 版,替代旧的 ViewBinding 版(Phase 4 遗留项)。
 *
 * 结构与旧版一一对应:服务商下拉(选中即回填端点/模型)、API Key、上下文
 * 保留与长度、流式开关、系统提示词、Agent 模式、按工具粒度的启停二级
 * 对话框、以及服务商连通性检测(打开即自动检测一次,缓存最近结果)。
 * 保存仍直接写 [SecurePrefs];[onSaved] 由调用方同步 ViewModel 状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    prefs: SecurePrefs,
    toolboxNames: List<String>,
    healthChecker: ProviderHealthChecker,
    healthCache: ProviderHealthCache,
    todayUsage: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    val current = remember { ProviderCatalog.findById(prefs.providerId) }
    var activeProvider by remember { mutableStateOf(current) }
    var providerExpanded by remember { mutableStateOf(false) }

    var apiKey by remember { mutableStateOf(prefs.getApiKey()) }
    var model by remember { mutableStateOf(prefs.model) }
    var endpoint by remember { mutableStateOf(prefs.endpoint) }
    var keepContext by remember { mutableStateOf(prefs.keepContext) }
    var streamOutput by remember { mutableStateOf(prefs.streamOutput) }
    var agentMode by remember { mutableStateOf(prefs.agentMode) }
    var systemPrompt by remember { mutableStateOf(prefs.systemPrompt) }

    val contextLabels = remember { listOf("10 条", "20 条", "50 条", "不限制") }
    val contextValues = remember { mapOf("10 条" to 10, "20 条" to 20, "50 条" to 50, "不限制" to 0) }
    val contextValueToLabel = remember { contextValues.entries.associate { (k, v) -> v to k } }
    var contextLimitLabel by remember {
        mutableStateOf(contextValueToLabel[prefs.contextLimit] ?: "20 条")
    }
    var contextExpanded by remember { mutableStateOf(false) }

    var disabledTools by remember { mutableStateOf(prefs.disabledTools) }
    var showToolPicker by remember { mutableStateOf(false) }

    // v4.2:远程 MCP 服务器(可选)。
    var mcpEndpoint by remember { mutableStateOf(prefs.mcpEndpoint) }
    var mcpToken by remember { mutableStateOf(prefs.mcpToken) }

    var health by remember { mutableStateOf(healthCache.get(current.id)) }
    var checking by remember { mutableStateOf(false) }

    fun check(provider: Provider, apiKeyOverride: String?) {
        checking = true
        health = ProviderHealth.checking(provider.id)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                healthChecker.check(provider, apiKeyOverride)
            }
            healthCache.put(result)
            if (result.providerId == activeProvider.id) {
                health = result
                checking = false
            }
        }
    }

    // 打开即自动检测当前服务商,用户不用点。
    LaunchedEffect(Unit) { check(current, apiKeyOverride = null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("⚙️ 设置") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (todayUsage.isNotEmpty()) {
                    Text("📊 $todayUsage", color = ClawColors.TextSecondary, fontSize = 12.sp)
                }

                // ── 服务商 ─────────────────────────────────────────
                ExposedDropdownMenuBox(
                    expanded = providerExpanded,
                    onExpandedChange = { providerExpanded = it },
                ) {
                    OutlinedTextField(
                        value = activeProvider.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("服务商") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                        colors = dialogFieldColors(),
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = providerExpanded,
                        onDismissRequest = { providerExpanded = false },
                    ) {
                        ProviderCatalog.PROVIDERS.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.displayName) },
                                onClick = {
                                    providerExpanded = false
                                    activeProvider = p
                                    model = p.defaultModel
                                    endpoint = p.defaultEndpoint
                                    check(p, apiKeyOverride = null)
                                },
                            )
                        }
                    }
                }

                if (activeProvider.requiresApiKey) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        colors = dialogFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                activeProvider.apiKeyHelpUrl?.let {
                    Text("获取 API Key:$it", color = ClawColors.TextSecondary, fontSize = 11.sp)
                }

                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("端点") },
                    colors = dialogFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型") },
                    colors = dialogFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // ── 连通性检测 ─────────────────────────────────────
                Text(
                    text = formatHealth(health),
                    color = ClawColors.TextSecondary,
                    fontSize = 12.sp,
                )
                TextButton(onClick = {
                    check(activeProvider, apiKeyOverride = apiKey.trim().ifEmpty { null })
                }) {
                    Text(if (checking) "检测中..." else "🔄 检测连通性", fontSize = 13.sp)
                }

                // ── 行为开关 ───────────────────────────────────────
                SwitchRow("保留上下文", keepContext) { keepContext = it }
                ExposedDropdownMenuBox(
                    expanded = contextExpanded,
                    onExpandedChange = { contextExpanded = it },
                ) {
                    OutlinedTextField(
                        value = contextLimitLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("上下文长度") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = contextExpanded) },
                        colors = dialogFieldColors(),
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = contextExpanded,
                        onDismissRequest = { contextExpanded = false },
                    ) {
                        contextLabels.forEach { label ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    contextExpanded = false
                                    contextLimitLabel = label
                                },
                            )
                        }
                    }
                }
                SwitchRow("流式输出", streamOutput) { streamOutput = it }
                SwitchRow("🦾 Agent 模式", agentMode) { agentMode = it }

                Text(
                    "工具配置:已启用 ${toolboxNames.count { it !in disabledTools }}" +
                        "/${toolboxNames.size}(点击设置)",
                    color = ClawColors.Accent,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showToolPicker = true }
                        .padding(vertical = 6.dp),
                )

                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("系统提示词") },
                    colors = dialogFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp),
                    minLines = 3,
                )

                // ── MCP 远程服务器(可选,v4.2)───────────────────────
                OutlinedTextField(
                    value = mcpEndpoint,
                    onValueChange = { mcpEndpoint = it },
                    label = { Text("🔌 MCP 服务器 URL(可选)") },
                    placeholder = { Text("https://…/mcp", color = ClawColors.TextSecondary, fontSize = 13.sp) },
                    colors = dialogFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Text(
                    "远程工具将以 mcp_ 前缀进入 Agent 爪子集(受工具开关约束)。留空关闭。",
                    color = ClawColors.TextSecondary, fontSize = 11.sp,
                )
                OutlinedTextField(
                    value = mcpToken,
                    onValueChange = { mcpToken = it },
                    label = { Text("MCP Bearer Token(可选)") },
                    colors = dialogFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val picked = ProviderCatalog.findByName(activeProvider.displayName) ?: current
                prefs.providerId = picked.id
                prefs.model = model.trim().ifEmpty { picked.defaultModel }
                prefs.endpoint = endpoint.trim().ifEmpty { picked.defaultEndpoint }
                prefs.setApiKey(apiKey.trim())
                prefs.keepContext = keepContext
                prefs.streamOutput = streamOutput
                prefs.contextLimit = contextValues[contextLimitLabel] ?: 20
                prefs.systemPrompt = systemPrompt.trim()
                prefs.agentMode = agentMode
                prefs.disabledTools = disabledTools
                prefs.mcpEndpoint = mcpEndpoint.trim()
                prefs.mcpToken = mcpToken.trim()
                onSaved()
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )

    if (showToolPicker) {
        ToolPickerDialog(
            toolboxNames = toolboxNames,
            disabled = disabledTools,
            onDismiss = { showToolPicker = false },
            onConfirm = {
                disabledTools = it
                showToolPicker = false
            },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = ClawColors.TextPrimary, fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 统一的字段配色(深色主题)。 */
@Composable
private fun dialogFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = ClawColors.TextPrimary,
    unfocusedTextColor = ClawColors.TextPrimary,
    cursorColor = ClawColors.Accent,
    focusedBorderColor = ClawColors.Accent,
    unfocusedBorderColor = Color(0xFF2a2f3a),
    focusedLabelColor = ClawColors.Accent,
    unfocusedLabelColor = ClawColors.TextSecondary,
)

/** 健康状态 → 单行人话(原 Activity.formatHealth,随对话框迁来)。 */
fun formatHealth(h: ProviderHealth?): String = when (h?.status) {
    null, ProviderHealth.Status.Unknown -> "⚪ 尚未检测"
    ProviderHealth.Status.Checking -> "⏳ 检测中..."
    ProviderHealth.Status.Ok -> "🟢 正常 · ${h.latencyMs}ms"
    ProviderHealth.Status.Slow -> "🟡 较慢 · ${h.latencyMs}ms"
    ProviderHealth.Status.Auth -> "🔴 API Key 无效"
    ProviderHealth.Status.Offline -> "🔴 ${h.message ?: "连不上"}"
    ProviderHealth.Status.HttpError -> "🔴 ${h.message ?: "HTTP ${h.httpCode}"}"
    ProviderHealth.Status.Skipped -> "⚪ 不支持检测"
}

/** 按工具粒度的启停(原 setMultiChoiceItems 的 Compose 版)。 */
@Composable
private fun ToolPickerDialog(
    toolboxNames: List<String>,
    disabled: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val local = remember(disabled) { toolboxNames.associateWith { it !in disabled }.toMutableMap() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("启用哪些工具") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                toolboxNames.forEach { name ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { local[name] = !(local[name] ?: true) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = local[name] ?: true,
                            onCheckedChange = { local[name] = it },
                        )
                        Text(TOOL_LABELS[name] ?: name, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(toolboxNames.filter { !(local[it] ?: true) }.toSet())
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
