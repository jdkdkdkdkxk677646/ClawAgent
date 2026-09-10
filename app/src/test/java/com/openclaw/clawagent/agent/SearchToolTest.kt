package com.openclaw.clawagent.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for WebSearchTool: parsing a frozen DuckDuckGo-lite HTML
 * sample into results. The sample mirrors the real page structure (result
 * links wrapped in //duckduckgo.com/l/?uddg= redirects, snippets in
 * result-snippet cells) — no network is touched.
 */
class SearchToolTest {

    /** Structure-faithful excerpt of a lite.duckduckgo.com response. */
    private val SAMPLE = """
        <html><head><title>foo at DuckDuckGo</title></head><body>
        <table><tr><td>1.&nbsp;</td>
        <td valign="top"><a rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fdeepseek.example%2Fpricing&amp;rut=a1b2" class="result-link">DeepSeek API 价格页面</a></td></tr>
        <tr><td class="result-snippet">API 价格 <b>每百万</b> tokens 输入 1 元</td></tr>
        <tr><td>2.&nbsp;</td>
        <td valign="top"><a rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fdocs.example.org%2Fv1&amp;rut=c3d4" class="result-link">API 文档 &amp; 指南</a></td></tr>
        <tr><td class="result-snippet">如何调用接口,含示例</td></tr>
        </table>
        <a href="/about">关于我们</a>
        </body></html>
    """.trimIndent()

    @Test
    fun `parses redirect links into real urls and titles`() {
        val results = SearchLogic.parseResults(SAMPLE)
        assertEquals(2, results.size)
        assertEquals("DeepSeek API 价格页面", results[0].title)
        assertEquals("https://deepseek.example/pricing", results[0].url)
        assertEquals("https://docs.example.org/v1", results[1].url)
    }

    @Test
    fun `snippets are paired by position and cleaned of tags`() {
        val results = SearchLogic.parseResults(SAMPLE)
        assertTrue(results[0].snippet.contains("每百万"))
        assertTrue(!results[0].snippet.contains("<b>"))
        assertTrue(results[1].snippet.contains("如何调用接口"))
    }

    @Test
    fun `non-result anchors are ignored`() {
        val results = SearchLogic.parseResults(SAMPLE)
        assertTrue(results.none { it.url.contains("/about") })
    }

    @Test
    fun `unwrapRedirect handles plain and malformed hrefs`() {
        assertEquals(
            "https://a.b/c?d=1",
            SearchLogic.unwrapRedirect("//duckduckgo.com/l/?uddg=https%3A%2F%2Fa.b%2Fc%3Fd%3D1&rut=x")
        )
        assertNull(SearchLogic.unwrapRedirect("https://plain-link.example"))
        assertNull(SearchLogic.unwrapRedirect("//duckduckgo.com/l/?uddg=%E4%B8%8D%E6%98%AFurl"))
        assertNull(SearchLogic.unwrapRedirect("//duckduckgo.com/l/?uddg=%ZZ&rut=1")) // bad escape
    }

    @Test
    fun `empty or ad-only page yields no results`() {
        assertTrue(SearchLogic.parseResults("<html><body>no results</body></html>").isEmpty())
    }

    @Test
    fun `tool schema is well formed`() {
        val tool = WebSearchTool()
        assertEquals("web_search", tool.name)
        val schema = JSONObject(tool.parametersJson)
        assertEquals("query", schema.getJSONArray("required").getString(0))
    }
}
