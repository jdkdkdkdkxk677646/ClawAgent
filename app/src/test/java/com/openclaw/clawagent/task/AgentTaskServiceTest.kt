package com.openclaw.clawagent.task

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.openclaw.clawagent.agent.AgentRequest
import com.openclaw.clawagent.agent.AgentToolbox
import com.openclaw.clawagent.agent.AgentWiring
import com.openclaw.clawagent.conversation.ClawDatabase
import com.openclaw.clawagent.provider.ChatService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * End-to-end tests for [AgentTaskService] — the v4.3 background-task executor.
 *
 * The service had zero coverage even though it *is* the whole "dispatch a
 * task, walk away, get pinged" feature: everything else (ChatRepository, the
 * agent loop, the tools) is tested elsewhere, but the glue that runs a turn in
 * a foreground service and files the result back into the shared tree was not.
 *
 * Strategy — real components, faked network only:
 *  - real Robolectric context + real [ChatRepository] singleton (real
 *    OkHttp [ChatService], real [com.openclaw.clawagent.agent.AgentLoop]);
 *  - a [MockWebServer] stands in for the provider, so the endpoint the
 *    request points at is a real socket and the OpenAI SSE frames are written
 *    by hand. `prefs`/storage/prefs are the real ones too — nothing is mocked
 *    except the wire.
 *  - the service is driven through [AgentTaskService.enqueue] +
 *    `onStartCommand`, exactly like the platform would after
 *    `startForegroundService`.
 *
 * Threading: `Dispatchers.setMain(Dispatchers.Unconfined)` lets the service's
 * `scope.launch` run eagerly, but the loop still hops to
 * `Dispatchers.Default`/`Dispatchers.IO` (OkHttp), so every test polls for the
 * terminal state via [awaitUntil] rather than asserting a transient flag —
 * same pattern as [com.openclaw.clawagent.ui.ChatViewModelTest].
 */
