package com.openclaw.clawagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic tests for [NoteSearchLogic] — tokenising, scoring, snippets. */
class NoteSearchLogicTest {

    // ── tokenize ──────────────────────────────────────────────────

    @Test
    fun `splits latin and digit runs`() {
        assertEquals(listOf("hello", "world", "42"), NoteSearchLogic.tokenize("Hello, World 42!"))
    }

    @Test
    fun `splits cjk into bigrams`() {
        assertEquals(listOf("咖啡", "啡偏", "偏好"), NoteSearchLogic.tokenize("咖啡偏好"))
    }

    @Test
    fun `keeps a single cjk char as unigram`() {
        assertEquals(listOf("猫"), NoteSearchLogic.tokenize("猫"))
    }

    @Test
    fun `mixes latin words and cjk bigrams`() {
        assertEquals(listOf("gpt", "风向", "向标"), NoteSearchLogic.tokenize("gpt风向标"))
    }

    @Test
    fun `deduplicates tokens`() {
        assertEquals(listOf("ab"), NoteSearchLogic.tokenize("ab ab"))
    }

    @Test
    fun `punctuation only yields no tokens`() {
        assertTrue(NoteSearchLogic.tokenize("，。！?、").isEmpty())
    }

    // ── score ─────────────────────────────────────────────────────

    @Test
    fun `exact hit beats token scoring`() {
        val exact = NoteSearchLogic.score("咖啡偏好", "咖啡偏好", "")
        val token = NoteSearchLogic.score("咖啡偏好", "咖啡", "")
        assertTrue("$exact should beat $token", exact > token)
    }

    @Test
    fun `exact title hit outranks exact body hit`() {
        val title = NoteSearchLogic.score("咖啡", "咖啡", "")
        val body = NoteSearchLogic.score("咖啡", "", "咖啡")
        assertEquals(NoteSearchLogic.EXACT_TITLE, title)
        assertEquals(NoteSearchLogic.EXACT_BODY, body)
        assertTrue(title > body)
    }

    @Test
    fun `title weight outranks body weight for tokens`() {
        val titleOnly = NoteSearchLogic.score("风向标", "风向", "")
        val bodyOnly = NoteSearchLogic.score("风向标", "", "向标")
        assertTrue("$titleOnly should beat $bodyOnly", titleOnly > bodyOnly)
    }

    @Test
    fun `all tokens hit adds a bonus`() {
        val all = NoteSearchLogic.score("风向标", "风向", "向标")
        val some = NoteSearchLogic.score("风向标", "风向", "")
        assertTrue("$all should beat $some", all > some)
    }

    @Test
    fun `no hit scores zero`() {
        assertEquals(0, NoteSearchLogic.score("毫无关系", "风向", "向标"))
    }

    @Test
    fun `blank or punctuation query scores zero`() {
        assertEquals(0, NoteSearchLogic.score("", "a", "b"))
        assertEquals(0, NoteSearchLogic.score("   ", "a", "b"))
        assertEquals(0, NoteSearchLogic.score("，。！", "a", "b"))
    }

    @Test
    fun `case insensitive exact hit`() {
        assertEquals(NoteSearchLogic.EXACT_TITLE, NoteSearchLogic.score("coffee", "Coffee Preferences", ""))
    }

    // ── snippet ───────────────────────────────────────────────────

    @Test
    fun `snippet centers on the first hit with ellipses`() {
        val body = "前言".repeat(50) + "关键内容" + "后记".repeat(50)
        val snip = NoteSearchLogic.snippet(body, "关键内容", maxLen = 20)
        assertTrue(snip, snip.contains("关键内容"))
        assertTrue(snip, snip.startsWith("…"))
        assertTrue(snip, snip.endsWith("…"))
    }

    @Test
    fun `snippet falls back to the head when nothing matches`() {
        val snip = NoteSearchLogic.snippet("abcdefghij", "zzz", maxLen = 5)
        assertEquals("abcde…", snip)
    }

    @Test
    fun `snippet collapses newlines`() {
        val snip = NoteSearchLogic.snippet("line1\n\nline2 目标", "目标", maxLen = 40)
        assertFalse(snip, snip.contains("\n"))
        assertTrue(snip, snip.contains("目标"))
    }

    @Test
    fun `snippet matches a token when the whole query is absent`() {
        val snip = NoteSearchLogic.snippet("早上常去楼下买咖啡,大杯少冰", "咖啡偏好", maxLen = 40)
        assertTrue(snip, snip.contains("咖啡"))
    }
}
