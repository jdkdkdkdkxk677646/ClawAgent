package com.openclaw.clawagent.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * JVM-only tests for the agent's persistent notebook. A temp directory is
 * injected so the real filesystem code paths run, but nothing outside the
 * temp dir is touched.
 */
class NoteToolTest {

    private lateinit var dir: File
    private lateinit var tool: NoteTool

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("claw_notes_test").toFile()
        tool = NoteTool(dir)
    }

    // ── save / read round trip ────────────────────────────────────

    @Test
    fun `save then read returns content`() {
        val out = tool.execute(JSONObject().apply {
            put("action", "save")
            put("title", "用户的咖啡偏好")
            put("content", "拿铁,双份浓缩,不加糖")
        }.toString())
        assertTrue(out, out.contains("已保存"))

        val read = tool.execute(JSONObject().apply {
            put("action", "read")
            put("title", "用户的咖啡偏好")
        }.toString())
        assertTrue(read, read.contains("拿铁,双份浓缩,不加糖"))
    }

    @Test
    fun `saving same title twice updates not duplicates`() {
        fun save(content: String) = tool.execute(JSONObject().apply {
            put("action", "save"); put("title", "清单"); put("content", content)
        }.toString())

        save("v1")
        val second = save("v2")
        assertTrue(second, second.contains("已更新"))

        val files = dir.listFiles()!!.filter { it.isFile }
        assertEquals(1, files.size)
        assertTrue(files[0].readText().contains("v2"))
    }

    // ── list / delete ─────────────────────────────────────────────

    @Test
    fun `list shows all saved notes`() {
        save("A", "a")
        save("B", "b")
        val out = tool.execute("""{"action":"list"}""")
        assertTrue(out, out.contains("2 条笔记"))
        assertTrue(out, out.contains("A"))
        assertTrue(out, out.contains("B"))
    }

    @Test
    fun `empty notebook lists nothing`() {
        val out = tool.execute("""{"action":"list"}""")
        assertTrue(out, out.contains("空"))
    }

    @Test
    fun `delete removes the note file`() {
        save("临时", "x")
        val out = tool.execute(JSONObject().apply {
            put("action", "delete"); put("title", "临时")
        }.toString())
        assertTrue(out, out.contains("已删除"))
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun `delete missing note reports gracefully`() {
        val out = tool.execute("""{"action":"delete","title":"不存在"}""")
        assertTrue(out, out.contains("没有"))
    }

    // ── validation & edge cases ───────────────────────────────────

    @Test
    fun `missing required args produce readable errors`() {
        assertTrue(tool.execute("""{"action":"save"}""").startsWith("错误"))
        assertTrue(tool.execute("""{"action":"save","title":"t"}""").startsWith("错误"))
        assertTrue(tool.execute("""{"action":"read"}""").startsWith("错误"))
        assertTrue(tool.execute("""{"action":"fly"}""").startsWith("错误"))
    }

    @Test
    fun `malformed json degrades into error string`() {
        assertTrue(tool.execute("not json").startsWith("错误"))
    }

    @Test
    fun `read missing note suggests list`() {
        val out = tool.execute("""{"action":"read","title":"ghost"}""")
        assertTrue(out, out.contains("没有"))
        assertTrue(out, out.contains("list"))
    }

    // ── slug safety ───────────────────────────────────────────────

    @Test
    fun `slug strips filesystem-illegal characters`() {
        assertEquals("a_b_c", tool.slug("a/b\\c"))
        assertEquals("采购_计划_", tool.slug("采购:计划?"))
        assertEquals("___", tool.slug("///"))
        assertEquals("中文标题", tool.slug("中文标题"))
        // path traversal: dots get trimmed at both ends, so "../escape" -> "escape"
        assertFalse(File(dir, tool.slug("../escape") + ".md").path.contains(".."))
    }

    private fun save(title: String, content: String) {
        val out = tool.execute(JSONObject().apply {
            put("action", "save"); put("title", title); put("content", content)
        }.toString())
        assertTrue(out, !out.startsWith("错误"))
    }
}
