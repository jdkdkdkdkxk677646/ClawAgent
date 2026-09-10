package com.openclaw.clawagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-105 对抗用例:HttpToolLogic。现有 HttpRequestToolTest 覆盖主干,
 * 这里针对协议大小写、端口/查询串、明文 http、超长 URL、正文中部 script、
 * 未收录的 HTML 实体等边界,**固化当前行为**(任务卡:发现 bug 不顺手修)。
 */
class HttpToolLogicAdversarialTest {

    // ── validateUrl 对抗 ─────────────────────────────────────────

    @Test
    fun `uppercase https scheme is accepted and normalized`() {
        val out = HttpToolLogic.validateUrl("HTTPS://EXAMPLE.COM/Path")
        assertNotNull("大写协议应被接受", out)
        // 只断言 protocol 被规范化为小写;host 的大小写在 JDK 17 与 25 上
        // 行为不同(新版不再小写化),跨 JVM 版本不做断言。
        assertTrue("scheme 应规范化为小写: $out", out!!.startsWith("https://"))
    }

    @Test
    fun `port and query string survive validation`() {
        val out = HttpToolLogic.validateUrl("https://example.com:8080/api/v1/search?q=%20x&rpp=10")
        assertNotNull(out)
        assertEquals("https://example.com:8080/api/v1/search?q=%20x&rpp=10", out)
    }

    @Test
    fun `plaintext http is accepted at validation layer`() {
        // 行为固化:校验层不区分明文/加密,是否拦截 http:// 由产品层决定
        assertNotNull(HttpToolLogic.validateUrl("http://insecure.example.com/plain.txt"))
    }

    @Test
    fun `very long url is accepted`() {
        // 行为固化:当前实现没有 URL 长度上限,2000+ 字符仍通过
        val long = "https://example.com/" + "a".repeat(2000)
        assertNotNull(HttpToolLogic.validateUrl(long))
    }

    @Test
    fun `lone control char passes validateUrl`() {
        // 行为固化 + 待维护者留意:ILLEGAL_URL_CHARS 注释称拒绝 control chars,
        // 但正则只含空白与 < > " ' `,U+0001 不在其中,仍能通过校验。
        // 实际抓取时 OkHttp 会拒绝该 URL 并降级为错误串,故无实害。
        assertNotNull(HttpToolLogic.validateUrl("https://example.com/\u0001x"))
    }

    // ── htmlToText 对抗 ──────────────────────────────────────────

    @Test
    fun `script in the middle of body is stripped`() {
        val text = HttpToolLogic.htmlToText("前言<script>alert(1)</script>中段<style>x{}</style>后文")
        assertFalse(text, text.contains("alert"))
        assertFalse(text, text.contains("x{}"))
        assertTrue(text, text.contains("前言"))
        assertTrue(text, text.contains("后文"))
    }

    @Test
    fun `unclosed script tag does not swallow the page`() {
        // 对抗:尾部未闭合的 <script> 不应吞掉整页正文
        val text = HttpToolLogic.htmlToText("正文开始<script>alert(1)")
        assertEquals("正文开始alert(1)", text)
    }

    @Test
    fun `hex numeric entity is not decoded`() {
        // 行为固化:decodeEntities 只收录固定的小写实体表,&#x2F; 原样保留
        assertEquals("a&#x2F;b", HttpToolLogic.htmlToText("a&#x2F;b"))
    }

    @Test
    fun `uppercase AMP entity is preserved verbatim`() {
        // 任务卡明确"按原样保留不算 bug,测行为":&AMP; 不在实体表内
        val text = HttpToolLogic.htmlToText("Tom &AMP; Jerry")
        assertTrue(text, text.contains("&AMP;"))
        assertFalse(text, text.contains("Tom & Jerry"))
    }

    @Test
    fun `entity-encoded script tag is not treated as a tag`() {
        // 行为固化:先剥标签、后解码实体。实体化的 <script> 在剥除阶段
        // 不存在,因此不会被移除;最终文本里 &lt; 解码为 <,而 &#x2F; 不在
        // 实体表内保持原样。
        val text = HttpToolLogic.htmlToText("&lt;script&gt;evil()&lt;&#x2F;script&gt;")
        assertEquals("<script>evil()<&#x2F;script>", text)
    }
}
