package com.openclaw.clawagent.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openclaw.clawagent.ChatMessage
import com.openclaw.clawagent.ImageAttachments
import com.openclaw.clawagent.SystemPromptManager
import com.openclaw.clawagent.agent.AgentDirective
import com.openclaw.clawagent.agent.AgentEvent
import com.openclaw.clawagent.agent.AgentLoop
import com.openclaw.clawagent.agent.AgentRequest
import com.openclaw.clawagent.agent.AgentToolbox
import com.openclaw.clawagent.agent.AgentWiring
import com.openclaw.clawagent.conversation.BranchMessage
import com.openclaw.clawagent.conversation.ConversationStorage
import com.openclaw.clawagent.conversation.ConversationTree
import com.openclaw.clawagent.provider.ChatService
import com.openclaw.clawagent.provider.ProviderCatalog
import com.openclaw.clawagent.provider.SecurePrefs
import com.openclaw.clawagent.task.AgentTaskService
import com.openclaw.clawagent.task.ChatRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 屏幕快照,Compose 唯一的渲染依据。 */
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val branches: List<BranchUi> = emptyList(),
    val activeBranchName: String = "",
    val roleKey: String = "general",
    val roleLabel: String = "通用助手",
    val isSending: Boolean = false,
    /** 会话树从 Room 加载中;加载完成前禁止发送/切分支,防写入竞态。 */
    val isLoading: Boolean = false,
    val stagedImageCount: Int = 0,
    /** 今日 token 台账(UsageTracker.todaySummary()),v4.1 起 UI 展示。 */
    val todayUsage: String = "",
    /** MCP 远程服务器状态(v4.2):空=未配置,其余为连接结果一行话。 */
    val mcpStatus: String = "",
    /** 发送栏的后台执行 toggle(v4.3):开着→发送即移交前台服务。 */
    val backgroundTask: Boolean = false,
    /** 已有任务在后台跑(期间禁止发送,避免两路同时写会话树)。 */
    val backgroundTaskRunning: Boolean = false,
) {
    val showWelcome: Boolean get() = messages.isEmpty()
    val canSend: Boolean get() = !isSending && !isLoading && !backgroundTaskRunning
}

data class BranchUi(val id: String, val name: String, val messageCount: Int, val isActive: Boolean)

/** 用户意图:UI → ViewModel,单向。 */
sealed class ChatIntent {
    data class SendMessage(val text: String) : ChatIntent()
    data object StopGeneration : ChatIntent()
    data object NewBranch : ChatIntent()
    data class SwitchBranch(val branchId: String) : ChatIntent()
    data class DeleteBranch(val branchId: String) : ChatIntent()
    data class ForkAt(val position: Int) : ChatIntent()
    data class SetRole(val roleKey: String) : ChatIntent()
    data object ClearStagedImages : ChatIntent()
    data class CopyAt(val position: Int) : ChatIntent()
    data object ToggleBackground : ChatIntent()
    data object ReloadFromRepository : ChatIntent()
}

/** 一次性副作用,由 Activity 消费(Toast/选择器/剪贴板/分享)。 */
sealed class ChatEffect {
    data class Toast(val message: String) : ChatEffect()
    data object LaunchGalleryPicker : ChatEffect()
    data class LaunchCamera(val uri: Uri, val tempFile: File) : ChatEffect()
    data class CopyToClipboard(val text: String) : ChatEffect()
    data object OpenSettings : ChatEffect()
    data object ScrollToBottom : ChatEffect()
}

