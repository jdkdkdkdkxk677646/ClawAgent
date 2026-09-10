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

    // ── vision / multimodal turns ─────────────────────────────────

    @Test
    fun `images empty keeps plain string content`() {
        val json = service.serializeMessage(
            ChatService.Message("user", "hello")
        )
        assertEquals("hello", json.getString("content"))
        assertFalse(json.has("image_url"))
    }

    @Test
    fun `images emit the openai multimodal content array`() {
        val json = service.serializeMessage(
            ChatService.Message(
                role = "user",
                content = "这张图里是什么?",
                images = listOf(
                    "data:image/jpeg;base64,QUJD",
                    "https://example.com/pic.png",
                ),
            )
        )
        // content is a text segment + one image_url segment per image.
        val content = json.getJSONArray("content")
        assertEquals(3, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("这张图里是什么?", content.getJSONObject(0).getString("text"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertEquals(
            "data:image/jpeg;base64,QUJD",
            content.getJSONObject(1).getJSONObject("image_url").getString("url")
        )
        assertEquals(
            "https://example.com/pic.png",
            content.getJSONObject(2).getJSONObject("image_url").getString("url")
        )
        assertFalse(json.has("tool_calls"))
    }

    @Test
    fun `tool messages never carry images even if set`() {
        val json = service.serializeMessage(
            ChatService.Message(
                role = "tool",
                content = "42",
                toolCallId = "call_1",
                toolName = "calculator",
                images = listOf("data:image/png;base64,WA=="),
            )
        )
        // Tool results stay a plain string — the vision branch is for
        // user/assistant turns only.
        assertEquals("42", json.getString("content"))
        assertFalse(json.has("image_url"))
    }
}
