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
    val stagedImageCount: Int = 0,
) {
    val showWelcome: Boolean get() = messages.isEmpty()
    val canSend: Boolean get() = !isSending
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
) : ViewModel() {

    private val toolbox: AgentToolbox = AgentWiring.forAndroid(appContext)

    private val tree = ConversationTree()

    private val _state = MutableStateFlow(
        ChatUiState(roleLabel = SystemPromptManager.getRoleDisplayName(prefs.roleKey))
    )
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _effects = Channel<ChatEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private val pendingImages = mutableListOf<String>()
    private var pendingPhotoFile: File? = null
    private var sendJob: Job? = null

    init {
        val loaded = storage.load()
        if (loaded != null) {
            val (branches, activeId) = loaded
            tree.replaceAll(branches, activeId)
        }
        syncMessages()
        publish()
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
        }
    }

    /** 设置对话框(View 版,Phase 4 迁 Compose)保存后同步 UI。 */
    fun refreshFromPrefs() {
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
                storage.save(tree)
                _state.value = _state.value.copy(isSending = false)
                publish()
                _effects.trySend(ChatEffect.ScrollToBottom)
            }
        }
    }

    private fun onAgentEvent(event: AgentEvent, assistantMsg: ChatMessage) {
        when (event) {
            is AgentEvent.Delta -> {
                assistantMsg.content += event.text
                notifyChanged()
            }
            is AgentEvent.ToolCalls -> {
                if (assistantMsg.content.isNotEmpty() && !assistantMsg.content.endsWith("\n")) {
                    assistantMsg.content += "\n\n"
                }
                event.calls.forEach { call ->
                    assistantMsg.content += "🔧 ${call.name}(${call.arguments})\n"
                }
                notifyChanged()
            }
            is AgentEvent.ToolResult -> {
                assistantMsg.content += "↳ " + event.preview + "\n"
                notifyChanged()
            }
            is AgentEvent.Usage -> Unit // ledger updated inside ChatService
            is AgentEvent.RoundLimitReached -> {
                assistantMsg.content += "\n\n⚠️ 已连续调用工具 ${event.rounds} 轮,为避免死循环已停止。"
                notifyChanged()
            }
            is AgentEvent.Error -> {
                assistantMsg.content = if (assistantMsg.content.isEmpty()) {
                    "⚠️ ${event.message}"
                } else {
                    "${assistantMsg.content}\n\n⚠️ ${event.message}"
                }
                notifyChanged()
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

    private fun activeToolbox() =
        toolbox.filtered(toolbox.names.filter { prefs.isToolEnabled(it) })

    // ── branches / roles / copy ──────────────────────────────────

    private fun startNewBranch() {
        if (_state.value.isSending) {
            _effects.trySend(ChatEffect.Toast("生成中,请先停止再切换或新建分支"))
            return
        }
        if (tree.activeBranch.messages.isEmpty()) {
            _effects.trySend(ChatEffect.Toast("当前分支已是空的"))
            return
        }
        tree.forkAt(messageIndex = tree.activeBranch.messages.size, name = "新分支")
        syncMessages()
        storage.save(tree)
        publish()
    }

    private fun switchBranch(branchId: String) {
        if (_state.value.isSending || branchId == tree.activeBranchId) return
        tree.switchTo(branchId)
        syncMessages()
        storage.save(tree)
        publish()
    }

    private fun deleteBranch(branchId: String) {
        if (_state.value.isSending) {
            _effects.trySend(ChatEffect.Toast("生成中,请先停止再删除分支"))
            return
        }
        val target = tree.allBranches.firstOrNull { it.id == branchId } ?: return
        if (target.parentId == null) {
            _effects.trySend(ChatEffect.Toast("根分支不可删除"))
            return
        }
        tree.deleteBranch(branchId)
        syncMessages()
        storage.save(tree)
        publish()
    }

    private fun forkAt(position: Int) {
        if (_state.value.isSending) {
            _effects.trySend(ChatEffect.Toast("生成中,请先停止再分叉"))
            return
        }
        val visible = tree.visibleMessages()
        if (position !in visible.indices) return
        val inherited = parentIndexOf(position) + 1
        tree.forkAt(messageIndex = inherited, name = "分叉")
        syncMessages()
        storage.save(tree)
        publish()
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
        viewModelScope.launch {
            val reply = when {
                text.contains("你好") || text.lowercase().contains("hello") ->
                    "你好呀!👋\n\n我是 **Claw Agent** 🦀\n\n当前是演示模式,配置 API Key 后即可使用完整 Agent 能力。"
                else ->
                    "你好!我是 **Claw Agent** 🦀\n\n当前是演示模式。请在设置中配置 API Key。\n\n支持:OpenAI / DeepSeek / 智谱 / Kimi / 硅基流动 等 13 家服务商。"
            }
            for (i in 1..reply.length) {
                msg.content = reply.substring(0, i)
                notifyChanged()
                kotlinx.coroutines.delay(15)
            }
            tree.activeBranch.messages.add(BranchMessage("assistant", reply))
            storage.save(tree)
            _state.value = _state.value.copy(isSending = false)
            publish()
        }
    }

    // ── state plumbing ───────────────────────────────────────────

    private fun appendLocal(msg: ChatMessage) {
        _state.value = _state.value.copy(messages = _state.value.messages + msg)
    }

    private fun notifyChanged() {
        _state.value = _state.value.copy(messages = _state.value.messages.toList())
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
        )
    }

    companion object {
        private const val MAX_TOOL_ROUNDS = 15
        private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024

        /** 手工装配(无 DI 框架);依赖图短,Phase 4 若引入 Hilt 再迁。 */
        fun factory(activity: androidx.activity.ComponentActivity) =
            androidx.lifecycle.viewmodel.viewModelFactory {
                initializer {
                    val prefs = SecurePrefs(activity)
                    val storage = ConversationStorage(activity)
                    val chatService = ChatService(
                        usageTracker = com.openclaw.clawagent.provider.UsageTracker(prefs.usageStore())
                    )
                    ChatViewModel(
                        appContext = activity.applicationContext,
                        prefs = prefs,
                        storage = storage,
                        chatService = chatService,
                        agentLoop = AgentLoop(chatService),
                    )
                }
            }
    }
}
