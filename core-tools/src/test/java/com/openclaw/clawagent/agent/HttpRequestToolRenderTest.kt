package com.openclaw.clawagent.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [HttpRequestTool]'s `render_js` branch. No network is touched: a
 * fake [WebRenderer] drives the render path, and the plain path is short-
 * circuited with an invalid URL so nothing is fetched.
 */
class HttpRequestToolRenderTest {

    private class FakeRenderer(
        private val html: String = "",
        private val throws: Boolean = false,
    ) : WebRenderer {
        var lastUrl: String? = null
        var lastTimeoutMs: Long = -1

        override fun render(url: String, timeoutMs: Long): String {
            lastUrl = url
            lastTimeoutMs = timeoutMs
            if (throws) throw IllegalStateException("boom")
            return html
        }
    }

    private fun args(url: String, renderJs: Boolean? = null): String =
        JSONObject().apply {
            put("url", url)
            if (renderJs != null) put("render_js", renderJs)
        }.toString()

    @Test
    fun `render mode delegates to the injected renderer`() {
        val fake = FakeRenderer("<html><body><h1>Hello</h1></body></html>")
        val out = HttpRequestTool(fake).execute(args("https://example.com", renderJs = true))

        assertTrue(out, out.contains("JS 渲染模式"))
        assertTrue(out, out.contains("Hello"))
        assertEquals("https://example.com", fake.lastUrl)
        assertEquals(HttpRequestTool.RENDER_TIMEOUT_MS, fake.lastTimeoutMs)
    }

    @Test
    fun `render mode reuses the shared sanitise pipeline`() {
        val html = "<html><head><style>x{}</style><script>var a=1;</script>" +
            "</head><body><p>Body text</p></body></html>"
        val out = HttpRequestTool(FakeRenderer(html)).execute(args("https://example.com", renderJs = true))

        val expected = HttpToolLogic.htmlToText(html)
        assertTrue(out, out.contains(expected))
    }

    @Test
    fun `render mode without an engine degrades with a readable error`() {
        val out = HttpRequestTool().execute(args("https://example.com", renderJs = true))
        assertTrue(out, out.contains("没有可用的 JS 渲染引擎"))
    }

    @Test
    fun `renderer failure is reported with a retry hint`() {
        val out = HttpRequestTool(FakeRenderer(throws = true))
            .execute(args("https://example.com", renderJs = true))
        assertTrue(out, out.contains("失败"))
        assertTrue(out, out.contains("普通模式"))
    }

    @Test
    fun `empty rendered html reports an anti-bot hint`() {
        val out = HttpRequestTool(FakeRenderer("")).execute(args("https://example.com", renderJs = true))
        assertTrue(out, out.contains("反爬"))
    }

    @Test
    fun `render_js defaults to false so invalid urls still short-circuit`() {
        val out = HttpRequestTool(FakeRenderer("<html><body>x</body></html>"))
            .execute("""{"url":"not a url"}""")
        assertTrue(out, out.startsWith("错误"))
    }

    @Test
    fun `invalid url in render mode is rejected before hitting the renderer`() {
        val fake = FakeRenderer("<html><body>x</body></html>")
        val out = HttpRequestTool(fake).execute(args("file:///etc/passwd", renderJs = true))
        assertTrue(out, out.startsWith("错误"))
        assertEquals(null, fake.lastUrl)
    }
}
