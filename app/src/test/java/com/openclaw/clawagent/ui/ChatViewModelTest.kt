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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
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
 * Unit tests for the MVI [ChatViewModel] — the piece of the v4 rewrite that
 * had no tests of its own while `:core-agent` / `:core-tools` / `:data` did.
 *
 * Strategy: real Robolectric context + real [SecurePrefs] (keystore falls
 * back) + real [ConversationStorage] (on-disk Room) + a scripted
 * [FakeTransport] behind the real [AgentLoop]. That covers the full send →
 * stream → tool-execute → persist cycle with zero networking.
 *
 * Threading: `Dispatchers.setMain(Dispatchers.Unconfined)` makes
 * `viewModelScope.launch` run eagerly AND lets continuations resumed from
 * Room's executor threads run immediately (a TestDispatcher would park them
 * in its virtual scheduler, which a plain `runBlocking` never advances).
 * Room still finishes asynchronously, hence the [awaitUntil] polls. Note
 * the daily ledger is recorded by the real [ChatService] (covered by
 * UsageTrackerTest) — with a fake transport nothing records, so these tests
 * only assert the UI line mirrors the tracker.
 */
@RunWith(RobolectricTestRunner::class)
class ChatViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context.deleteDatabase(ClawDatabase.NAME)
        context.getSharedPreferences("claw_branches", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("claw_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── fakes ──────────────────────────────────────────────────────

    /** Scripted transport: each streamChat call consumes the next script. */
    private class FakeTransport(vararg scripts: List<ChatService.StreamEvent>) : ChatTransport {
        private val queue = ArrayDeque(scripts.toList())
        val requests = mutableListOf<List<ChatService.Message>>()

        override fun streamChat(
            endpoint: String,
            apiKey: String,
            model: String,
            history: List<ChatService.Message>,
            stream: Boolean,
            tools: JSONArray?,
        ): Flow<ChatService.StreamEvent> = flow {
            requests.add(history)
            val script = queue.removeFirstOrNull()
                ?: throw AssertionError("unexpected streamChat call #${requests.size}")
            script.forEach { emit(it) }
        }
    }

    private class MapUsageStore : UsageStore {
        val map = HashMap<String, String>()
        override fun read(key: String): String? = map[key]
        override fun write(key: String, value: String) { map[key] = value }
    }

    /**
     * [seed] runs after storage/prefs exist but BEFORE the ViewModel starts
     * its async load — the only way to guarantee the init load sees data.
     */
    private class Harness(
        val transport: FakeTransport,
        seed: (ConversationStorage, SecurePrefs) -> Unit = { _, _ -> },
    ) {
        private val context: Context = ApplicationProvider.getApplicationContext()

        val prefs = SecurePrefs(context).apply {
            setApiKey("sk-test") // non-empty key → the real agent loop path
        }
        val storage = ConversationStorage(context)
        val usageStore = MapUsageStore()
        // Pre-seed one record so the init snapshot carries a non-empty day.
        val usageTracker = UsageTracker(usageStore).apply { record(7, 3, 10) }

        init {
            seed(storage, prefs)
        }

        // Mirrors ChatViewModel.factory wiring: the tracker rides ChatService.
        val vm = ChatViewModel(
            appContext = context,
            prefs = prefs,
            storage = storage,
            chatService = ChatService(usageTracker = usageTracker),
            agentLoop = AgentLoop(transport),
            usageTracker = usageTracker,
        )
    }

    private suspend fun awaitUntil(timeoutMs: Long = 5000, condition: suspend () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(20)
        }
    }

    private suspend fun awaitLoaded(h: Harness): ChatViewModel {
        awaitUntil { !h.vm.state.value.isLoading }
        return h.vm
    }

    // ── load ───────────────────────────────────────────────────────

    @Test
    fun `empty storage shows welcome state`() = runBlocking {
        val h = Harness(FakeTransport(listOf(ChatService.StreamEvent.Done)))
        val vm = awaitLoaded(h)
        assertTrue(vm.state.value.showWelcome)
        assertTrue(vm.state.value.messages.isEmpty())
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `tree loads from storage on init`() = runBlocking {
        val h = Harness(FakeTransport(), seed = { storage, _ ->
            storage.save(ConversationTree().apply {
                appendMessage("user", "hello")
                appendMessage("assistant", "world")
            })
        })
        val vm = awaitLoaded(h)
        assertEquals(listOf("hello", "world"), vm.state.value.messages.map { it.content })
    }

    @Test
    fun `send before load completes is rejected - no write race`() = runBlocking {
        val h = Harness(FakeTransport(listOf(ChatService.StreamEvent.Done)))
        val vm = h.vm
        // isLoading is still true: the async load hasn't finished yet.
        assertTrue(vm.state.value.isLoading)
        vm.onIntent(ChatIntent.SendMessage("early"))
        awaitLoaded(h)
        assertTrue(vm.state.value.messages.isEmpty())
    }

    // ── send / stream / persist ────────────────────────────────────

    @Test
    fun `send appends user message immediately and streams into tail`() = runBlocking {
        val h = Harness(
            FakeTransport(
                listOf(
                    ChatService.StreamEvent.Delta("你好"),
                    ChatService.StreamEvent.Delta("，Claw"),
                    ChatService.StreamEvent.Done,
                )
            )
        )
        val vm = awaitLoaded(h)

        vm.onIntent(ChatIntent.SendMessage("hi"))
        // user message lands synchronously
        assertEquals("hi", vm.state.value.messages.first().content)
        assertTrue(vm.state.value.isSending)

        awaitUntil { !vm.state.value.isSending }
        assertEquals(listOf("hi", "你好，Claw"), vm.state.value.messages.map { it.content })
    }

    @Test
    fun `finished turn persists to storage`() = runBlocking {
        val h = Harness(
            FakeTransport(listOf(ChatService.StreamEvent.Delta("answer"), ChatService.StreamEvent.Done))
        )
        val vm = awaitLoaded(h)
        vm.onIntent(ChatIntent.SendMessage("q"))
        awaitUntil { !vm.state.value.isSending }

        awaitUntil {
            h.storage.load()?.first
                ?.any { branch -> branch.messages.any { it.content == "answer" } } == true
        }
    }

    @Test
    fun `tool call round trips through the real toolset`() = runBlocking {
        val h = Harness(
            FakeTransport(
                listOf(
                    ChatService.StreamEvent.ToolCalls(
                        listOf(ChatService.ToolCall("c1", "calculator", "{\"expression\":\"2+2\"}"))
                    ),
                    ChatService.StreamEvent.Done,
                ),
                listOf(ChatService.StreamEvent.Delta("答案是 4"), ChatService.StreamEvent.Done),
            )
        )
        val vm = awaitLoaded(h)
        vm.onIntent(ChatIntent.SendMessage("算一下 2+2"))
        awaitUntil { !vm.state.value.isSending }

        val bubble = vm.state.value.messages.last().content
        assertTrue(bubble.contains("🔧 calculator"))
        assertTrue(bubble.contains("↳ 4"))
        assertTrue(bubble.endsWith("答案是 4"))
    }

    @Test
    fun `transport error surfaces in the bubble and keeps prior text`() = runBlocking {
        val h = Harness(
            FakeTransport(
                listOf(
                    ChatService.StreamEvent.Delta("部分"),
                    ChatService.StreamEvent.Error("boom"),
                    ChatService.StreamEvent.Done,
                )
            )
        )
        val vm = awaitLoaded(h)
        vm.onIntent(ChatIntent.SendMessage("q"))
        awaitUntil { !vm.state.value.isSending }
        val bubble = vm.state.value.messages.last().content
        assertTrue(bubble.contains("部分"))
        assertTrue(bubble.contains("boom"))
    }

    @Test
    fun `demo mode kicks in without api key`() = runBlocking {
        val h = Harness(FakeTransport())
        h.prefs.setApiKey("")
        val vm = awaitLoaded(h)
        vm.onIntent(ChatIntent.SendMessage("hello"))
        awaitUntil { !vm.state.value.isSending }
        assertTrue(vm.state.value.messages.last().content.contains("演示模式"))
    }

    @Test
    fun `todayUsage mirrors the injected tracker`() = runBlocking {
        val h = Harness(FakeTransport())
        // The tracker pre-seeded one record before the VM snapshot was built;
        // the UI line must show exactly what the tracker holds.
        val vm = awaitLoaded(h)
        val summary = h.usageTracker.todaySummary()
        assertEquals(summary, vm.state.value.todayUsage)
        assertTrue(summary.contains("1 次请求"))
    }

    // ── branches / roles / copy ────────────────────────────────────

    @Test
    fun `new branch becomes active and switching back restores root`() = runBlocking {
        val h = Harness(FakeTransport(), seed = { storage, _ ->
            storage.save(ConversationTree().apply { appendMessage("user", "root msg") })
        })

        val vm = awaitLoaded(h)
        vm.onIntent(ChatIntent.NewBranch)
        val newBranchId = vm.state.value.branches.first { it.isActive }.id
        assertEquals("新分支", vm.state.value.activeBranchName)
        awaitUntil { h.storage.load()?.second == newBranchId }

        val rootId = vm.state.value.branches.first { !it.isActive }.id
        vm.onIntent(ChatIntent.SwitchBranch(rootId))
        awaitUntil { vm.state.value.messages.map { it.content } == listOf("root msg") }
        awaitUntil { h.storage.load()?.second == rootId }
    }

    @Test
    fun `root branch cannot be deleted`() = runBlocking {
        val h = Harness(FakeTransport())
        val vm = awaitLoaded(h)
        val rootId = vm.state.value.branches.first { it.isActive }.id
        vm.onIntent(ChatIntent.DeleteBranch(rootId))
        // no effect, and no crash
        assertTrue(vm.state.value.branches.any { it.id == rootId })
    }

    @Test
    fun `setRole updates prefs and state label`() = runBlocking {
        val h = Harness(FakeTransport())
        val vm = awaitLoaded(h)
        vm.onIntent(ChatIntent.SetRole("code_expert"))
        assertEquals("code_expert", h.prefs.roleKey)
        assertEquals("code_expert", vm.state.value.roleKey)
        assertEquals("代码专家", vm.state.value.roleLabel)
    }

    @Test
    fun `copyAt emits clipboard effect with raw content`() = runBlocking {
        val h = Harness(FakeTransport(), seed = { storage, _ ->
            storage.save(ConversationTree().apply { appendMessage("user", "copy me") })
        })
        val vm = awaitLoaded(h)

        var copied: String? = null
        val job = launch {
            vm.effects.collect { e -> if (e is ChatEffect.CopyToClipboard) copied = e.text }
        }
        vm.onIntent(ChatIntent.CopyAt(0))
        awaitUntil { copied != null }
        assertEquals("copy me", copied)
        job.cancel()
    }

    @Test
    fun `exportBranchText renders roles`() = runBlocking {
        val h = Harness(FakeTransport(), seed = { storage, _ ->
            storage.save(ConversationTree().apply {
                appendMessage("user", "问题")
                appendMessage("assistant", "回答")
            })
        })
        val vm = awaitLoaded(h)
        val export = vm.exportBranchText()
        assertTrue(export.contains("👤 问题"))
        assertTrue(export.contains("🦀 回答"))
    }
}
