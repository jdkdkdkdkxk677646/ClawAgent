package com.openclaw.clawagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for HttpRequestTool: URL validation, HTML compaction and
 * truncation. No network is touched — the OkHttp call itself is exercised
 * only on-device.
 */
class HttpRequestToolTest {

    // ── validateUrl ───────────────────────────────────────────────

    @Test
    fun `accepts http and https urls`() {
        assertNotNull(HttpToolLogic.validateUrl("https://example.com"))
        assertNotNull(HttpToolLogic.validateUrl("http://api.example.com/v1/data?q=1"))
        assertEquals(
            "https://example.com/path",
            HttpToolLogic.validateUrl("  https://example.com/path ")
        )
    }

    @Test
    fun `rejects non-http schemes`() {
        assertNull(HttpToolLogic.validateUrl("file:///etc/passwd"))
        assertNull(HttpToolLogic.validateUrl("javascript:alert(1)"))
        assertNull(HttpToolLogic.validateUrl("ftp://example.com/file"))
        assertNull(HttpToolLogic.validateUrl("example.com"))
        assertNull(HttpToolLogic.validateUrl(""))
    }

    @Test
    fun `rejects whitespace and embedded credentials`() {
        assertNull(HttpToolLogic.validateUrl("https://example.com/a b"))
        assertNull(HttpToolLogic.validateUrl("https://user:pass@example.com"))
        assertNull(HttpToolLogic.validateUrl("https://example.com/\nheader: x"))
    }

    // ── htmlToText ────────────────────────────────────────────────

    @Test
    fun `strips script and style with content`() {
        val text = HttpToolLogic.htmlToText(
            "<html><head><style>body{color:red}</style>" +
                "<script>alert('x')</script></head><body>Hello</body></html>"
        )
        assertEquals("Hello", text)
        assertFalse(text.contains("alert"))
        assertFalse(text.contains("color:red"))
    }

    @Test
    fun `block tags become line breaks`() {
        val text = HttpToolLogic.htmlToText("<p>第一段</p><p>第二段</p><li>列表项</li>")
        assertTrue(text.contains("第一段\n"))
        assertTrue(text.contains("\n列表项"))
    }

    @Test
    fun `decodes common entities`() {
        val text = HttpToolLogic.htmlToText("<b>A &amp; B</b> &lt;tag&gt; &#39;quoted&#39; &nbsp; end")
        assertTrue(text, text.contains("A & B <tag>"))
        assertTrue(text, text.contains("'quoted'"))
        assertFalse(text, text.contains("&amp;"))
    }

    @Test
    fun `collapses runs of whitespace`() {
        val text = HttpToolLogic.htmlToText("<p>a</p>\n\n\n\n<p>b</p>    c")
        assertFalse(text.contains("\n\n\n"))
        assertTrue(text, text.contains("c"))
    }

    // ── truncate ──────────────────────────────────────────────────

    @Test
    fun `truncate keeps short text untouched`() {
        assertEquals("hello", HttpToolLogic.truncate("hello", 10))
    }

    @Test
    fun `truncate caps long text and reports size`() {
        val long = "x".repeat(5000)
        val out = HttpToolLogic.truncate(long, 100)
        assertTrue(out.length < 200)
        assertTrue(out, out.contains("5000"))
    }

    // ── tool schema sanity ────────────────────────────────────────

    @Test
    fun `tool metadata is well formed`() {
        val tool = HttpRequestTool()
        assertEquals("http_get", tool.name)
        val schema = JSONObject(tool.parametersJson)
        assertTrue(schema.getJSONArray("required").getString(0) == "url")
        assertTrue(tool.description.contains("url"))
    }
}
