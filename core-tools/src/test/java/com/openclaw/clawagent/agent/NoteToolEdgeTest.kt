package com.openclaw.clawagent.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch

/**
 * T-105 边界用例:NoteTool。现有 NoteToolTest 覆盖读写主干,
 * 这里针对并发写、超长标题截断、纯空白 content、文件名片段搜索、
 * 标题清洗落盘。全部走真实临时目录,不碰 temp 之外的任何路径。
 */
class NoteToolEdgeTest {

    private lateinit var dir: File
    private lateinit var tool: NoteTool

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("claw_notes_edge").toFile()
        tool = NoteTool(dir)
    }

    private fun save(title: String, content: String): String = tool.execute(
        JSONObject().apply {
            put("action", "save"); put("title", title); put("content", content)
        }.toString()
    )

    @Test
    fun `concurrent saves to same title leave an intact file`() {
        // 两线程各 save 50 次同一标题:writeText 每次写完整字符串,
        // 最终文件必须是"某一次写入的完整内容"——结构完整、可解析。
        val n = 50
        val latch = CountDownLatch(2)
        val t1 = Thread { runCatching { repeat(n) { save("并发标题", "线程一-$it") } }; latch.countDown() }
        val t2 = Thread { runCatching { repeat(n) { save("并发标题", "线程二-$it") } }; latch.countDown() }
        t1.start(); t2.start()
        latch.await()

        val files = dir.listFiles()!!.filter { it.isFile }
        assertEquals("并发写同一标题只应落一个文件", 1, files.size)
        val content = files[0].readText()
        assertTrue("文件应以单个换行结尾(save 的写入格式)", content.endsWith("\n"))
        assertTrue("内容不应为空或撕裂", content.isNotBlank())
        val list = tool.execute("""{"action":"list"}""")
        assertTrue("list 应能正常枚举目录: $list", list.contains("1 条笔记"))
    }

    @Test
    fun `oversized title is truncated to 80 chars`() {
        val longTitle = "超".repeat(100)
        assertEquals(80, tool.slug(longTitle).length)
        val out = save(longTitle, "内容")
        assertTrue(out, out.contains("已保存"))
        // 原始标题仍能读回:read 与 save 走同一 fileFor/slug 路径
        val read = tool.execute(JSONObject().apply {
            put("action", "read"); put("title", longTitle)
        }.toString())
        assertTrue(read, read.contains("内容"))
    }

    @Test
    fun `blank content is rejected on save`() {
        val out = save("空白内容", "   ")
        assertTrue(out, out.startsWith("错误") && out.contains("content"))
        assertEquals("被拒的 save 不应落盘", 0, dir.listFiles()!!.size)
    }

    @Test
    fun `search keyword equal to filename fragment hits by title`() {
        save("Grok3推理配置", "模型参数")
        val out = tool.execute("""{"action":"search","query":"Grok3"}""")
        assertTrue(out, out.contains("Grok3推理配置"))
        assertTrue(out, out.contains("标题命中"))
    }

    @Test
    fun `title with separators is sanitized into one file`() {
        save("a\nb\tc", "x")
        assertEquals(listOf("a_b_c.md"), dir.listFiles()!!.map { it.name })
    }

    @Test
    fun `slug of blank or dot-only title falls back to untitled`() {
        assertEquals("untitled", tool.slug(""))
        assertEquals("untitled", tool.slug("..."))
    }
}
