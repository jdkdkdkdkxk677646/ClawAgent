package com.openclaw.clawagent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.openclaw.clawagent.databinding.DialogSettingsBinding
import com.openclaw.clawagent.provider.Provider
import com.openclaw.clawagent.provider.ProviderCatalog
import com.openclaw.clawagent.provider.ProviderHealth
import com.openclaw.clawagent.provider.ProviderHealthCache
import com.openclaw.clawagent.provider.ProviderHealthChecker
import com.openclaw.clawagent.provider.SecurePrefs
import com.openclaw.clawagent.provider.UsageTracker
import com.openclaw.clawagent.ui.ClawColors
import com.openclaw.clawagent.ui.ChatEffect
import com.openclaw.clawagent.ui.ChatIntent
import com.openclaw.clawagent.ui.ChatScreen
import com.openclaw.clawagent.ui.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v4.0 Phase 3:Compose 宿主。
 *
 * 业务逻辑全部在 [ChatViewModel](MVI:Intent → State/Effect);消息列表通过
 * AndroidView 复用旧 [MessageAdapter] 的 Markdown/表格渲染管线;设置对话框
 * 暂保留 View 版(DialogSettingsBinding),Phase 4 迁 Compose。
 */
class MainActivity : ComponentActivity() {

    private lateinit var prefs: SecurePrefs
    private val healthChecker = ProviderHealthChecker()
    private val healthCache = ProviderHealthCache()

    // 设置对话框里的工具配置需要工具清单;与 VM 内的实例各自独立(工具无共享可变状态,
    // NoteTool 共享同一磁盘目录,行为一致)。
    private val toolbox by lazy { com.openclaw.clawagent.agent.AgentWiring.forAndroid(this) }

    private val vm: ChatViewModel by viewModels { ChatViewModel.factory(this) }

    // 消息列表:RecyclerView + MessageAdapter 原样复用(容器换成 Compose)
    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: MessageAdapter
    private lateinit var recyclerView: RecyclerView

