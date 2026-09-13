package com.openclaw.clawagent.agent

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-only tests for the [JsTool] sandbox. They exercise the real Rhino engine
 * (no mocks): arithmetic, console capture, JSON serialisation, the runaway-loop
 * budget and the Java-access block.
 */
class JsToolTest {

    private val tool = JsTool()

    private fun run(code: String, timeoutMs: Int? = null): String {
        val args = JSONObject().apply {
            put("code", code)
            if (timeoutMs != null) put("timeout_ms", timeoutMs)
        }
        return tool.execute(args.toString())
    }

    @Test
    fun `precise arithmetic is computed exactly`() {
        val out = run("365 * 24 * 60 * 60")
        assertTrue(out, out.contains("31536000"))
    }

    @Test
    fun `console output is captured`() {
        val out = run("console.log('hello', 42); 1 + 1")
        assertTrue(out, out.contains("控制台输出"))
        assertTrue(out, out.contains("hello 42"))
        assertTrue(out, out.contains("返回值:2"))
    }

    @Test
    fun `object result is serialized as json`() {
        val out = run("({a: 1, b: [2, 3]})")
        assertTrue(out, out.contains("""{"a":1,"b":[2,3]}"""))
    }

    @Test
    fun `array map returns json`() {
        val out = run("JSON.parse('[1,2,3]').map(x => x * 2)")
        assertTrue(out, out.contains("[2,4,6]"))
    }

    @Test
    fun `undefined result is reported as no return value`() {
        val out = run("var x = 1;")
        assertTrue(out, out.contains("无返回值"))
    }

    @Test
    fun `infinite loop is aborted within the time limit`() {
        val start = System.currentTimeMillis()
        val out = run("while(true){}", timeoutMs = 800)
        val elapsed = System.currentTimeMillis() - start
        assertTrue(out, out.startsWith("错误"))
        assertTrue("应在 5s 内中止,实际 ${elapsed}ms", elapsed < 5000)
    }

    @Test
    fun `java access is denied`() {
        val out = run("java.lang.Runtime.getRuntime()")
        assertTrue(out, out.startsWith("错误"))
    }

    @Test
    fun `no state leaks between runs`() {
        run("var leak = 123; leak")
        val out = run("typeof leak")
        assertTrue(out, out.contains("undefined"))
    }

    @Test
    fun `syntax error is reported with a line number`() {
        val out = run("var = ;")
        assertTrue(out, out.startsWith("错误"))
        assertTrue(out, out.contains("行"))
    }

    @Test
    fun `missing code yields an error string`() {
        assertTrue(tool.execute("""{"timeout_ms":1000}""").startsWith("错误"))
    }

    @Test
    fun `malformed json yields an error string`() {
        assertTrue(tool.execute("not json").startsWith("错误"))
    }

    @Test
    fun `oversized code is rejected`() {
        val huge = "0;".repeat(JsTool.MAX_CODE_LENGTH)
        assertTrue(run(huge).startsWith("错误"))
    }
}