@RunWith(RobolectricTestRunner::class)
class AgentTaskServiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer

    /** Must match [AgentTaskService] companion constants (private there). */
    private val foregroundNotificationId = 0xC1A3
    private val resultNotificationId = 0xC1A4

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context.deleteDatabase(ClawDatabase.NAME)
        context.getSharedPreferences("claw_branches", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("claw_settings", Context.MODE_PRIVATE).edit().clear().commit()
        // The repository singleton outlives one test's database — rebind it
        // (fresh tree included) or in-memory state leaks across tests.
        ChatRepository.reset(context)

        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        Dispatchers.resetMain()
    }

    // ── harness ────────────────────────────────────────────────────

    private fun chatEndpoint(): String = server.url("/v1/chat/completions").toString()

    private fun androidToolbox(): AgentToolbox = AgentWiring.forAndroid(context)

    private fun request(
        toolset: AgentToolbox = androidToolbox(),
        endpoint: String = chatEndpoint(),
        history: List<ChatService.Message> = listOf(ChatService.Message("user", "帮我算 2+2")),
    ) = AgentRequest(
        endpoint = endpoint,
        apiKey = "sk-test", // non-empty → the real agent-loop path
        model = "test-model",
        history = history,
        stream = true,
        tools = toolset.requestJson(),
        maxRounds = 15,
        toolset = toolset,
    )

    /** Parks the task and drives the service the way the platform would. */
    private fun startTask(task: AgentTaskService.PendingTask): AgentTaskService {
        val service = Robolectric.buildService(AgentTaskService::class.java).create().get()
        AgentTaskService.enqueue(context, task)
        service.onStartCommand(Intent(context, AgentTaskService::class.java), 0, 1)
        return service
    }

    private suspend fun awaitUntil(timeoutMs: Long = 20000, condition: suspend () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(20)
        }
    }

    private fun assistantMessages(): List<String> =
        ChatRepository.tree.activeBranch.messages
            .filter { it.role == "assistant" }
            .map { it.content }

    private fun notificationManager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Whether a notification with [id] is currently posted. The id is the key
     * passed to `notify(id, notification)` — a [Notification] has no id field,
     * so the shadow manager must be queried by key.
     */
    private fun hasNotification(id: Int): Boolean =
        shadowOf(notificationManager()).getNotification(id) != null

    /** Waits until the turn finished (flag cleared) and its message landed. */
    private suspend fun awaitFinished() = awaitUntil {
        !ChatRepository.backgroundTaskRunning && assistantMessages().isNotEmpty()
    }

    // ── SSE script helpers (what an OpenAI-compatible provider streams) ──

    private fun sseBody(vararg frames: JSONObject): String =
        frames.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    private fun contentFrame(text: String): JSONObject = JSONObject().put(
        "choices",
        JSONArray().put(JSONObject().put("delta", JSONObject().put("content", text))),
    )

    private fun toolCallFrame(name: String, argsJson: String, id: String = "call_1"): JSONObject =
        JSONObject().put(
            "choices",
            JSONArray().put(
                JSONObject().put(
                    "delta",
                    JSONObject().put(
                        "tool_calls",
                        JSONArray().put(
                            JSONObject()
                                .put("index", 0)
                                .put("id", id)
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject().put("name", name).put("arguments", argsJson),
                                ),
                        ),
                    ),
                ),
            ),
        )

    private fun sseResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    // ── 1. happy path (with a real tool round) ─────────────────────

    @Test
    fun `happy path runs a tool round and files the result into the active branch`() = runBlocking {
        server.enqueue(sseResponse(sseBody(toolCallFrame("calculator", "{\"expression\":\"2+2\"}"))))
        server.enqueue(sseResponse(sseBody(contentFrame("答案是 4"))))

        val branchId = ChatRepository.tree.activeBranchId
        startTask(AgentTaskService.PendingTask(request(), "帮我算 2+2", branchId))
        awaitFinished()

        val result = assistantMessages().last()
        assertTrue("bubble should show the tool line, was: $result", result.contains("🔧 calculator"))
        assertTrue("bubble should show the tool result, was: $result", result.contains("↳ 4"))
        assertTrue("bubble should end with the answer, was: $result", result.endsWith("答案是 4"))
        assertFalse("background flag must clear when the turn ends", ChatRepository.backgroundTaskRunning)
        // Two model rounds → two requests hit the provider.
        assertEquals(2, server.requestCount)
    }

    // ── 2. transport failure degrades into a message (never throws) ──

    @Test
    fun `transport failure degrades into an error bubble but still files and notifies`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("server boom"))

        val branchId = ChatRepository.tree.activeBranchId
        startTask(AgentTaskService.PendingTask(request(), "会失败", branchId))
        awaitFinished()

        val result = assistantMessages().last()
        assertTrue("failure must degrade into a ⚠️ message, was: $result", result.contains("⚠️"))
        // Filing still happens (best-effort contract): the result landed anyway.
        assertTrue(assistantMessages().isNotEmpty())
        // …and the completion notification fires even on failure.
        assertTrue(
            "expect a completion notification",
            hasNotification(resultNotificationId),
        )
        assertFalse(ChatRepository.backgroundTaskRunning)
    }

    // ── 3. notifications: foreground progress + completion ─────────

    @Test
    fun `finish posts a foreground progress notice and a completion notification`() = runBlocking {
        server.enqueue(sseResponse(sseBody(contentFrame("完成啦"))))

        startTask(
            AgentTaskService.PendingTask(
                request(history = listOf(ChatService.Message("user", "hi"))),
                "hi",
                ChatRepository.tree.activeBranchId,
            )
        )
        awaitFinished()

        assertTrue(
            "expect the ongoing foreground notice",
            hasNotification(foregroundNotificationId),
        )
        assertTrue(
            "expect the completion notification",
            hasNotification(resultNotificationId),
        )

        val done = shadowOf(notificationManager()).getNotification(resultNotificationId)
        assertNotNull("completion notification must exist", done)
        assertEquals("✅ 后台任务完成", done!!.extras?.getString(Notification.EXTRA_TITLE))
        assertTrue(
            "completion text carries the result preview",
            done.extras?.getString(Notification.EXTRA_TEXT)?.contains("完成啦") == true,
        )
    }

    // ── 4. the static handoff slot is consumed per run, never piled up ──

    @Test
    fun `enqueue slot is consumed on each run and never accumulates`() = runBlocking {
        server.enqueue(sseResponse(sseBody(contentFrame("第一轮"))))
        server.enqueue(sseResponse(sseBody(contentFrame("第二轮"))))

        startTask(AgentTaskService.PendingTask(request(), "one", ChatRepository.tree.activeBranchId))
        awaitUntil { assistantMessages().size == 1 && assistantMessages().last() == "第一轮" }

        // A second task must run on its own — not reuse / double-consume the
        // first one's parked slot.
        startTask(AgentTaskService.PendingTask(request(), "two", ChatRepository.tree.activeBranchId))
        awaitUntil { assistantMessages().size == 2 && assistantMessages().last() == "第二轮" }

        assertEquals(listOf("第一轮", "第二轮"), assistantMessages())
        assertEquals("one request per single-round task", 2, server.requestCount)
    }

    // ── 5. the result lands in the task's own branch, view not hijacked ──

    @Test
    fun `result files into the task branch and restores the user's active branch`() = runBlocking {
        server.enqueue(sseResponse(sseBody(contentFrame("后台结果"))))

        val tree = ChatRepository.tree
        val rootId = tree.activeBranchId
        // The user forks a branch, then browses back to the root while the
        // background task is still running.
        val forked = tree.forkAt(0)
        tree.switchTo(rootId)

        startTask(AgentTaskService.PendingTask(request(), "后台跑", forked.id))
        awaitUntil {
            forked.messages.any { it.role == "assistant" && it.content == "后台结果" }
        }

        // Filed into the *task's* branch…
        assertTrue(forked.messages.any { it.role == "assistant" && it.content == "后台结果" })
        // …and the user's view was not silently hijacked.
        assertEquals("user's active branch must be preserved", rootId, tree.activeBranchId)
    }

    // ── 6. a raw network exception surfaces the chained "出错了" message ──

    @Test
    fun `network exception surfaces the chained failure message`() = runBlocking {
        // Dead port → OkHttp onFailure → the flow closes with the cause, which
        // the service's catch-all turns into the "出错了" bubble.
        val deadEndpoint = "http://127.0.0.1:1/v1/chat/completions"
        startTask(
            AgentTaskService.PendingTask(
                request(endpoint = deadEndpoint),
                "x",
                ChatRepository.tree.activeBranchId,
            )
        )
        awaitFinished()

        val result = assistantMessages().last()
        assertTrue("network failure must surface 出错了, was: $result", result.contains("⚠️ 出错了"))
        assertFalse(ChatRepository.backgroundTaskRunning)
    }
}
