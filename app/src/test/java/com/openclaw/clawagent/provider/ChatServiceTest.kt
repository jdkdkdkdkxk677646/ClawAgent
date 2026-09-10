package com.openclaw.clawagent.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Wire-format regression tests for message serialization. These pin the
 * exact JSON key names the OpenAI-compatible protocol expects — a wrong
 * key here is invisible to every other test (requests still "work" until
 * a strict provider 400s), so the shape is asserted explicitly.
 *
 * serializeMessage is internal for exactly this reason.
 */
class ChatServiceTest {

    private val service = ChatService()

    @Test
    fun `plain message serializes to role and content only`() {
        val json = service.serializeMessage(ChatService.Message("user", "hi"))
        assertEquals("user", json.getString("role"))
        assertEquals("hi", json.getString("content"))
        assertFalse(json.has("tool_calls"))
    }

    @Test
    fun `tool result carries the wire id key`() {
        val msg = ChatService.Message(
            role = "tool",
            content = "42",
            toolCallId = "call_9",
            toolName = "calculator",
        )
        val json = service.serializeMessage(msg)
        assertEquals("tool", json.getString("role"))
        assertEquals("42", json.getString("content"))
        assertEquals("call_9", json.getString("tool_call_id"))
        assertEquals("calculator", json.getString("name"))
    }

    @Test
    fun `assistant tool calls serialize in the OpenAI array shape`() {
        val msg = ChatService.Message(
            role = "assistant",
            content = "",
            toolCalls = listOf(
                ChatService.ToolCall("call_1", "current_time", "{}")
            ),
        )
        val json = service.serializeMessage(msg)
        val calls = json.getJSONArray("tool_calls")
        assertEquals(1, calls.length())
        val first = calls.getJSONObject(0)
        assertEquals("call_1", first.getString("id"))
        assertEquals("function", first.getString("type"))
        assertEquals("current_time", first.getJSONObject("function").getString("name"))
        assertEquals("{}", first.getJSONObject("function").getString("arguments"))
    }
}
