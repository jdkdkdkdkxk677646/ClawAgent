package com.openclaw.clawagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorToolTest {

    private fun eval(expression: String): String {
        val args = """{"expression":"$expression"}"""
        return CalculatorTool().execute(args)
    }

    @Test
    fun `integer arithmetic`() {
        assertEquals("8", eval("3+5"))
        assertEquals("6", eval("2*3"))
        assertEquals("2.5", eval("5/2"))
        assertEquals("2", eval("5%3"))
    }

    @Test
    fun `precedence and parentheses`() {
        assertEquals("14", eval("2+3*4"))
        assertEquals("20", eval("(2+3)*4"))
        assertEquals("26", eval("(2+3)*(4+1)+1"))
    }

    @Test
    fun `power is right associative`() {
        assertEquals("512", eval("2^3^2"))
        assertEquals("8", eval("2^3"))
    }

    @Test
    fun `unary minus and whitespace`() {
        assertEquals("-4", eval("-2*2"))
        assertEquals("3", eval(" 1 + 2 "))
        assertEquals("-9", eval("-(3)^2"))
    }

    @Test
    fun `decimal results are trimmed`() {
        assertEquals("0.5", eval("1/2"))
        assertEquals("1.5", eval("0.75*2"))
    }

    @Test
    fun `division by zero reports error`() {
        val result = eval("1/0")
        assertTrue("应为错误信息: $result", result.startsWith("错误"))
    }

    @Test
    fun `malformed expression reports error`() {
        assertTrue(eval("2++*3").startsWith("错误"))
        assertTrue(eval("2+(3").startsWith("错误"))
        assertTrue(eval("foo").startsWith("错误"))
    }

    @Test
    fun `expression length is capped`() {
        val longExpr = "1+".repeat(500) + "1"
        assertTrue(eval(longExpr).startsWith("错误"))
    }

    @Test
    fun `malformed arguments json reports error`() {
        val result = CalculatorTool().execute("not json")
        assertTrue("应为错误信息: $result", result.startsWith("错误"))
    }

    @Test
    fun `request json advertises core tools`() {
        val json = AgentToolbox.core(notesTempDir()).requestJson()
        assertEquals(4, json.length())
        val names = (0 until json.length()).map { i ->
            json.getJSONObject(i).getJSONObject("function").getString("name")
        }
        assertTrue(names.contains("calculator"))
        assertTrue(names.contains("current_time"))
    }

    @Test
    fun `unknown tool returns graceful message`() {
        val result = AgentToolbox.core(notesTempDir()).execute("no_such_tool", "{}")
        assertTrue("应为错误信息: $result", result.contains("未找到"))
        assertTrue("应列出可用工具: $result", result.contains("calculator"))
    }

    private fun notesTempDir(): java.io.File =
        java.nio.file.Files.createTempDirectory("claw_calc_test").toFile()
}
