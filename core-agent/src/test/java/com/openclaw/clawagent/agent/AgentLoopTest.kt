package com.openclaw.clawagent.agent

import com.openclaw.clawagent.provider.ChatService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole point of the v4.0 surgery, proven: the ReAct loop is now
 * unit-testable against a fake transport and a fake tool — no network, no
 * Android, no concrete toolset dependency (this module must not know
 * :core-tools). Covers round counting, tool execution, the anti-loop guard,
 * preview truncation and error pass-through.
 */
class AgentLoopTest {

    /** Deterministic tool: whatever arguments say, echoes `result`. */
    private class EchoTool(private val result: String) : AgentTool {
        override val name = "echo"
        override val description = "test double"
        override val parametersJson = """{"type":"object","properties":{}}"""
        override fun execute(arguments: String): String = result
    }

    private class FakeTransport(
        private val script: List<List<ChatService.StreamEvent>>,
    ) : com.openclaw.clawagent.provider.ChatTransport {
        var requests = 0
        val seenHistories = mutableListOf<List<ChatService.Message>>()
        override fun streamChat(
            endpoint: String,
            apiKey: String,
            model: String,
            history: List<ChatService.Message>,
            stream: Boolean,
            tools: JSONArray?,
        ): Flow<ChatService.StreamEvent> {
            requests++
            seenHistories += history
            return flowOf(*script[requests - 1].toTypedArray())
        }
    }

    private fun request(maxRounds: Int = 15, echoResult: String = "4") = AgentRequest(
        endpoint = "https://unit.test/v1",
        apiKey = "",
        model = "fake",
        history = listOf(ChatService.Message("user", "hi")),
        stream = false,
        tools = JSONArray(),
        maxRounds = maxRounds,
        toolset = AgentToolbox(listOf(EchoTool(echoResult))),
    )

    private fun delta(t: String) = ChatService.StreamEvent.Delta(t)

    private fun call(id: String, args: String = "{}") =
        ChatService.ToolCall(id, "echo", args)

    // ── simple answer, no tools ───────────────────────────────────

    @Test
    fun `plain answer ends the turn with Done`() = runBlocking {
        val transport = FakeTransport(listOf(listOf(delta("你好"), ChatService.StreamEvent.Done)))
        val events = AgentLoop(transport).run(request()).toList()
        assertEquals(
            listOf<AgentEvent>(
                AgentEvent.Delta("你好"),
                AgentEvent.Done,
            ),
            events,
        )
        assertEquals(1, transport.requests)
    }

    // ── one tool round ────────────────────────────────────────────

    @Test
    fun `tool call is executed and fed back before the final answer`() = runBlocking {
        val transport = FakeTransport(
            listOf(
                listOf(delta("我来算"), ChatService.StreamEvent.ToolCalls(listOf(call("c1", """{"v":"4"}""")))),
                listOf(delta("完成"), ChatService.StreamEvent.Done),
            )
        )
        val events = AgentLoop(transport).run(request(echoResult = "4")).toList()

        assertTrue(events.any { it is AgentEvent.ToolResult && it.preview == "4" })
        assertTrue(events.last() is AgentEvent.Done)

        // The second request must carry the assistant tool_calls turn and the
        // role:"tool" result, in the OpenAI order.
        val second = transport.seenHistories[1]
        assertEquals(3, second.size) // user, assistant(toolCalls), tool
        assertEquals("assistant", second[1].role)
        assertEquals("tool", second[2].role)
        assertEquals("4", second[2].content)
        assertEquals("c1", second[2].toolCallId)
        assertEquals("echo", second[2].toolName)
    }

    @Test
    fun `tool result preview truncates but full result reaches the model`() = runBlocking {
        val big = "x".repeat(5000)
        val transport = FakeTransport(
            listOf(
                listOf(
                    ChatService.StreamEvent.ToolCalls(
                        listOf(ChatService.ToolCall("n1", "echo", """{"v":"$big"}"""))
                    )
                ),
                listOf(delta("done"), ChatService.StreamEvent.Done),
            )
        )
        val loop = AgentLoop(transport).apply { resultPreviewChars = 50 }
        val events = loop.run(request(echoResult = big)).toList()
        val result = events.filterIsInstance<AgentEvent.ToolResult>().single()
        assertTrue(result.fullResult.length >= 5000)
        assertTrue(result.preview.length < 120)
        assertTrue(result.preview.endsWith("已完整提供给模型)"))
        // The model saw the full text.
        val toolMsg = transport.seenHistories[1].first { it.role == "tool" }
        assertTrue(toolMsg.content.length >= 5000)
    }

    // ── anti-loop guard ───────────────────────────────────────────

    @Test
    fun `round limit trips and reports the round count`() = runBlocking {
        val loopScript = listOf(
            ChatService.StreamEvent.ToolCalls(listOf(call("c"))),
        )
        val transport = FakeTransport(List(5) { loopScript })
        val events = AgentLoop(transport).run(request(maxRounds = 3)).toList()
        val trip = events.filterIsInstance<AgentEvent.RoundLimitReached>().single()
        assertEquals(3, trip.rounds)
        assertEquals(3, transport.requests)
    }

    // ── error pass-through ────────────────────────────────────────

    @Test
    fun `transport error ends the turn and keeps prior deltas`() = runBlocking {
        val transport = FakeTransport(
            listOf(
                listOf(delta("部分回答"), ChatService.StreamEvent.Error("网络挂了", null)),
            )
        )
        val events = AgentLoop(transport).run(request()).toList()
        assertEquals(
            listOf<AgentEvent>(
                AgentEvent.Delta("部分回答"),
                AgentEvent.Error("网络挂了", null),
            ),
            events,
        )
        assertTrue(events.last() !is AgentEvent.Done)
    }

    // ── text-embedded tool calls (fallback path) ──────────────────

    @Test
    fun `text-embedded call naming a registered tool is executed`() = runBlocking {
        val embedded = """
            ```json
            {"name":"echo","parameters":{"v":"4"}}
            ```
        """.trimIndent()
        val transport = FakeTransport(
            listOf(
                listOf(delta(embedded)),
                listOf(delta("done"), ChatService.StreamEvent.Done),
            )
        )
        val events = AgentLoop(transport).run(request(echoResult = "4")).toList()
        assertTrue(events.any { it is AgentEvent.ToolResult && it.name == "echo" })
        assertTrue(events.last() is AgentEvent.Done)
        val toolMsg = transport.seenHistories[1].firstOrNull { it.role == "tool" }
        assertTrue("tool result must be fed back", toolMsg != null)
        assertEquals("4", toolMsg!!.content)
    }

    @Test
    fun `text-embedded call naming an unregistered tool is ignored`() = runBlocking {
        val embedded = """{"name":"delete_everything","parameters":{}}"""
        val transport = FakeTransport(
            listOf(listOf(delta(embedded), ChatService.StreamEvent.Done))
        )
        val events = AgentLoop(transport).run(request()).toList()
        assertTrue(events.none { it is AgentEvent.ToolResult })
        assertTrue(events.last() is AgentEvent.Done)
        assertEquals(1, transport.requests)
    }
}
