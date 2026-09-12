package com.openclaw.clawagent.task

import android.content.Context
import com.openclaw.clawagent.conversation.ConversationStorage
import com.openclaw.clawagent.conversation.ConversationTree
import com.openclaw.clawagent.provider.SecurePrefs
import com.openclaw.clawagent.provider.UsageStore
import com.openclaw.clawagent.provider.UsageTracker

/**
 * Process-level owner of the conversation tree and its storage (v4.3).
 *
 * Why: the agent turn used to live only inside [com.openclaw.clawagent.ui.ChatViewModel],
 * which dies with the Activity — lock the screen mid-task and the work dies
 * with it. Background tasks (v4.3) run in [AgentTaskService], and both the
 * ViewModel and the Service need to touch the SAME tree. A process singleton
 * makes that sharing trivial: the Service appends the result, the UI reads
 * the same in-memory object when it comes back to the foreground — no
 * serialization, no broadcast, no database polling.
 *
 * Usage accounting rides along (the tracker is shared so background tokens
 * land on the same daily ledger).
 */
object ChatRepository {

    lateinit var prefs: SecurePrefs
        private set
    lateinit var storage: ConversationStorage
        private set
    lateinit var usageTracker: UsageTracker
        private set
    lateinit var chatService: com.openclaw.clawagent.provider.ChatService
        private set
    lateinit var agentLoop: com.openclaw.clawagent.agent.AgentLoop
        private set

    /** The conversation tree — one object, touched by VM and Service alike. */
    var tree = ConversationTree()
        private set

    @Volatile
    var initialized = false
        private set

    /** True while [AgentTaskService] is running a turn (UI must not send). */
    @Volatile
    var backgroundTaskRunning = false

    /**
     * T-202:进程内一次性槽——[com.openclaw.clawagent.ShareReceiverActivity] 收到
     * 系统分享文本后写入,[com.openclaw.clawagent.MainActivity] 启动/回前台时
     * 消费成输入框草稿。刻意不被 [reset] 清空:分享槽的写入发生在 MainActivity
     * 创建之前,一次 reset 会把还没被消费的分享文本抹掉。
     */
    @Volatile
    var pendingShare: String? = null

    fun init(context: Context) {
        if (initialized) return
        reset(context)
    }

    /**
     * Rebinds all state to a fresh context/tree. Production calls this once
     * (via init); Robolectric tests call it per test method — the singleton
     * outlives a single test's database, and a stale in-memory tree would
     * leak between tests.
     */
    fun reset(context: Context) {
        prefs = SecurePrefs(context)
        storage = ConversationStorage(context)
        usageTracker = UsageTracker(prefs.usageStore())
        chatService = com.openclaw.clawagent.provider.ChatService(usageTracker = usageTracker)
        agentLoop = com.openclaw.clawagent.agent.AgentLoop(chatService)
        tree = ConversationTree()
        backgroundTaskRunning = false
        initialized = true
    }

    suspend fun save() {
        storage.save(tree)
    }
}
