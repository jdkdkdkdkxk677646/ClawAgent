package com.openclaw.clawagent.agent

import com.openclaw.clawagent.mcp.McpClient
import com.openclaw.clawagent.mcp.McpException
import com.openclaw.clawagent.mcp.McpToolDef
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [McpServers] — pure logic, no Android imports.
 *
 * Covers: JSON parse tolerance, name deduplication, clientFactory seam,
 * connectAll success/failure/empty, and the legacy-single-server compat path
 * (parseConfigs reads the new JSON shape, not the old mcpEndpoint key).
 */
class McpServersTest {

    // ── parseConfigs ────────────────────────────────────────────────

    @Test
    fun `parseConfigs handles empty string`() {
        assertEquals(emptyList<McpServers.McpServerConfig>(), McpServers.parseConfigs(""))
    }

    @Test
    fun `parseConfigs handles empty array`() {
        assertEquals(emptyList<McpServers.McpServerConfig>(), McpServers.parseConfigs("[]"))
    }

    @Test
    fun `parseConfigs handles corrupt JSON`() {
        assertEquals(emptyList<McpServers.McpServerConfig>(), McpServers.parseConfigs("{not json"))
    }

    @Test
    fun `parseConfigs skips entries missing endpoint`() {
        val json = """[{"name":"bad"},{"name":"good","endpoint":"https://x/mcp"}]"""
        val out = McpServers.parseConfigs(json)
        assertEquals(1, out.size)
        assertEquals("https://x/mcp", out[0].endpoint)
    }

    @Test
    fun `parseConfigs deduplicates names with suffix`() {
        val json = """[
            {"name":"srv","endpoint":"https://a/mcp"},
            {"name":"srv","endpoint":"https://b/mcp"}
        ]""".replace("\n", "")
        val out = McpServers.parseConfigs(json)
        assertEquals(2, out.size)
        assertEquals("srv", out[0].name)
        assertEquals("srv-2", out[1].name)
    }

    @Test
    fun `parseConfigs defaults name from endpoint host`() {
        val json = """[{"endpoint":"https://docs.example.com/mcp"}]"""
        val out = McpServers.parseConfigs(json)
        assertEquals(1, out.size)
        assertEquals("docs.example.com", out[0].name)
    }

    // ── connectAll ──────────────────────────────────────────────────

    private class FakeClient(
        private val tools: List<McpToolDef>,
        private val serverInfo: String = "Fake",
        private val shouldThrow: Boolean = false,
    ) : McpClient("https://fake/mcp") {
        init {
            if (shouldThrow) throw McpException("boom")
        }
        override fun connect() { if (shouldThrow) throw McpException("boom") }
        override fun listTools() = tools
        override fun callTool(name: String, argumentsJson: String) = "ok"
        override fun close() {}
    }

    @Test
    fun `connectAll returns sessions with tools when factory succeeds`() {
        val cfg = McpServers.McpServerConfig("docs", "https://docs/mcp", null)
        val defs = listOf(McpToolDef("search", "Search", "Search docs", "{}"))
        val fake = FakeClient(tools = defs)
        val sessions = McpServers.connectAll(listOf(cfg)) { _, _ -> fake }
        assertEquals(1, sessions.size)
        assertTrue(sessions[0].success)
        assertEquals(1, sessions[0].tools.size)
        assertEquals("mcp_docs_search", sessions[0].tools[0].name)
    }

    @Test
    fun `connectAll degrades on single server failure`() {
        val cfg = McpServers.McpServerConfig("bad", "https://bad/mcp", null)
        val sessions = McpServers.connectAll(listOf(cfg)) { _, _ ->
            FakeClient(tools = emptyList(), shouldThrow = true)
        }
        assertEquals(1, sessions.size)
        assertTrue(sessions[0].error != null)
        assertEquals(0, sessions[0].tools.size)
    }

    @Test
    fun `connectAll all-failed returns empty tool set without throwing`() {
        val cfgs = listOf(
            McpServers.McpServerConfig("a", "https://a/mcp", null),
            McpServers.McpServerConfig("b", "https://b/mcp", null),
        )
        val sessions = McpServers.connectAll(cfgs) { _, _ ->
            FakeClient(tools = emptyList(), shouldThrow = true)
        }
        assertEquals(2, sessions.size)
        assertTrue(sessions.all { !it.success })
        assertEquals(0, sessions.flatMap { it.tools }.size)
    }

    @Test
    fun `connectAll empty config returns empty sessions`() {
        val sessions = McpServers.connectAll(emptyList())
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `connectAll resolves cross-server name collisions`() {
        val cfgA = McpServers.McpServerConfig("srv-a", "https://a/mcp", null)
        val cfgB = McpServers.McpServerConfig("srv-b", "https://b/mcp", null)
        val def = McpToolDef("search", "Search", "search", "{}")
        val sessions = McpServers.connectAll(listOf(cfgA, cfgB)) { ep, _ ->
            FakeClient(tools = listOf(def))
        }
        val allNames = sessions.flatMap { it.tools }.map { it.name }.toSet()
        // Both tools should be present with unique names.
        assertTrue(allNames.contains("mcp_srv-a_search"))
        assertTrue(allNames.contains("mcp_srv-b_search"))
        assertEquals(2, allNames.size)
    }
}