class ChatViewModel(
    private val appContext: android.content.Context,
    private val prefs: SecurePrefs,
    private val storage: ConversationStorage,
    private val chatService: ChatService,
    private val agentLoop: AgentLoop,
    private val usageTracker: com.openclaw.clawagent.provider.UsageTracker,
) : ViewModel() {

    private val toolbox: AgentToolbox = AgentWiring.forAndroid(appContext)

    // v4.3:树的所有权在 ChatRepository(进程单例)——后台任务 Service 与本 VM
    // 共享同一个对象,Service 完成后 VM 回前台 syncMessages 即见结果。
    private val tree get() = com.openclaw.clawagent.task.ChatRepository.tree

    private val _state = MutableStateFlow(
        ChatUiState(
            isLoading = true,
            roleLabel = SystemPromptManager.getRoleDisplayName(prefs.roleKey),
            todayUsage = usageTracker.todaySummary(),
        )
    )
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _effects = Channel<ChatEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private val pendingImages = mutableListOf<String>()
    private var pendingPhotoFile: File? = null
    private var sendJob: Job? = null

    // ── MCP(v4.2):远程爪子,握手成功后才进工具清单 ──────────────────
    @Volatile private var mcpTools: List<com.openclaw.clawagent.agent.AgentTool> = emptyList()
    @Volatile private var mcpClient: com.openclaw.clawagent.mcp.McpClient? = null

    init {
        // v4.1:加载改异步(Room suspend DAO)。加载完成前 isLoading=true,
        // 发送与分支操作被 canSend=false 挡住,避免空树覆盖磁盘的竞态。
        viewModelScope.launch {
            val loaded = storage.load()
            if (loaded != null) {
                val (branches, activeId) = loaded
                tree.replaceAll(branches, activeId)
            }
            syncMessages()
            _state.value = _state.value.copy(isLoading = false)
            publish()
            connectMcpIfConfigured()
        }
    }

    /**
     * 配置了 MCP 服务器就握手并把远程工具挂进爪子集。失败静默降级:
     * 内置工具照常可用,状态行告知用户原因。
     */
    private fun connectMcpIfConfigured() {
        val endpoint = prefs.mcpEndpoint.trim()
        if (endpoint.isEmpty()) {
            disconnectMcp()
            return
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                mcpClient?.close()
                val client = com.openclaw.clawagent.mcp.McpClient(
                    endpoint = endpoint,
                    authToken = prefs.mcpToken.trim().ifEmpty { null },
                )
                client.connect()
                val defs = client.listTools()
                mcpClient = client
                mcpTools = com.openclaw.clawagent.mcp.McpToolBridge.bridgeAll(client, defs)
                _state.value = _state.value.copy(
                    mcpStatus = "🔌 MCP 已连接:${client.serverInfo.ifEmpty { endpoint }} · ${defs.size} 只远程爪子"
                )
            } catch (e: Exception) {
                mcpClient = null
                mcpTools = emptyList()
                _state.value = _state.value.copy(
                    mcpStatus = "🔌 MCP 连接失败:${e.message ?: e.javaClass.simpleName}(内置工具不受影响)"
                )
            }
        }
    }

    private fun disconnectMcp() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { mcpClient?.close() }
        mcpClient = null
        mcpTools = emptyList()
    }

    // ── intents ──────────────────────────────────────────────────

    fun onIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.SendMessage -> sendMessage(intent.text)
            ChatIntent.StopGeneration -> sendJob?.cancel()
            ChatIntent.NewBranch -> startNewBranch()
            is ChatIntent.SwitchBranch -> switchBranch(intent.branchId)
            is ChatIntent.DeleteBranch -> deleteBranch(intent.branchId)
            is ChatIntent.ForkAt -> forkAt(intent.position)
            is ChatIntent.SetRole -> setRole(intent.roleKey)
            ChatIntent.ClearStagedImages -> {
                pendingImages.clear()
                publish()
            }
            is ChatIntent.CopyAt -> copyAt(intent.position)
            ChatIntent.ToggleBackground -> {
                _state.value = _state.value.copy(backgroundTask = !_state.value.backgroundTask)
            }
            ChatIntent.ReloadFromRepository -> {
                syncMessages()
                _state.value = _state.value.copy(
                    backgroundTaskRunning = ChatRepository.backgroundTaskRunning,
                    todayUsage = usageTracker.todaySummary(),
                )
                publish()
            }
        }
    }

    /** 设置对话框保存后同步 UI;MCP 配置变化时重连远程爪子。 */
    fun refreshFromPrefs() {
        connectMcpIfConfigured()
        publish()
    }

    // ── images ───────────────────────────────────────────────────

    fun prepareCamera(): ChatEffect.LaunchCamera? {
        val dir = File(appContext.cacheDir, "photos").apply { mkdirs() }
        val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(Date()) + ".jpg"
        val file = File(dir, name)
        val uri: Uri = try {
            androidx.core.content.FileProvider.getUriForFile(
                appContext, "${appContext.packageName}.fileprovider", file
            )
        } catch (e: Exception) {
            _effects.trySend(ChatEffect.Toast("无法启动相机:${e.message}"))
            return null
        }
        pendingPhotoFile = file
        return ChatEffect.LaunchCamera(uri, file)
    }

    fun onCameraResult(success: Boolean) {
        val file = pendingPhotoFile ?: return
        try {
            if (!success) return
            val bytes = file.inputStream().use { it.readBytes() }
            if (ImageAttachments.isTooLarge(bytes, MAX_IMAGE_BYTES)) {
                _effects.trySend(ChatEffect.Toast("图片过大(>4MB),换一张吧"))
                return
            }
            stage(ImageAttachments.toDataUrl("image/jpeg", bytes))
        } catch (e: Exception) {
            _effects.trySend(ChatEffect.Toast("读取照片失败:${e.message}"))
        } finally {
            file.delete()
            pendingPhotoFile = null
        }
    }

    fun onGalleryResult(uri: Uri?) {
        if (uri == null) return
        try {
            val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null) {
                _effects.trySend(ChatEffect.Toast("读取图片失败"))
                return
            }
            if (ImageAttachments.isTooLarge(bytes, MAX_IMAGE_BYTES)) {
                _effects.trySend(ChatEffect.Toast("图片过大(>4MB),换一张吧"))
                return
            }
            val mime = appContext.contentResolver.getType(uri) ?: "image/jpeg"
            stage(ImageAttachments.toDataUrl(mime, bytes))
        } catch (e: Exception) {
            _effects.trySend(ChatEffect.Toast("读取图片失败:${e.message}"))
        }
    }

    private fun stage(dataUrl: String) {
        pendingImages.add(dataUrl)
        publish()
    }

    fun exportBranchText(): String = buildString {
        tree.visibleMessages().forEach { m ->
            append(if (m.role == "user") "👤 " else "🦀 ").append(m.content).append("\n\n")
        }
    }.toString().trim()

    // ── send / agent loop ────────────────────────────────────────

    private fun sendMessage(text: String) {
        if (_state.value.isLoading) return
        if (_state.value.isSending) {
            sendJob?.cancel()
            return
        }
        if (text.isEmpty() && pendingImages.isEmpty()) return

        val imageCount = pendingImages.size
        val outgoingImages = pendingImages.toList()
        pendingImages.clear()

        val displayed = if (imageCount > 0) "[图片 x$imageCount]\n$text" else text
        tree.appendMessage("user", displayed)
        syncMessages()

        val assistantMsg = ChatMessage("assistant", "")
        appendLocal(assistantMsg)
        publish()

        val provider = ProviderCatalog.findById(prefs.providerId)
        val apiKey = prefs.getApiKey()
        val model = prefs.model.trim()
        if (provider.requiresApiKey && apiKey.isEmpty()) {
            runDemo(text, assistantMsg)
            return
        }
        if (model.isEmpty()) {
            assistantMsg.content = "⚠️ 还没有配置模型名称。请打开设置,在「模型」一栏填写后再试。"
            notifyChanged()
            // Persist like the streaming path does (its finally appends to
            // the tree), or this warning bubble vanishes on the next branch
            // sync or process restart.
            tree.activeBranch.messages.add(BranchMessage("assistant", assistantMsg.content))
            persistTree()
            return
        }

        // v4.3:后台执行——回合整体移交给前台服务,离开屏幕也继续跑。
        if (_state.value.backgroundTask) {
            if (ChatRepository.backgroundTaskRunning) {
                _effects.trySend(ChatEffect.Toast("已有后台任务在跑,先等它完成"))
                return
            }
            val request = AgentRequest(
                endpoint = prefs.endpoint,
                apiKey = apiKey,
                model = model,
                history = buildRequestHistory(text, outgoingImages),
                stream = false, // 无流式 UI,整段返回更省电
                tools = if (prefs.agentMode) activeToolbox().requestJson() else null,
                maxRounds = MAX_TOOL_ROUNDS,
                toolset = activeToolbox(),
            )
            val ok = AgentTaskService.enqueue(
                appContext,
                AgentTaskService.PendingTask(request, displayed, tree.activeBranchId)
            )
            if (ok) {
                ChatRepository.backgroundTaskRunning = true
                _state.value = _state.value.copy(backgroundTaskRunning = true)
                publish()
                _effects.trySend(ChatEffect.Toast("🦀 已移交后台,完成后通知你"))
            } else {
                _effects.trySend(ChatEffect.Toast("无法启动后台服务"))
            }
            return
        }

        _state.value = _state.value.copy(isSending = true)
        publish()
        sendJob = viewModelScope.launch {
            try {
                val request = AgentRequest(
                    endpoint = prefs.endpoint,
                    apiKey = apiKey,
                    model = model,
                    history = buildRequestHistory(text, outgoingImages),
                    stream = prefs.streamOutput,
                    tools = if (prefs.agentMode) activeToolbox().requestJson() else null,
                    maxRounds = MAX_TOOL_ROUNDS,
                    toolset = activeToolbox(),
                )
                agentLoop.run(request).collect { event -> onAgentEvent(event, assistantMsg) }
            } catch (e: CancellationException) {
                assistantMsg.content = if (assistantMsg.content.isBlank()) {
                    "⏹ 已停止生成"
                } else {
                    "${assistantMsg.content}\n\n⏹ 已停止"
                }
            } catch (e: Exception) {
                assistantMsg.content = if (assistantMsg.content.isEmpty()) {
                    "⚠️ 出错了:${e.message}\n\n请检查网络和服务商配置。"
                } else {
                    "${assistantMsg.content}\n\n⚠️ 出错了:${e.message}"
                }
            } finally {
                tree.activeBranch.messages.add(BranchMessage("assistant", assistantMsg.content))
                persistTree()
                _state.value = _state.value.copy(
                    isSending = false,
                    todayUsage = usageTracker.todaySummary(),
                )
                publish()
                _effects.trySend(ChatEffect.ScrollToBottom)
            }
        }
    }

    private fun onAgentEvent(event: AgentEvent, assistantMsg: ChatMessage) {
        when (event) {
            is AgentEvent.Delta -> {
                assistantMsg.content += event.text
                notifyChanged(assistantMsg)
            }
            is AgentEvent.ToolCalls -> {
                if (assistantMsg.content.isNotEmpty() && !assistantMsg.content.endsWith("\n")) {
                    assistantMsg.content += "\n\n"
                }
                event.calls.forEach { call ->
                    assistantMsg.content += "🔧 ${call.name}(${call.arguments})\n"
                }
                notifyChanged(assistantMsg)
            }
            is AgentEvent.ToolResult -> {
                assistantMsg.content += "↳ " + event.preview + "\n"
                notifyChanged(assistantMsg)
            }
            is AgentEvent.Usage -> {
                // 台账已在 ChatService 内记录;这里刷新 UI 展示。
                _state.value = _state.value.copy(todayUsage = usageTracker.todaySummary())
            }
            is AgentEvent.RoundLimitReached -> {
                assistantMsg.content += "\n\n⚠️ 已连续调用工具 ${event.rounds} 轮,为避免死循环已停止。"
                notifyChanged(assistantMsg)
            }
            is AgentEvent.Error -> {
                assistantMsg.content = if (assistantMsg.content.isEmpty()) {
                    "⚠️ ${event.message}"
                } else {
                    "${assistantMsg.content}\n\n⚠️ ${event.message}"
                }
                notifyChanged(assistantMsg)
            }
            AgentEvent.Done -> Unit
        }
    }

    private fun buildRequestHistory(text: String, images: List<String>): List<ChatService.Message> {
        val systemMessages = buildList {
            val persona = prefs.systemPrompt.trim()
            if (persona.isNotEmpty()) add(ChatService.Message("system", persona))
            if (prefs.agentMode) {
                add(ChatService.Message("system", AgentDirective.systemPrompt(activeToolbox())))
            }
        }
        val history = if (!prefs.keepContext) {
            systemMessages + listOf(ChatService.Message("user", text))
        } else {
            val base = _state.value.messages.dropLast(1)
                .map { ChatService.Message(it.role, it.content) }
            val limit = prefs.contextLimit
            val limited = if (limit > 0) base.takeLast(limit) else base
            systemMessages + limited
        }
        if (images.isEmpty()) return history
        return history.mapIndexed { i, m ->
            if (i == history.lastIndex && m.role == "user") m.copy(images = images) else m
        }
    }

    private fun activeToolbox(): com.openclaw.clawagent.agent.AgentToolbox {
        val enabled = toolbox.names.filter { prefs.isToolEnabled(it) }
        val base = toolbox.filtered(enabled)
        // 远程爪子:同样受 per-tool 开关约束(mcp_ 前缀名),握手失败时为空。
        val remote = mcpTools.filter { prefs.isToolEnabled(it.name) }
        return if (remote.isEmpty()) base else base.withTools(remote)
    }

    // ── branches / roles / copy ──────────────────────────────────

    /**
     * Branch operations while streaming detach the live bubble; branch
     * operations while a background task runs race the service's own
     * switchTo/append (it files results into the task branch). Block both,
     * with a toast explaining why.
     */
    private fun branchOpsBlocked(verb: String): Boolean {
        if (_state.value.isLoading || _state.value.isSending) {
            _effects.trySend(ChatEffect.Toast("生成中,请先停止再$verb"))
            return true
        }
        if (ChatRepository.backgroundTaskRunning) {
            _effects.trySend(ChatEffect.Toast("后台任务正在写入会话,请稍候再$verb"))
            return true
        }
        return false
    }

    private fun startNewBranch() {
        if (branchOpsBlocked("新建分支")) return
        if (tree.activeBranch.messages.isEmpty()) {
            _effects.trySend(ChatEffect.Toast("当前分支已是空的"))
            return
        }
        tree.forkAt(messageIndex = tree.activeBranch.messages.size, name = "新分支")
        syncMessages()
        persistTree()
        publish()
    }

    private fun switchBranch(branchId: String) {
        if (branchOpsBlocked("切换分支")) return
        if (branchId == tree.activeBranchId) return
        tree.switchTo(branchId)
        syncMessages()
        persistTree()
        publish()
    }

    private fun deleteBranch(branchId: String) {
        if (branchOpsBlocked("删除分支")) return
        val target = tree.allBranches.firstOrNull { it.id == branchId } ?: return
        if (target.parentId == null) {
            _effects.trySend(ChatEffect.Toast("根分支不可删除"))
            return
        }
        tree.deleteBranch(branchId)
        syncMessages()
        persistTree()
        publish()
    }

    private fun forkAt(position: Int) {
        if (branchOpsBlocked("分叉")) return
        val visible = tree.visibleMessages()
        if (position !in visible.indices) return
        val inherited = parentIndexOf(position) + 1
        tree.forkAt(messageIndex = inherited, name = "分叉")
        syncMessages()
        persistTree()
        publish()
    }

    /**
     * v4.1:持久化改异步(suspend DAO 在 Room 的事务执行器上跑,不再占主线程)。
     * 树的可变状态只在主线程动,这里只是把快照写出去。
     */
    private fun persistTree() {
        viewModelScope.launch { storage.save(tree) }
    }

    /** 可见列表第 N 条在当前分支自有消息里的下标(-1 = 继承自父分支)。 */
    private fun parentIndexOf(visiblePos: Int): Int {
        val own = tree.activeBranch.messages
        val offset = tree.visibleMessages().size - own.size
        val ownPos = visiblePos - offset
        return if (ownPos in own.indices) ownPos else own.size - 1
    }

    private fun setRole(roleKey: String) {
        prefs.roleKey = roleKey
        prefs.systemPrompt = SystemPromptManager.getPrompt(roleKey)
        publish()
        _effects.trySend(
            ChatEffect.Toast("已切换为:${SystemPromptManager.getRoleDisplayName(roleKey)}")
        )
    }

    private fun copyAt(position: Int) {
        val visible = tree.visibleMessages()
        if (position in visible.indices) {
            _effects.trySend(ChatEffect.CopyToClipboard(visible[position].content))
        }
    }

    // ── demo mode ────────────────────────────────────────────────

    private fun runDemo(text: String, msg: ChatMessage) {
        _state.value = _state.value.copy(isSending = true)
        publish()
        // Bound to sendJob so 停止 can interrupt the demo typewriter too —
        // previously only the network coroutine was cancellable.
        sendJob = viewModelScope.launch {
            val reply = when {
                text.contains("你好") || text.lowercase().contains("hello") ->
                    "你好呀!👋\n\n我是 **Claw Agent** 🦀\n\n当前是演示模式,配置 API Key 后即可使用完整 Agent 能力。"
                else ->
                    "你好!我是 **Claw Agent** 🦀\n\n当前是演示模式。请在设置中配置 API Key。\n\n支持:OpenAI / DeepSeek / 智谱 / Kimi / 硅基流动 等 13 家服务商。"
            }
            try {
                for (i in 1..reply.length) {
                    msg.content = reply.substring(0, i)
                    notifyChanged(msg)
                    kotlinx.coroutines.delay(15)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Stopped mid-typewriter: keep the partial text (the network
                // path does the same) instead of dropping it on the floor.
                if (msg.content.isBlank()) msg.content = "⏹ 已停止生成"
            }
            // File the full or partial reply so it survives a refresh.
            tree.activeBranch.messages.add(BranchMessage("assistant", msg.content))
            persistTree()
            _state.value = _state.value.copy(isSending = false)
            publish()
        }
    }

    // ── state plumbing ───────────────────────────────────────────

    private fun appendLocal(msg: ChatMessage) {
        // 快照拷贝:列表里绝不能有活引用(流式会原地改 content,
        // DiffUtil 的旧列表会读到新值,打字机就停了)。
        _state.value = _state.value.copy(
            messages = _state.value.messages + ChatMessage(msg.role, msg.content)
        )
    }

    private fun notifyChanged(streaming: ChatMessage? = null) {
        // 流式中的气泡是活对象(assistantMsg/demo msg),而 state 列表里存的
        // 是它的旧快照副本——必须用活对象的最新内容重建尾条,否则 UI 永远
        // 显示空串(DiffUtil 快照与活引用的两难,这里靠显式重建解决)。
        val s = _state.value
        val msgs = if (streaming != null && s.messages.isNotEmpty()) {
            s.messages.toMutableList().apply {
                set(size - 1, ChatMessage(streaming.role, streaming.content))
            }
        } else {
            s.messages
        }
        _state.value = s.copy(messages = msgs.map { ChatMessage(it.role, it.content) })
    }

    private fun syncMessages() {
        _state.value = _state.value.copy(
            messages = tree.visibleMessages().map { ChatMessage(it.role, it.content) }
        )
    }

    private fun publish() {
        val s = _state.value
        _state.value = s.copy(
            branches = tree.allBranches.map {
                BranchUi(it.id, it.name, it.messages.size, it.id == tree.activeBranchId)
            },
            activeBranchName = tree.activeBranch.name,
            roleKey = prefs.roleKey,
            roleLabel = SystemPromptManager.getRoleDisplayName(prefs.roleKey),
            stagedImageCount = pendingImages.size,
            mcpStatus = if (prefs.mcpEndpoint.isBlank()) "" else s.mcpStatus,
        )
    }

    companion object {
        private const val MAX_TOOL_ROUNDS = 15
        private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024

        /** 手工装配(无 DI 框架);依赖图短。数据所有权归 ChatRepository。 */
        fun factory(activity: androidx.activity.ComponentActivity) =
            androidx.lifecycle.viewmodel.viewModelFactory {
                initializer {
                    com.openclaw.clawagent.task.ChatRepository.init(activity)
                    val repo = com.openclaw.clawagent.task.ChatRepository
                    ChatViewModel(
                        appContext = activity.applicationContext,
                        prefs = repo.prefs,
                        storage = repo.storage,
                        chatService = repo.chatService,
                        agentLoop = repo.agentLoop,
                        usageTracker = repo.usageTracker,
                    )
                }
            }
    }
}
