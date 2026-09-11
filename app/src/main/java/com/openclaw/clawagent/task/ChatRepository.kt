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
    val tree = ConversationTree()

    @Volatile
    var initialized = false
        private set

    /** True while [AgentTaskService] is running a turn (UI must not send). */
    @Volatile
    var backgroundTaskRunning = false

    fun init(context: Context) {
        if (initialized) return
        prefs = SecurePrefs(context)
        storage = ConversationStorage(context)
        usageTracker = UsageTracker(prefs.usageStore())
        chatService = com.openclaw.clawagent.provider.ChatService(usageTracker = usageTracker)
        agentLoop = com.openclaw.clawagent.agent.AgentLoop(chatService)
        initialized = true
    }

    suspend fun save() {
        storage.save(tree)
    }
}
