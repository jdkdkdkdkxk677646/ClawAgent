package com.openclaw.clawagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Registry-level tests for the agent toolbox (framework-free core set).
 */
class AgentToolboxTest {

    private fun coreToolbox(): AgentToolbox =
        Toolsets.core(Files.createTempDirectory("claw_toolbox_test").toFile())

    @Test
    fun `core toolbox registers the expected pure tools`() {
        val expected = listOf(
            "calculator", "current_time", "notes", "http_get", "web_search", "task_plan"
        )
        assertEquals(expected, coreToolbox().names)
    }

    @Test
    fun `requestJson builds openai function schema`() {
        val tools = coreToolbox().requestJson()
        assertEquals(6, tools.length())
        val first = tools.getJSONObject(0)
        assertEquals("function", first.getString("type"))
        val fn = first.getJSONObject("function")
        assertEquals("calculator", fn.getString("name"))
        assertTrue(fn.getJSONObject("parameters").has("properties"))
    }

    @Test
    fun `execute dispatches to the named tool`() {
        val out = coreToolbox().execute("calculator", """{"expression":"2+2"}""")
        assertEquals("4", out)
    }

    @Test
    fun `execute unknown tool degrades into error listing available names`() {
        val out = coreToolbox().execute("nope", "{}")
        assertTrue(out, out.contains("未找到"))
        assertTrue(out, out.contains("calculator"))
    }

    @Test
    fun `summary lists every tool with its name`() {
        val summary = coreToolbox().summary()
        coreToolbox().names.forEach { name ->
            assertTrue("summary should mention $name", summary.contains("**$name**"))
        }
    }

    @Test
    fun `directive mentions every tool and the agent identity`() {
        val box = coreToolbox()
        val prompt = AgentDirective.systemPrompt(box)
        assertTrue(prompt, prompt.contains("Claw Agent"))
        box.names.forEach { name ->
            assertTrue("directive should mention $name", prompt.contains(name))
        }
    }

    // ── filtered (per-tool kill switches) ─────────────────────────

    @Test
    fun `filtered keeps only the enabled tools`() {
        val box = coreToolbox()
        val reduced = box.filtered(setOf("calculator", "current_time"))
        assertEquals(listOf("calculator", "current_time"), reduced.names)
    }

    @Test
    fun `filtered toolbox hides disabled tools from the wire and dispatch`() {
        val box = coreToolbox().filtered(listOf("calculator", "notes", "http_get"))
        // Not advertised to the model...
        val json = box.requestJson()
        val names = (0 until json.length()).map {
            json.getJSONObject(it).getJSONObject("function").getString("name")
        }
        assertFalse("disabled tool must not be advertised", names.contains("current_time"))
        // ...and not dispatchable either.
        assertTrue(box.execute("current_time", "{}").contains("未找到"))
        assertEquals("4", box.execute("calculator", """{"expression":"2+2"}"""))
    }

    @Test
    fun `filtered directive inventory matches the reduced set`() {
        val box = coreToolbox().filtered(setOf("notes"))
        val prompt = AgentDirective.systemPrompt(box)
        // Inventory lines are the precise signal: the directive's fixed text
        // may mention other tools by name, but only enabled ones get a
        // "- **name**:description" inventory line.
        assertTrue(prompt, prompt.contains("- **notes**:"))
        assertFalse(prompt, prompt.contains("- **http_get**:"))
    }
}
