package com.openclaw.clawagent.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openclaw.clawagent.agent.AgentLoop
import com.openclaw.clawagent.conversation.ClawDatabase
import com.openclaw.clawagent.conversation.ConversationStorage
import com.openclaw.clawagent.conversation.ConversationTree
import com.openclaw.clawagent.provider.ChatService
import com.openclaw.clawagent.provider.ChatTransport
import com.openclaw.clawagent.provider.SecurePrefs
import com.openclaw.clawagent.provider.UsageStore
import com.openclaw.clawagent.provider.UsageTracker
import com.openclaw.clawagent.task.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T-301 消息列表分页测试。
 *
 * 只跑 Robolectric(`ChatViewModel` 依赖 Room + context)。覆盖:
 *  - 超长会话首屏只暴露窗口大小,且 `hasMoreMessages == true`;
 *  - `LoadOlder` 逐页扩大窗口,取尽后 `hasMoreMessages` 翻转为 false;
 *  - **回归钉子**:发给模型的请求历史始终是会话树全量,不受窗口截断。
 */
@RunWith(RobolectricTestRunner::class)
class ChatPagingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context.deleteDatabase(ClawDatabase.NAME)
        context.getSharedPreferences("claw_branches", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("claw_settings", Context.MODE_PRIVATE).edit().clear().commit()
        ChatRepository.reset(context)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class MapUsageStore : UsageStore {
        private val map = HashMap<String, String>()
        override fun read(key: String): String? = map[key]
        override fun write(key: String, value: String) { map[key] = value }
    }

    /** Records the history of each request and immediately ends the turn. */
    private class RecordingTransport(
        private val sink: MutableList<List<ChatService.Message>>,
    ) : ChatTransport {
        override fun streamChat(
            endpoint: String,
            apiKey: String,
            model: String,
            history: List<ChatService.Message>,
            stream: Boolean,
            tools: JSONArray?,
        ): Flow<ChatService.StreamEvent> {
            sink.add(history)
            return flowOf(ChatService.StreamEvent.Done)
        }
    }

    /** Persist a tree with [n] messages so the VM loads it on init. */
    private fun seedMessages(n: Int) {
        runBlocking {
            val tree = ConversationTree()
            repeat(n) { i ->
                tree.appendMessage(if (i % 2 == 0) "user" else "assistant", "m$i")
            }
            ConversationStorage(context).save(tree)
        }
    }

    private fun viewModel(
        transport: ChatTransport = RecordingTransport(mutableListOf()),
    ): ChatViewModel {
        val prefs = SecurePrefs(context).apply {
            setApiKey("sk-test")
            model = "test-model"
            contextLimit = 0 // unlimited history → assert the FULL tree reaches the model
        }
        return ChatViewModel(
            appContext = context,
            prefs = prefs,
            storage = ConversationStorage(context),
            chatService = ChatService(),
            agentLoop = AgentLoop(transport),
            usageTracker = UsageTracker(MapUsageStore()),
        )
    }

    private suspend fun awaitUntil(timeoutMs: Long = 10000, condition: suspend () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(20)
        }
    }

    @Test
    fun `long conversation is windowed and reports more`() = runBlocking {
        seedMessages(120)
        val vm = viewModel()
        awaitUntil { !vm.state.value.isLoading }

        assertEquals(50, vm.state.value.messages.size)
        assertTrue(vm.state.value.hasMoreMessages)
    }

    @Test
    fun `load older expands the window until exhausted`() = runBlocking {
        seedMessages(120)
        val vm = viewModel()
        awaitUntil { !vm.state.value.isLoading }

        vm.onIntent(ChatIntent.LoadOlder)
        assertEquals(100, vm.state.value.messages.size)
        assertTrue(vm.state.value.hasMoreMessages)

        vm.onIntent(ChatIntent.LoadOlder)
        assertEquals(120, vm.state.value.messages.size)
        assertFalse(vm.state.value.hasMoreMessages)
    }

    @Test
    fun `request history stays full even though the list is windowed`() = runBlocking {
        seedMessages(120)
        val captured = mutableListOf<List<ChatService.Message>>()
        val vm = viewModel(RecordingTransport(captured))
        awaitUntil { !vm.state.value.isLoading }
        assertEquals(50, vm.state.value.messages.size) // windowed

        vm.onIntent(ChatIntent.SendMessage("hello"))
        awaitUntil { captured.isNotEmpty() }

        // 121 = 120 history + the current user turn; the 50-item window must NOT truncate it.
        assertEquals(121, captured.last().size)
    }
}
