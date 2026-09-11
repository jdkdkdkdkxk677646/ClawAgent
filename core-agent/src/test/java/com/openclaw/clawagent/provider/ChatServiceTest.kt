package com.openclaw.clawagent.provider

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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

    // ── usage extraction, end to end over a faked OkHttp transport ──────
    //
    // A short-circuit interceptor stands in for the network: no socket, no
    // mockwebserver dependency. These tests pin the full event contract:
    // the legacy Delta/ToolCalls/Error/Done sequence is untouched, and the
    // optional Usage event (when the provider reports one) lands right
    // before Done.

    private fun fakeClient(body: String, mediaType: MediaType): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody(mediaType))
                    .build()
            }
            .build()

    private fun collect(
        body: String,
        mediaType: MediaType,
        tracker: UsageTracker? = null,
        stream: Boolean,
    ): List<ChatService.StreamEvent> = runBlocking {
        ChatService(fakeClient(body, mediaType), tracker)
            .streamChat(
                endpoint = "https://unit.test/v1/chat/completions",
                apiKey = "",
                model = "test-model",
                history = listOf(ChatService.Message("user", "hi")),
                stream = stream,
                tools = null,
            )
            .toList()
    }

    @Test
    fun `streaming usage chunk emits Usage right before Done`() {
        val sse = buildString {
            append("""data: {"choices":[{"delta":{"content":"你好"}}]}""")
            append("\n\n")
            append("""data: {"choices":[{"delta":{}}]}""")
            append("\n\n")
            append("""data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":34,"total_tokens":46}}""")
            append("\n\n")
            append("data: [DONE]\n\n")
        }
        val events = collect(sse, "text/event-stream".toMediaType(), stream = true)
        assertEquals(
            listOf(
                ChatService.StreamEvent.Delta("你好"),
                ChatService.StreamEvent.Usage(12, 34, 46),
                ChatService.StreamEvent.Done,
            ),
            events,
        )
    }

    @Test
    fun `non-stream response emits Usage from the top-level object`() {
        val json =
            """{"choices":[{"message":{"role":"assistant","content":"hi"}}],""" +
                """"usage":{"prompt_tokens":5,"completion_tokens":7,"total_tokens":12}}"""
        val events = collect(json, "application/json".toMediaType(), stream = false)
        assertEquals(
            listOf(
                ChatService.StreamEvent.Delta("hi"),
                ChatService.StreamEvent.Usage(5, 7, 12),
                ChatService.StreamEvent.Done,
            ),
            events,
        )
    }

    @Test
    fun `stream without usage keeps the legacy event sequence`() {
        val sse = """data: {"choices":[{"delta":{"content":"hi"}}]}""" + "\n\ndata: [DONE]\n\n"
        val events = collect(sse, "text/event-stream".toMediaType(), stream = true)
        assertEquals(
            listOf(ChatService.StreamEvent.Delta("hi"), ChatService.StreamEvent.Done),
            events,
        )
    }

    @Test
    fun `non-stream response without usage keeps the legacy sequence`() {
        val json = """{"choices":[{"message":{"role":"assistant","content":"hi"}}]}"""
        val events = collect(json, "application/json".toMediaType(), stream = false)
        assertEquals(
            listOf(ChatService.StreamEvent.Delta("hi"), ChatService.StreamEvent.Done),
            events,
        )
    }

    @Test
    fun `streaming usage is recorded into the injected tracker`() {
        val store = java.util.concurrent.ConcurrentHashMap<String, String>()
        val tracker = UsageTracker(
            read = { store[it] },
            write = { key, value -> store[key] = value },
            todayKey = { "2026-07-22" },
        )
        val sse = """data: {"choices":[{"delta":{"content":"hi"}}]}""" + "\n\n" +
            """data: {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":200,"total_tokens":300}}""" +
            "\n\ndata: [DONE]\n\n"
        collect(sse, "text/event-stream".toMediaType(), tracker, stream = true)
        assertEquals(
            UsageTracker.DayUsage(1, 100, 200, 300),
            tracker.todayUsage(),
        )
    }

    @Test
    fun `non-stream usage is recorded into the injected tracker`() {
        val store = java.util.concurrent.ConcurrentHashMap<String, String>()
        val tracker = UsageTracker(
            read = { store[it] },
            write = { key, value -> store[key] = value },
            todayKey = { "2026-07-22" },
        )
        val json =
            """{"choices":[{"message":{"role":"assistant","content":"hi"}}],""" +
                """"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}"""
        collect(json, "application/json".toMediaType(), tracker, stream = false)
        assertEquals(
            UsageTracker.DayUsage(1, 1, 2, 3),
            tracker.todayUsage(),
        )
    }
}
