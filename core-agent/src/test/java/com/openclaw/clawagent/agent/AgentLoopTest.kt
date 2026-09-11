package com.openclaw.clawagent.agent

import com.openclaw.clawagent.provider.ChatService
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The whole point of the v4.0 surgery, proven: the ReAct loop is now
 * unit-testable against a fake transport — no network, no Android, fully
 * deterministic. Covers round counting, tool execution, the anti-loop guard,
 * preview truncation and error pass-through.
 */
class AgentLoopTest {

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
        ): kotlinx.coroutines.flow.Flow<ChatService.StreamEvent> {
            requests++
            seenHistories += history
            return flowOf(script[requests - 1])
        }
    }

    private fun request(toolset: AgentToolbox, maxRounds: Int = 15) = AgentRequest(
        endpoint = "https://unit.test/v1",
        apiKey = "",
        model = "fake",
        history = listOf(ChatService.Message("user", "hi")),
        stream = false,
        tools = JSONArray(),
        maxRounds = maxRounds,
        toolset = toolset,
    )

    private fun toolbox(notesDir: File) = Toolsets.core(notesDir)

    private fun delta(t: String) = ChatService.StreamEvent.Delta(t)

    private fun call(id: String, args: String = "{}") =
        ChatService.ToolCall(id, "calculator", args)

    // ── simple answer, no tools ───────────────────────────────────

    @Test
    fun `plain answer ends the turn with Done`() = runBlocking {
        val transport = FakeTransport(listOf(listOf(delta("你好"), ChatService.StreamEvent.Done)))
        val events = AgentLoop(transport).run(request(toolbox(tempDir()))).toList()
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
                listOf(delta("我来算"), ChatService.StreamEvent.ToolCalls(listOf(call("c1", """{"expression":"2+2"}""")))),
                listOf(delta("等于 4"), ChatService.StreamEvent.Done),
            )
        )
        val events = AgentLoop(transport).run(request(toolbox(tempDir()))).toList()

        // Round 1: delta + toolCalls + toolResult; round 2: delta + done.
        assertTrue(events.any { it is AgentEvent.ToolResult && it.preview == "4" })
        assertTrue(events.last() is AgentEvent.Done)

        // The second request must carry the assistant tool_calls turn and the
        // role:"tool" result, in the OpenAI order.
        val second = transport.seenHistories[1]
        assertEquals(4, second.size) // user, assistant(toolCalls), tool, ...
        assertEquals("assistant", second[1].role)
        assertEquals("tool", second[2].role)
        assertEquals("4", second[2].content)
        assertEquals("c1", second[2].toolCallId)
        assertEquals("calculator", second[2].toolName)
    }

    @Test
    fun `tool result preview truncates but full result reaches the model`() = runBlocking {
        val longExpr = "(1+1)" // tool output "2"; craft a long result via notes save instead
        val transport = FakeTransport(
            listOf(
                listOf(
                    ChatService.StreamEvent.ToolCalls(
                        listOf(
                            ChatService.ToolCall("n1", "notes", """{"action":"save","title":"big","content":"${"x".repeat(5000)}"}""")
                        )
                    )
                ),
                listOf(delta("done"), ChatService.StreamEvent.Done),
            )
        )
        val loop = AgentLoop(transport).apply { resultPreviewChars = 50 }
        val events = loop.run(request(toolbox(tempDir()))).toList()
        val result = events.filterIsInstance<AgentEvent.ToolResult>().single()
        assertTrue(result.fullResult.length > 100)
        assertTrue(result.preview.length < 120)
        assertTrue(result.preview.endsWith("已完整提供给模型)"))
        // The model saw the full text.
        val toolMsg = transport.seenHistories[1].first { it.role == "tool" }
        assertTrue(toolMsg.content.length > 5000)
    }

    // ── anti-loop guard ───────────────────────────────────────────

    @Test
    fun `round limit trips and reports the round count`() = runBlocking {
        val loopScript = listOf(
            ChatService.StreamEvent.ToolCalls(listOf(call("c"))),
        )
        val transport = FakeTransport(List(5) { loopScript })
        val events = AgentLoop(transport).run(request(toolbox(tempDir()), maxRounds = 3)).toList()
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
        val events = AgentLoop(transport).run(request(toolbox(tempDir()))).toList()
        assertEquals(
            listOf<AgentEvent>(
                AgentEvent.Delta("部分回答"),
                AgentEvent.Error("网络挂了", null),
            ),
            events,
        )
        assertTrue(events.last() !is AgentEvent.Done)
    }

    // ── failing tool degrades, never crashes the loop ─────────────

    @Test
    fun `unknown tool degrades into error string and loop continues`() = runBlocking {
        val transport = FakeTransport(
            listOf(
                listOf(ChatService.StreamEvent.ToolCalls(listOf(call("c1", "{}")))),
                listOf(delta("ok"), ChatService.StreamEvent.Done),
            )
        )
        val events = AgentLoop(transport).run(request(toolbox(tempDir()))).toList()
        val result = events.filterIsInstance<AgentEvent.ToolResult>().single()
        assertTrue(result.fullResult.contains("未找到"))
        assertTrue(events.last() is AgentEvent.Done)
    }

    private fun tempDir(): File = Files.createTempDirectory("agentloop_test").toFile()
}