    private val galleryPicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> vm.onGalleryResult(uri) }

    private val takePicture = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok -> vm.onCameraResult(ok) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SecurePrefs(this)

        adapter = MessageAdapter(
            messages,
            onCopy = { text ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Claw Agent", text))
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            },
            onMessageLongClick = { position, _ -> showForkMenu(position) },
        )

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = ClawColors.Bg) {
                    ChatScreen(
                        state = vm.state.collectAsStateWithLifecycle().value,
                        onIntent = vm::onIntent,
                        onOpenSettings = { showSettings() },
                        onAttachClick = {
                            galleryPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onAttachLongClick = {
                            vm.prepareCamera()?.let { effect ->
                                runCatching { takePicture.launch(effect.uri) }
                                    .onFailure { vm.onCameraResult(false) }
                            }
                        },
                        messagesList = { MessagesList() },
                    )
                }
            }
        }

        // 副作用:Toast / 选择器 / 剪贴板 / 滚动
        lifecycleScope.launch {
            vm.effects.collect { effect ->
                when (effect) {
                    is ChatEffect.Toast ->
                        Toast.makeText(this@MainActivity, effect.message, Toast.LENGTH_SHORT).show()
                    is ChatEffect.LaunchGalleryPicker ->
                        galleryPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    is ChatEffect.LaunchCamera -> {
                        runCatching { takePicture.launch(effect.uri) }
                            .onFailure { vm.onCameraResult(false) }
                    }
                    is ChatEffect.CopyToClipboard -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Claw Agent", effect.text))
                        Toast.makeText(this@MainActivity, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }
                    is ChatEffect.ScrollToBottom ->
                        recyclerView.post { recyclerView.scrollToPosition(messages.size - 1) }
                    is ChatEffect.OpenSettings -> showSettings()
                }
            }
        }

        // state → adapter 镜像(全量刷新;alpha 阶段够用)
        lifecycleScope.launch {
            vm.state.collect { st ->
                messages.clear()
                messages.addAll(st.messages)
                adapter.notifyDataSetChanged()
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun MessagesList() {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                RecyclerView(ctx).apply {
                    layoutManager = LinearLayoutManager(ctx).apply { stackFromEnd = true }
                    adapter = this@MainActivity.adapter
                    recyclerView = this
                }
            },
            update = { rv -> rv.post { rv.scrollToPosition(messages.size - 1) } },
        )
    }

    private fun showForkMenu(position: Int) {
        val items = arrayOf("🌿 从这里重开", "📋 复制")
        showItemMenu(items, position)
    }

    private fun showItemMenu(items: Array<String>, position: Int) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("消息操作")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> vm.onIntent(ChatIntent.ForkAt(position))
                    1 -> vm.onIntent(ChatIntent.CopyAt(position))
                }
            }
            .show()
    }

    private fun showSettings() {
        val dialogBinding = DialogSettingsBinding.inflate(layoutInflater)
        val current = ProviderCatalog.findById(prefs.providerId)
        val providerNames = ProviderCatalog.PROVIDERS.map { it.displayName }

        // Provider dropdown
        val adapterSpinner = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            providerNames,
        )
        dialogBinding.providerDropdown.setAdapter(adapterSpinner)
        dialogBinding.providerDropdown.setText(current.displayName, false)

        // Render the current health row from the cache (so reopening the
        // dialog shows the last known result without re-pinging the network).
        var activeProvider: Provider = current
        fun renderHealth(p: Provider) {
            val h = healthCache.get(p.id)
            dialogBinding.providerHealthText.text = formatHealth(h)
        }
        fun applyProvider(p: Provider) {
            dialogBinding.modelEdit.setText(p.defaultModel)
            dialogBinding.endpointEdit.setText(p.defaultEndpoint)
            dialogBinding.providerHelp.text = p.apiKeyHelpUrl?.let { "获取 API Key:$it" } ?: ""
            // For providers that don't require auth, hide the Key field.
            dialogBinding.apiKeyLayout.visibility =
                if (p.requiresApiKey) View.VISIBLE else View.GONE
            activeProvider = p
            renderHealth(p)
        }
        applyProvider(current)

        // Auto-check the currently selected provider the moment the dialog
        // opens, so the user sees fresh data without clicking.
        runHealthCheck(current, apiKeyOverride = null) { fresh ->
            if (fresh.providerId == activeProvider.id) {
                dialogBinding.providerHealthText.text = formatHealth(fresh)
            }
        }

        dialogBinding.btnCheckHealth.setOnClickListener {
            // Use the current input value (not yet saved) so the user can
            // re-check right after typing a fresh key.
            val keyOverride = dialogBinding.apiKeyEdit.text.toString().trim()
                .ifEmpty { null }
            runHealthCheck(activeProvider, apiKeyOverride = keyOverride) { fresh ->
                if (fresh.providerId == activeProvider.id) {
                    dialogBinding.providerHealthText.text = formatHealth(fresh)
                    val toast = when (fresh.status) {
                        ProviderHealth.Status.Ok -> "✅ ${fresh.latencyMs}ms"
                        ProviderHealth.Status.Slow -> "🐢 ${fresh.latencyMs}ms 较慢"
                        ProviderHealth.Status.Auth -> "🔑 API Key 无效"
                        ProviderHealth.Status.Offline -> "📡 连不上 (${fresh.message ?: "网络错误"})"
                        ProviderHealth.Status.HttpError -> "❌ ${fresh.message ?: "HTTP ${fresh.httpCode}"}"
                        ProviderHealth.Status.Checking -> "🔄 检测中..."
                        ProviderHealth.Status.Skipped -> "不支持检测"
                        ProviderHealth.Status.Unknown -> "未检测"
                    }
                    Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialogBinding.providerDropdown.setOnItemClickListener { _, _, position, _ ->
            val picked = ProviderCatalog.PROVIDERS[position]
            applyProvider(picked)
            // Re-check on selection change so the user always sees the
            // state of the provider they're about to switch to.
            runHealthCheck(picked, apiKeyOverride = null) { fresh ->
                if (fresh.providerId == activeProvider.id) {
                    dialogBinding.providerHealthText.text = formatHealth(fresh)
                }
            }
        }

        // Context length dropdown
        val contextOptions = listOf("10 条", "20 条", "50 条", "不限制")
        val contextLabels = mapOf("10 条" to 10, "20 条" to 20, "50 条" to 50, "不限制" to 0)
        val currentLimit = prefs.contextLimit
        val currentLimitLabel = when (currentLimit) {
            0 -> "不限制"
            10 -> "10 条"
            20 -> "20 条"
            50 -> "50 条"
            else -> null
        } ?: "20 条" // custom legacy value: snap to nearest option
        dialogBinding.contextLimitDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, contextOptions)
        )
        dialogBinding.contextLimitDropdown.setText(currentLimitLabel, false)

        dialogBinding.apiKeyEdit.setText(prefs.getApiKey())
        dialogBinding.contextCheckbox.isChecked = prefs.keepContext
        dialogBinding.streamCheckbox.isChecked = prefs.streamOutput
        dialogBinding.systemPromptEdit.setText(prefs.systemPrompt)
        dialogBinding.agentCheckbox.isChecked = prefs.agentMode

        // Per-tool kill switches: summary line + multi-choice dialog over
        // the toolbox, persisted as a disabled-names set in SecurePrefs.
        val toolNames = toolbox.names
        fun renderToolsSummary() {
            val enabled = toolNames.count { prefs.isToolEnabled(it) }
            dialogBinding.toolsConfigText.text =
                "工具配置:已启用 $enabled/${toolNames.size}(点击设置)"
        }
        renderToolsSummary()
        dialogBinding.toolsConfigText.setOnClickListener {
            val labels = toolNames.map { TOOL_LABELS[it] ?: it }.toTypedArray()
            val checked = BooleanArray(toolNames.size) { prefs.isToolEnabled(toolNames[it]) }
            AlertDialog.Builder(this)
                .setTitle("启用哪些工具")
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton("保存") { _, _ ->
                    prefs.disabledTools =
                        toolNames.filterIndexed { i, _ -> !checked[i] }.toSet()
                    renderToolsSummary()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        AlertDialog.Builder(this)
            .setTitle("⚙️ 设置")
            .setView(dialogBinding.root)
            .setPositiveButton("保存") { _, _ ->
                val pickedName = dialogBinding.providerDropdown.text.toString()
                val picked = ProviderCatalog.findByName(pickedName) ?: current
                prefs.providerId = picked.id
                prefs.model = dialogBinding.modelEdit.text.toString().trim()
                    .ifEmpty { picked.defaultModel }
                prefs.endpoint = dialogBinding.endpointEdit.text.toString().trim()
                    .ifEmpty { picked.defaultEndpoint }
                prefs.setApiKey(dialogBinding.apiKeyEdit.text.toString().trim())
                prefs.keepContext = dialogBinding.contextCheckbox.isChecked
                prefs.streamOutput = dialogBinding.streamCheckbox.isChecked
                prefs.contextLimit = contextLabels[dialogBinding.contextLimitDropdown.text.toString()]
                    ?: 20
                prefs.systemPrompt = dialogBinding.systemPromptEdit.text.toString().trim()
                prefs.agentMode = dialogBinding.agentCheckbox.isChecked
                Toast.makeText(this, "已保存:${picked.displayName}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runHealthCheck(
        provider: Provider,
        apiKeyOverride: String?,
        onResult: (ProviderHealth) -> Unit,
    ) {
        healthCache.put(ProviderHealth.checking(provider.id))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                healthChecker.check(provider, apiKeyOverride)
            }
            healthCache.put(result)
            onResult(result)
        }
    }

    private fun formatHealth(h: ProviderHealth): String = when (h.status) {
        ProviderHealth.Status.Unknown -> "⚪ 尚未检测"
        ProviderHealth.Status.Checking -> "⏳ 检测中..."
        ProviderHealth.Status.Ok -> "🟢 正常 · ${h.latencyMs}ms"
        ProviderHealth.Status.Slow -> "🟡 较慢 · ${h.latencyMs}ms"
        ProviderHealth.Status.Auth -> "🔴 API Key 无效"
        ProviderHealth.Status.Offline -> "🔴 ${h.message ?: "连不上"}"
        ProviderHealth.Status.HttpError -> "🔴 ${h.message ?: "HTTP ${h.httpCode}"}"
        ProviderHealth.Status.Skipped -> "⚪ 不支持检测"
    }

    companion object {
        /** Friendly labels for the tool-configuration multi-choice dialog. */
private val TOOL_LABELS = mapOf(
            "calculator" to "🧮 计算器",
            "current_time" to "🕐 实时时钟",
            "notes" to "📓 持久笔记(Agent 记忆)",
            "http_get" to "🌐 网页/API 抓取",
            "web_search" to "🔍 联网搜索",
            "task_plan" to "📋 任务规划与进度",
            "device_info" to "🔋 设备信息",
            "clipboard" to "📋 剪贴板读写",
            "notify" to "🔔 系统通知",
            "remind" to "⏰ 定时提醒",
            "open_url" to "🌍 打开网页",
        )

    }
}
