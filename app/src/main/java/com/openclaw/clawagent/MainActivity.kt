package com.openclaw.clawagent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import android.widget.TwoLineListItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.openclaw.clawagent.agent.AgentDirective
import com.openclaw.clawagent.agent.AgentToolbox
import com.openclaw.clawagent.conversation.BranchMessage
import com.openclaw.clawagent.conversation.ConversationBranch
import com.openclaw.clawagent.conversation.ConversationStorage
import com.openclaw.clawagent.conversation.ConversationTree
import com.openclaw.clawagent.databinding.ActivityMainBinding
import com.openclaw.clawagent.databinding.DialogSettingsBinding
import com.openclaw.clawagent.provider.ChatService
import com.openclaw.clawagent.provider.Provider
import com.openclaw.clawagent.provider.ProviderCatalog
import com.openclaw.clawagent.provider.ProviderHealth
import com.openclaw.clawagent.provider.ProviderHealthCache
import com.openclaw.clawagent.provider.ProviderHealthChecker
import com.openclaw.clawagent.provider.SecurePrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: MessageAdapter
    private val messages = mutableListOf<ChatMessage>()
    private var isSending = false
    private var sendJob: Job? = null

    private lateinit var prefs: SecurePrefs
    private val chatService = ChatService()
    private val healthChecker = ProviderHealthChecker()
    private val healthCache = ProviderHealthCache()

    // The full tool claw-set (http fetch, notes, device info, clipboard,
    // notifications, reminders, calculator, clock). Built once with the app
    // context; execution happens off the main thread in the agent loop.
    private lateinit var toolbox: AgentToolbox

    // Conversation tree with branches. Always non-null after onCreate; we
    // keep a `messages` mirror of `tree.visibleMessages()` so the existing
    // adapter/data flow keeps working.
    private lateinit var tree: ConversationTree
    private lateinit var storage: ConversationStorage

    // Role / System prompt
    private var currentRoleKey = SystemPromptManager.Role.GENERAL.key

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SecurePrefs(this)
        toolbox = AgentToolbox.forAndroid(applicationContext)
        storage = ConversationStorage(this)
        tree = ConversationTree()
        val loaded = storage.load()
        if (loaded != null) {
            val (branches, activeId) = loaded
            tree.replaceAll(branches, activeId)
        } else {
            // First launch with no branch save: adopt the v1.x single-history
            // blob (SharedPreferences "claw_history") as the root branch so
            // upgrading users keep their existing conversation.
            val legacy = getSharedPreferences("claw_history", MODE_PRIVATE)
                .getString("history", null)
            if (!legacy.isNullOrBlank()) {
                try {
                    val arr = org.json.JSONArray(legacy)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        tree.appendMessage(
                            obj.optString("role", "user"),
                            obj.optString("content", ""),
                        )
                    }
                    tree.renameBranch(tree.activeBranchId, "历史对话")
                    storage.save(tree)
                } catch (_: Exception) {
                }
            }
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        syncMessagesFromTree()
        updateBranchChip()
        updateRoleButton()
        binding.inputField.requestFocus()
    }

    private fun setupRecyclerView() {
        adapter = MessageAdapter(
            messages,
            onCopy = { raw -> copyToClipboard(raw) },
            onMessageLongClick = { position, _ -> showMessageContextMenu(position) },
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.recyclerView.adapter = adapter
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Claw Agent", text))
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun setupListeners() {
        binding.sendBtn.setOnClickListener { sendMessage() }
        binding.inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }
        binding.settingsBtn.setOnClickListener { showSettings() }
        binding.newChatBtn.setOnClickListener { startNewChat() }
        binding.roleBtn.setOnClickListener { showRoleSelector() }
        binding.branchChip.setOnClickListener { showBranchPicker() }
    }

    // ─── Role / System Prompt ───────────────────────────────────────

    private fun updateRoleButton() {
        val role = SystemPromptManager.getRoleDisplayName(currentRoleKey)
        binding.roleBtn.contentDescription = "当前角色: $role"
    }

    private fun showRoleSelector() {
        val roles = SystemPromptManager.getRoleNames()
        AlertDialog.Builder(this)
            .setTitle("选择角色")
            .setItems(roles.toTypedArray()) { _, which ->
                val keys = SystemPromptManager.getRoleKeys()
                if (which in keys.indices) {
                    currentRoleKey = keys[which]
                    prefs.systemPrompt = SystemPromptManager.getPrompt(currentRoleKey)
                    updateRoleButton()
                    Toast.makeText(
                        this,
                        "已切换为：${roles[which]}",
                        Toast.LENGTH_SHORT
                    ).show()
                    messages.add(
                        ChatMessage(
                            "system",
                            "[角色已切换为 ${roles[which]}]"
                        )
                    )
                    adapter.notifyItemInserted(messages.size - 1)
                    updateChatVisibility()
                    scrollToBottom()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateChatVisibility() {
        if (messages.isEmpty()) {
            binding.welcomeLayout.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        } else {
            binding.welcomeLayout.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }
    }

    /**
     * The send button doubles as the stop button: while a request is in
     * flight it cancels the coroutine (the OkHttp call is torn down by
     * callbackFlow's awaitClose), keeping whatever partial text streamed in.
     */
    private fun sendMessage() {
        if (isSending) {
            sendJob?.cancel()
            return
        }
        val text = binding.inputField.text.toString().trim()
        if (text.isEmpty()) return

        isSending = true
        updateSendButton()
        binding.inputField.setText("")

        // Push the user message into the tree BEFORE mirroring to messages,
        // so the new entry is preserved across a save/load round trip even
        // if the process dies mid-stream.
        tree.appendMessage("user", text)
        syncMessagesFromTree()

        // Empty placeholder that the streaming response will fill in.
        val assistantMsg = ChatMessage("assistant", "")
        messages.add(assistantMsg)
        val assistantIndex = messages.size - 1
        adapter.notifyItemInserted(assistantIndex)
        scrollToBottom()

        sendJob = lifecycleScope.launch {
            try {
                val provider = ProviderCatalog.findById(prefs.providerId)
                val apiKey = prefs.getApiKey()

                if (provider.requiresApiKey && apiKey.isEmpty()) {
                    simulateResponse(text, assistantMsg, assistantIndex)
                    return@launch
                }

                val model = prefs.model.trim()
                if (model.isEmpty()) {
                    assistantMsg.content = "⚠️ 还没有配置模型名称。请打开设置,在「模型」一栏填写后再试。"
                    adapter.notifyItemChanged(assistantIndex)
                    return@launch
                }

                val history = buildRequestHistory(text)

                runAgentTurn(history, assistantMsg, assistantIndex, apiKey, model)
            } catch (e: CancellationException) {
                // User hit stop. Keep the partial reply; mark it if empty.
                assistantMsg.content = if (assistantMsg.content.isBlank()) {
                    "⏹ 已停止生成"
                } else {
                    "${assistantMsg.content}\n\n⏹ 已停止"
                }
                adapter.notifyItemChanged(assistantIndex)
            } catch (e: Exception) {
                assistantMsg.content = if (assistantMsg.content.isEmpty()) {
                    "⚠️ 出错了:${e.message}\n\n请检查网络和服务商配置。"
                } else {
                    "${assistantMsg.content}\n\n⚠️ 出错了:${e.message}"
                }
                adapter.notifyItemChanged(assistantIndex)
            } finally {
                // Persist the streaming reply into the active branch. The
                // user message is already there; we just need the assistant's
                // final content.
                tree.activeBranch.messages.add(
                    BranchMessage("assistant", assistantMsg.content)
                )
                saveHistory()
                isSending = false
                sendJob = null
                updateSendButton()
                binding.inputField.requestFocus()
                scrollToBottom()
            }
        }
    }

    /**
     * Context fed to the API: everything (minus the placeholder) when context
     * memory is on, truncated to [SecurePrefs.contextLimit] entries so token
     * cost and request latency stay bounded on long conversations; just the
     * latest user turn when context memory is off. A configured system prompt
     * (the agent's persona) always leads the request, regardless of limits.
     */
    private fun buildRequestHistory(text: String): List<ChatService.Message> {
        val systemMessages = buildList {
            val persona = prefs.systemPrompt.trim()
            if (persona.isNotEmpty()) add(ChatService.Message("system", persona))
            // Agent mode: tell the model it is an agent and hand it the live
            // toolbox inventory, so it plans multi-step tool work.
            if (prefs.agentMode) {
                add(ChatService.Message("system", AgentDirective.systemPrompt(toolbox)))
            }
        }
        if (!prefs.keepContext) {
            return systemMessages + listOf(ChatService.Message("user", text))
        }
        val base = messages.dropLast(1).map { ChatService.Message(it.role, it.content) }
        val limit = prefs.contextLimit
        val limited = if (limit > 0) base.takeLast(limit) else base
        return systemMessages + limited
    }

    /**
     * The agent loop: stream a model reply; if the model requests tool calls
     * (OpenAI function calling), execute them locally, feed the results back
     * as `role:"tool"` messages and continue — up to [MAX_TOOL_ROUNDS] model
     * rounds per user turn. The chat bubble shows every tool invocation and
     * its result inline, then the model's final answer streams below it.
     */
    private suspend fun runAgentTurn(
        initialHistory: List<ChatService.Message>,
        assistantMsg: ChatMessage,
        assistantIndex: Int,
        apiKey: String,
        model: String,
    ) {
        val conversation = initialHistory.toMutableList()
        val toolsJson = if (prefs.agentMode) toolbox.requestJson() else null

        var round = 0
        while (true) {
            round++
            var roundContent = ""   // raw model text produced this round
            var requestedCalls: List<ChatService.ToolCall>? = null
            var failed = false

            chatService.streamChat(
                endpoint = prefs.endpoint,
                apiKey = apiKey,
                model = model,
                history = conversation,
                stream = prefs.streamOutput,
                tools = toolsJson,
            ).collect { event ->
                when (event) {
                    is ChatService.StreamEvent.Delta -> {
                        roundContent += event.text
                        assistantMsg.content += event.text
                        adapter.notifyItemChanged(assistantIndex)
                        scrollToBottom()
                    }
                    is ChatService.StreamEvent.ToolCalls -> {
                        requestedCalls = event.calls
                        // Transparent tool trace in the bubble.
                        if (assistantMsg.content.isNotEmpty() &&
                            !assistantMsg.content.endsWith("\n")
                        ) {
                            assistantMsg.content += "\n\n"
                        }
                        event.calls.forEach { call ->
                            assistantMsg.content += "🔧 ${call.name}(${call.arguments})\n"
                        }
                        adapter.notifyItemChanged(assistantIndex)
                        scrollToBottom()
                    }
                    is ChatService.StreamEvent.Error -> {
                        failed = true
                        // Keep whatever already streamed in — replacing it
                        // would throw away paid tokens the user just paid for.
                        assistantMsg.content = if (assistantMsg.content.isEmpty()) {
                            "⚠️ ${event.message}"
                        } else {
                            "${assistantMsg.content}\n\n⚠️ ${event.message}"
                        }
                        adapter.notifyItemChanged(assistantIndex)
                    }
                    ChatService.StreamEvent.Done -> {
                        scrollToBottom()
                    }
                }
            }

            if (failed) return
            val calls = requestedCalls
            if (calls.isNullOrEmpty()) return   // final answer complete

            if (round >= MAX_TOOL_ROUNDS) {
                assistantMsg.content += "\n\n⚠️ 已连续调用工具 $round 轮,为避免死循环已停止。"
                adapter.notifyItemChanged(assistantIndex)
                return
            }

            // Record the tool-call request, then execute each tool locally.
            conversation += ChatService.Message(
                role = "assistant",
                content = roundContent,
                toolCalls = calls,
            )
            for (call in calls) {
                val result = withContext(Dispatchers.Default) {
                    runCatching { toolbox.execute(call.name, call.arguments) }
                        .getOrElse { "工具执行失败:${it.message}" }
                }
                // Bubble shows a short preview; the full result always goes
                // back to the model — long fetches would flood the chat UI.
                assistantMsg.content += "↳ " + previewOf(result) + "\n"
                adapter.notifyItemChanged(assistantIndex)
                scrollToBottom()
                conversation += ChatService.Message(
                    role = "tool",
                    content = result,
                    toolCallId = call.id,
                    toolName = call.name,
                )
            }
            assistantMsg.content += "\n"
        }
    }

    /** Tool-result preview for the chat bubble: short head + full-size note. */
    private fun previewOf(result: String): String =
        if (result.length <= RESULT_PREVIEW_CHARS) {
            result
        } else {
            result.take(RESULT_PREVIEW_CHARS) +
                "…(共 ${result.length} 字符,已完整提供给模型)"
        }

    private fun updateSendButton() {
        if (isSending) {
            binding.sendBtn.setImageResource(R.drawable.ic_stop)
            binding.sendBtn.imageTintList =
                android.content.res.ColorStateList.valueOf(Color.argb(255, 239, 68, 68))
            binding.sendBtn.contentDescription = "停止生成"
        } else {
            binding.sendBtn.setImageResource(R.drawable.ic_send)
            binding.sendBtn.imageTintList =
                android.content.res.ColorStateList.valueOf(Color.WHITE)
            binding.sendBtn.contentDescription = "发送"
        }
    }

    private suspend fun simulateResponse(text: String, msg: ChatMessage, index: Int) {
        val reply = when {
            text.contains("你好") || text.lowercase().contains("hello") || text.lowercase().contains("hi") ->
                "你好呀!👋\n\n我是 **Claw Agent** 🦀\n\n我目前运行在演示模式。配置 API Key 后就能使用完整 AI 功能了!"

            text.contains("天气") ->
                "🌤️ 天气查询\n\n演示模式下无法获取实时天气。配置 API Key 后,我就能帮你查询全球任意城市的天气啦!"

            text.contains("笑话") || text.contains("搞笑") ->
                "😄 来一个:\n\n为什么程序员总是分不清万圣节和圣诞节?\n\n因为 **Oct 31 == Dec 25** 🎃🎄\n\n(八进制的 31 = 十进制的 25)"

            text.contains("代码") || text.lowercase().contains("python") || text.contains("编程") ->
                "🐍 快速排序示例:\n\n```python\ndef quick_sort(arr):\n    if len(arr) <= 1: return arr\n    pivot = arr[len(arr) // 2]\n    left = [x for x in arr if x < pivot]\n    middle = [x for x in arr if x == pivot]\n    right = [x for x in arr if x > pivot]\n    return quick_sort(left) + middle + quick_sort(right)\n```"

            text.contains("量子") ->
                "⚛️ **量子计算** 简介\n\n• **量子比特** — 可同时处于 0 和 1 的叠加态\n• **叠加** — 同时表示多种状态\n• **纠缠** — 两个比特的神秘关联\n• **量子干涉** — 放大正确答案概率\n\n配置 API Key 后可获取更详细解释!"

            else ->
                "你好!我是 **Claw Agent** 🦀\n\n我目前运行在演示模式。请在设置中配置 API Key 来解锁完整 AI 能力。\n\n支持的服务商:OpenAI / DeepSeek / 通义千问 / Moonshot / 智谱 / Stepfun / OpenRouter / Pollinations / Ollama 等。"
        }

        // Typewriter effect.
        for (i in 1..reply.length) {
            msg.content = reply.substring(0, i)
            adapter.notifyItemChanged(index)
            scrollToBottom()
            kotlinx.coroutines.delay(15)
        }
    }

    private fun scrollToBottom() {
        if (messages.isEmpty()) return
        binding.recyclerView.post {
            // Instant scroll during streaming: smooth-scroll spam on every
            // delta is janky and piles up animation requests.
            binding.recyclerView.scrollToPosition(messages.size - 1)
        }
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

    /**
     * Branch mutations (switch / fork / new / delete) while a reply is
     * streaming would rebuild the message mirror and detach the live
     * placeholder bubble — its notifyItemChanged index would go stale and
     * the finally-block would append the reply to the wrong branch. Block
     * them until the stream ends. Returns true (with a toast) when busy.
     */
    private fun blockIfSending(): Boolean {
        if (!isSending) return false
        Toast.makeText(this, "生成中,请先停止再切换或新建分支", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun startNewChat() {
        if (blockIfSending()) return
        // Open a new branch off the current active one, inheriting zero
        // messages — i.e. a clean slate while preserving the old thread.
        // If the active branch is already empty, we just no-op rather than
        // spamming the user with empty "Branch N" entries.
        if (tree.activeBranch.messages.isEmpty() && tree.allBranches.size == 1) {
            binding.inputField.requestFocus()
            return
        }
        val newBranch = tree.forkAt(messageIndex = 0, name = "新对话 ${tree.allBranches.size + 1}")
        syncMessagesFromTree()
        updateBranchChip()
        saveHistory()
        binding.inputField.requestFocus()
        Toast.makeText(this, "已开新分支:${newBranch.name}", Toast.LENGTH_SHORT).show()
    }

    private fun saveHistory() {
        // Persist the full conversation tree. Storage caps each branch's
        // own message count; combined with the existing MAX_SAVED_MESSAGES
        // cap, we stay well within SharedPreferences' comfortable range.
        capActiveBranch()
        storage.save(tree)
    }

    private fun loadHistory() {
        // loadHistory is now invoked from onCreate, which already calls
        // syncMessagesFromTree() right after. We keep this method (no-op)
        // so any external caller doesn't have to change.
    }

    /**
     * If the active branch's own messages exceed [MAX_SAVED_MESSAGES], drop
     * the oldest ones. The inherited prefix is always preserved.
     */
    private fun capActiveBranch() {
        val msgs = tree.activeBranch.messages
        if (msgs.size > MAX_SAVED_MESSAGES) {
            val toDrop = msgs.size - MAX_SAVED_MESSAGES
            repeat(toDrop) { msgs.removeAt(0) }
        }
    }

    /**
     * Mirror [tree.visibleMessages] into the [messages] list and refresh
     * the adapter. Call this after any change to the tree.
     */
    private fun syncMessagesFromTree() {
        messages.clear()
        for (m in tree.visibleMessages()) {
            messages.add(ChatMessage(m.role, m.content))
        }
        adapter.notifyDataSetChanged()
        updateChatVisibility()
        if (messages.isNotEmpty()) scrollToBottom()
    }

    /**
     * Update the chip in the header to show the active branch's name. The
     * 🌿 icon hints at the branching metaphor.
     */
    private fun updateBranchChip() {
        val total = tree.allBranches.size
        val name = tree.activeBranch.name
        binding.branchChip.text = if (total > 1) "🌿 $name ($total)" else "🌿 $name"
    }

    /**
     * Show a bottom sheet–style dialog with all branches, the current one
     * marked. Tapping a non-active branch switches to it; long-press gives
     * a delete/rename option for non-root branches.
     */
    private fun showBranchPicker() {
        val branches = tree.allBranches
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val titles = branches.map { b ->
            val marker = if (b.id == tree.activeBranchId) "● " else "  "
            val parent = if (b.parentId != null) " ⤴" else ""
            "$marker${b.name}$parent"
        }
        val metas = branches.map { b ->
            "${b.messages.size} 条消息 · 创建于 ${fmt.format(Date(b.createdAt))}"
        }

        val listView = ListView(this)
        listView.adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_2, titles
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TwoLineListItem).apply {
                    text1.text = titles[position]
                    text1.setTextColor(0xFFe2e8f0.toInt())
                    text1.textSize = 15f
                    text2.text = metas[position]
                    text2.setTextColor(0xFF64748b.toInt())
                    text2.textSize = 11f
                }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("会话分支")
            .setView(listView)
            .setPositiveButton("＋ 新分支") { _, _ -> startNewChat() }
            .setNeutralButton("📤 导出当前分支") { _, _ -> exportActiveBranch() }
            .setNegativeButton("关闭", null)
            .show()

        listView.setOnItemClickListener { _, _, position, _ ->
            val target = branches[position]
            if (target.id != tree.activeBranchId && !blockIfSending()) {
                tree.switchTo(target.id)
                syncMessagesFromTree()
                updateBranchChip()
                storage.save(tree)
                dialog.dismiss()
                Toast.makeText(this, "切换到 ${target.name}", Toast.LENGTH_SHORT).show()
            }
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val target = branches[position]
            if (target.parentId == null) {
                Toast.makeText(this, "根分支不可删除", Toast.LENGTH_SHORT).show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("删除分支")
                    .setMessage("删除「${target.name}」？其子分支将并入上级分支。")
                    .setPositiveButton("删除") { _, _ ->
                        if (blockIfSending()) return@setPositiveButton
                        tree.deleteBranch(target.id)
                        syncMessagesFromTree()
                        updateBranchChip()
                        storage.save(tree)
                        dialog.dismiss()
                        Toast.makeText(this, "已删除 ${target.name}", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            true
        }
    }

    /** Share the active branch's effective conversation as plain text. */
    private fun exportActiveBranch() {
        val msgs = tree.visibleMessages()
        if (msgs.isEmpty()) {
            Toast.makeText(this, "当前分支没有消息", Toast.LENGTH_SHORT).show()
            return
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val text = buildString {
            appendLine("Claw Agent 对话导出 — ${tree.activeBranch.name}")
            appendLine("（${fmt.format(Date(System.currentTimeMillis()))}）")
            appendLine("────────────────────")
            msgs.forEach { m ->
                appendLine()
                appendLine("【${if (m.role == "user") "我" else "Claw"}】${m.content}")
            }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_TITLE, "Claw Agent — ${tree.activeBranch.name}")
        }
        startActivity(Intent.createChooser(send, "导出对话"))
    }

    /**
     * Long-press a message → offer "Fork from here". The new branch
     * inherits every message up to and including the tapped index.
     */
    private fun showMessageContextMenu(position: Int) {
        val items = arrayOf("🌿 从这里重开", "📋 复制")
        AlertDialog.Builder(this)
            .setTitle("消息操作")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> if (!blockIfSending()) forkAt(position)
                    1 -> { /* copy is handled by adapter default */ }
                }
            }
            .show()
    }

    /**
     * Create a new branch starting at [position] (inclusive) of the
     * effective message list and switch to it. Returns the new branch.
     */
    private fun forkAt(position: Int): ConversationBranch {
        val newBranch = tree.forkAt(position + 1) // +1 to include this message
        syncMessagesFromTree()
        updateBranchChip()
        storage.save(tree)
        Toast.makeText(this, "已开新分支:${newBranch.name}", Toast.LENGTH_SHORT).show()
        return newBranch
    }

    @Suppress("UNUSED")
    fun sendQuick(view: View) {
        if (isSending) return
        val text = when (view.id) {
            R.id.btnQuick1 -> "今天天气怎么样?"
            R.id.btnQuick2 -> "给我讲个笑话"
            R.id.btnQuick3 -> "帮我写一段Python代码"
            R.id.btnQuick4 -> "解释一下量子计算"
            else -> ""
        }
        if (text.isNotEmpty()) {
            binding.inputField.setText(text)
            sendMessage()
        }
    }

    /**
     * Run a one-shot health check for [provider] on a background thread, then
     * cache the result and post [onResult] back on the main thread. The
     * callback is responsible for guarding against stale results (i.e. the
     * user may have switched providers in the meantime).
     */
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

    /**
     * Format a [ProviderHealth] for the inline text row in the settings
     * dialog. Keep the string short so it fits next to the check button.
     */
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
        private const val MAX_SAVED_MESSAGES = 300

        /** Cap on model rounds per user turn in agent mode (tool-call loop). */
        private const val MAX_TOOL_ROUNDS = 15
        private const val RESULT_PREVIEW_CHARS = 300
    }
}
