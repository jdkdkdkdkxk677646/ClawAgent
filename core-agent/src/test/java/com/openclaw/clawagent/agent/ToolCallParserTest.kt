package com.openclaw.clawagent.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the migrated, hardened [ToolCallParser]. All pure JVM — no network,
 * no Android.
 */
class ToolCallParserTest {

    // ── three shapes ──────────────────────────────────────────────

    @Test
    fun `parses a bare call inside a fenced block`() {
        val text = """
            让我算一下。
            ```json
            {"name": "calculator", "parameters": {"expression": "(2+2)*3"}}
            ```
        """.trimIndent()
        val calls = ToolCallParser.parse(text)
        assertEquals(1, calls.size)
        assertEquals("calculator", calls[0].name)
        assertEquals("(2+2)*3", calls[0].getString("expression"))
    }

    @Test
    fun `parses a tool_calls array with nested parameters`() {
        val text = """{"tool_calls":[{"id":"a1","name":"x","parameters":{"a":{"b":{"c":1}},"list":[1,{"d":2}]}}]}"""
        val calls = ToolCallParser.parse(text)
        assertEquals(1, calls.size)
        assertEquals("a1", calls[0].id)
        @Suppress("UNCHECKED_CAST")
        val a = calls[0].parameters["a"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val b = a["b"] as Map<String, Any?>
        assertEquals(1, b["c"])
    }

    @Test
    fun `parses a tool_use wrapper`() {
        val text = """{"tool_use": {"name": "notes", "arguments": {"action": "list"}}}"""
        val calls = ToolCallParser.parse(text)
        assertEquals(1, calls.size)
        assertEquals("notes", calls[0].name)
        assertEquals("list", calls[0].getString("action"))
    }

    // ── strategy 3: balanced scan in free text ────────────────────

    @Test
    fun `finds a balanced call scattered in prose`() {
        val text = """那我调用一下 {"name":"calculator","parameters":{"expression":{"nested":true}}} 完成"""
        val calls = ToolCallParser.parse(text)
        assertEquals(1, calls.size)
        assertEquals("calculator", calls[0].name)
        @Suppress("UNCHECKED_CAST")
        val expr = calls[0].parameters["expression"] as Map<String, Any?>
        assertEquals(true, expr["nested"])
    }

    @Test
    fun `fenced block with surrounding prose still parses`() {
        val text = """
            这是调用:
            ```json
            先说一句散文,再给 JSON: {"name":"echo","parameters":{"v":"hi"}}
            ```
            结束
        """.trimIndent()
        val calls = ToolCallParser.parse(text)
        assertEquals(1, calls.size)
        assertEquals("echo", calls[0].name)
        assertEquals("hi", calls[0].getString("v"))
    }

    // ── de-dup & robustness ───────────────────────────────────────

    @Test
    fun `duplicate calls collapse to one`() {
        val text = """{"tool_calls":[{"name":"a","parameters":{"x":1}},{"name":"a","parameters":{"x":1}}]}"""
        assertEquals(1, ToolCallParser.parse(text).size)
    }

    @Test
    fun `distinct arguments are kept separate`() {
        val text = """{"tool_calls":[{"name":"a","parameters":{"x":1}},{"name":"a","parameters":{"x":2}}]}"""
        assertEquals(2, ToolCallParser.parse(text).size)
    }

    @Test
    fun `broken json does not throw and yields no calls`() {
        assertEquals(0, ToolCallParser.parse("""{"name": "x", "parameters": {""").size)
        assertEquals(0, ToolCallParser.parse("""{{{{ """).size)
    }

    @Test
    fun `plain chat text yields no calls`() {
        assertTrue(ToolCallParser.parse("你好呀,今天天气不错,要不要一起去看电影?").isEmpty())
    }

    // ── extractTextResponse ───────────────────────────────────────

    @Test
    fun `extractTextResponse strips the call and keeps the prose`() {
        val text = """结果是 {"name":"calculator","parameters":{"x":1}} 完事了"""
        val cleaned = ToolCallParser.extractTextResponse(text)
        assertFalse(cleaned, cleaned.contains("name"))
        assertFalse(cleaned, cleaned.contains("parameters"))
        assertTrue(cleaned, cleaned.contains("结果是"))
        assertTrue(cleaned, cleaned.contains("完事了"))
    }

    @Test
    fun `extractTextResponse removes fenced blocks`() {
        val text = "前言\n```json\n{\"name\":\"a\",\"parameters\":{}}\n```\n后记"
        val cleaned = ToolCallParser.extractTextResponse(text)
        assertFalse(cleaned, cleaned.contains("name"))
        assertTrue(cleaned, cleaned.contains("前言"))
        assertTrue(cleaned, cleaned.contains("后记"))
    }

    // ── argumentsJson round-trip ──────────────────────────────────

    @Test
    fun `argumentsJson re-encodes the parameter map`() {
        val text = """{"name":"a","parameters":{"n":1,"s":"x","nested":{"k":true}}}"""
        val json = JSONObject(ToolCallParser.parse(text)[0].argumentsJson())
        assertEquals(1, json.getInt("n"))
        assertEquals("x", json.getString("s"))
        assertTrue(json.getJSONObject("nested").getBoolean("k"))
    }
}
