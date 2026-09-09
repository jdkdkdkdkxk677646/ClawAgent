package com.openclaw.clawagent.agent

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Registry-level tests for the agent toolbox (framework-free core set).
 */
class AgentToolboxTest {

    private fun coreToolbox(): AgentToolbox =
        AgentToolbox.core(Files.createTempDirectory("claw_toolbox_test").toFile())

    @Test
    fun `core toolbox registers the expected pure tools`() {
        val expected = listOf("calculator", "current_time", "notes", "http_get")
        assertEquals(expected, coreToolbox().names)
    }

    @Test
    fun `requestJson builds openai function schema`() {
        val tools = coreToolbox().requestJson()
        assertEquals(4, tools.length())
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
}
