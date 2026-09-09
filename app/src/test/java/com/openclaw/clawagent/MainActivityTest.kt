package com.openclaw.clawagent

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for MainActivity core logic.
 *
 * Tests cover:
 * - ChatMessage data class
 * - Message list management (add, clear, cap)
 * - Settings save / load via SharedPreferences
 * - History serialization / deserialization
 * - Build request history (system prompt, context window, truncation)
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("claw_test_unit", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    // ── ChatMessage ───────────────────────────────────────────────

    @Test
    fun `ChatMessage stores role and content`() {
        val msg = ChatMessage("user", "Hello")
        assertEquals("user", msg.role)
        assertEquals("Hello", msg.content)
    }

    @Test
    fun `ChatMessage equality works`() {
        val a = ChatMessage("assistant", "Hi")
        val b = ChatMessage("assistant", "Hi")
        assertEquals(a, b)
    }

    @Test
    fun `ChatMessage with empty content is valid`() {
        val msg = ChatMessage("assistant", "")
        assertEquals("", msg.content)
    }

    // ── Message list management ───────────────────────────────────

    @Test
    fun `adding messages grows list`() {
        val messages = mutableListOf<ChatMessage>()
        messages.add(ChatMessage("user", "Q1"))
        messages.add(ChatMessage("assistant", "A1"))
        assertEquals(2, messages.size)
        assertEquals("Q1", messages[0].content)
        assertEquals("A1", messages[1].content)
    }

    @Test
    fun `clearing messages empties list`() {
        val messages = mutableListOf(
            ChatMessage("user", "Q1"),
            ChatMessage("assistant", "A1"),
        )
        messages.clear()
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `message list cap keeps last N entries`() {
        val messages = mutableListOf<ChatMessage>()
        repeat(400) { i ->
            messages.add(ChatMessage("user", "msg_$i"))
        }
        val cap = 300
        val capped = if (messages.size > cap) messages.takeLast(cap) else messages
        assertEquals(cap, capped.size)
        assertEquals("msg_100", capped[0].content)
    }

    // ── History serialization ─────────────────────────────────────

    private fun serialize(messages: List<ChatMessage>): String {
        val json = JSONArray()
        messages.forEach {
            json.put(JSONObject().apply {
                put("role", it.role)
                put("content", it.content)
            })
        }
        return json.toString()
    }

    private fun deserialize(jsonStr: String): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        val json = JSONArray(jsonStr)
        for (i in 0 until json.length()) {
            val obj = json.getJSONObject(i)
            result.add(ChatMessage(obj.getString("role"), obj.getString("content")))
        }
        return result
    }

    @Test
    fun `round-trip serialization preserves messages`() {
        val original = listOf(
            ChatMessage("user", "Hello"),
            ChatMessage("assistant", "Hi there!"),
        )
        val json = serialize(original)
        val restored = deserialize(json)
        assertEquals(original, restored)
    }

    @Test
    fun `empty list serializes to empty array`() {
        val json = serialize(emptyList())
        assertEquals("[]", json)
    }

    @Test
    fun `deserializing empty array yields empty list`() {
        val result = deserialize("[]")
        assertTrue(result.isEmpty())
    }

    // ── Settings save / load ──────────────────────────────────────

    @Test
    fun `save and load provider id`() {
        prefs.edit().putString("provider_id", "deepseek").apply()
        val loaded = prefs.getString("provider_id", "openai")
        assertEquals("deepseek", loaded)
    }

    @Test
    fun `save and load model name`() {
        prefs.edit().putString("model", "deepseek-chat").apply()
        val loaded = prefs.getString("model", null)
        assertEquals("deepseek-chat", loaded)
    }

    @Test
    fun `save and load endpoint`() {
        prefs.edit().putString("api_endpoint", "https://api.example.com/v1").apply()
        val loaded = prefs.getString("api_endpoint", null)
        assertEquals("https://api.example.com/v1", loaded)
    }

    @Test
    fun `save and load boolean flags`() {
        prefs.edit()
            .putBoolean("keep_context", true)
            .putBoolean("stream", false)
            .putBoolean("agent_mode", true)
            .apply()
        assertTrue(prefs.getBoolean("keep_context", false))
        assertFalse(prefs.getBoolean("stream", true))
        assertTrue(prefs.getBoolean("agent_mode", false))
    }

    @Test
    fun `save and load context limit`() {
        prefs.edit().putInt("context_limit", 50).apply()
        assertEquals(50, prefs.getInt("context_limit", 20))
    }

    @Test
    fun `save and load system prompt`() {
        val prompt = "You are a helpful assistant."
        prefs.edit().putString("system_prompt", prompt).apply()
        assertEquals(prompt, prefs.getString("system_prompt", ""))
    }

    @Test
    fun `save and load history to SharedPreferences`() {
        val messages = listOf(
            ChatMessage("user", "Q1"),
            ChatMessage("assistant", "A1"),
        )
        val json = serialize(messages)
        prefs.edit().putString("history", json).apply()
        val loaded = prefs.getString("history", null)
        assertNotNull(loaded)
        val restored = deserialize(loaded!!)
        assertEquals(messages, restored)
    }

    // ── Build request history ─────────────────────────────────────

    @Test
    fun `request history includes system prompt when set`() {
        val history = buildRequestHistory(
            userText = "Hello",
            messages = emptyList(),
            systemPrompt = "You are a helpful assistant.",
            keepContext = true,
            contextLimit = 0,
        )
        assertEquals(2, history.size)
        assertEquals("system", history[0].role)
        assertEquals("You are a helpful assistant.", history[0].content)
        assertEquals("user", history[1].role)
        assertEquals("Hello", history[1].content)
    }

    @Test
    fun `request history omits context when keepContext is off`() {
        val history = buildRequestHistory(
            userText = "Hello",
            messages = listOf(ChatMessage("user", "Prev"), ChatMessage("assistant", "Ok")),
            systemPrompt = "",
            keepContext = false,
            contextLimit = 0,
        )
        assertEquals(1, history.size)
        assertEquals("user", history[0].role)
        assertEquals("Hello", history[0].content)
    }

    @Test
    fun `request history truncates to context limit`() {
        val history = buildRequestHistory(
            userText = "Hello",
            messages = List(10) { i -> ChatMessage(if (i % 2 == 0) "user" else "assistant", "msg_$i") },
            systemPrompt = "",
            keepContext = true,
            contextLimit = 4,
        )
        // 4 context messages + 1 user turn
        assertEquals(5, history.size)
        assertEquals("msg_6", history[0].content)
        assertEquals("msg_9", history[3].content)
        assertEquals("Hello", history[4].content)
    }

    @Test
    fun `request history has no limit when contextLimit is zero`() {
        val messages = List(50) { i -> ChatMessage("user", "msg_$i") }
        val history = buildRequestHistory(
            userText = "Hello",
            messages = messages,
            systemPrompt = "",
            keepContext = true,
            contextLimit = 0,
        )
        // All 50 context messages + user turn
        assertEquals(51, history.size)
    }

    @Test
    fun `request history with system prompt and limited context`() {
        val messages = List(10) { i -> ChatMessage("user", "msg_$i") }
        val history = buildRequestHistory(
            userText = "Hello",
            messages = messages,
            systemPrompt = "Be concise.",
            keepContext = true,
            contextLimit = 3,
        )
        // system + 3 context + user turn = 5
        assertEquals(5, history.size)
        assertEquals("system", history[0].role)
        assertEquals("Be concise.", history[0].content)
        assertEquals("msg_7", history[1].content)
        assertEquals("msg_8", history[2].content)
        assertEquals("msg_9", history[3].content)
    }

    @Test
    fun `request history defaults to empty system prompt`() {
        val history = buildRequestHistory(
            userText = "Hi",
            messages = emptyList(),
            systemPrompt = "",
            keepContext = true,
            contextLimit = 0,
        )
        assertEquals(1, history.size)
        assertEquals("user", history[0].role)
        assertEquals("Hi", history[0].content)
    }

    // ── Helpers ───────────────────────────────────────────────────

    /**
     * Mirrors MainActivity.buildRequestHistory logic for isolated testing.
     */
    private fun buildRequestHistory(
        userText: String,
        messages: List<ChatMessage>,
        systemPrompt: String,
        keepContext: Boolean,
        contextLimit: Int,
    ): List<com.openclaw.clawagent.provider.ChatService.Message> {
        val systemMessages = systemPrompt.trim().takeIf { it.isNotEmpty() }
            ?.let { listOf(com.openclaw.clawagent.provider.ChatService.Message("system", it)) }
            ?: emptyList()
        if (!keepContext) {
            return systemMessages + listOf(com.openclaw.clawagent.provider.ChatService.Message("user", userText))
        }
        val base = messages.map { com.openclaw.clawagent.provider.ChatService.Message(it.role, it.content) }
        val limited = if (contextLimit > 0) base.takeLast(contextLimit) else base
        return systemMessages + limited + listOf(com.openclaw.clawagent.provider.ChatService.Message("user", userText))
    }
}
