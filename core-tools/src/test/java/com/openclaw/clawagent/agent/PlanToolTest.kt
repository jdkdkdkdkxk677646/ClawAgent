package com.openclaw.clawagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the planning claw: set → mark → auto-summary lifecycle.
 * PlanState is a singleton, so each test resets it via a fresh `set`.
 */
class PlanToolTest {

    private val tool = PlanTool()

    private fun setPlan(): String = tool.execute(
        """{"action":"set","goal":"查价格并存笔记","steps":["搜索价格","读取页面","写入笔记"]}"""
    )

    @Test
    fun `set builds a checklist and returns it`() {
        val out = setPlan()
        assertTrue(out, out.contains("3 步"))
        assertTrue(out, out.contains("☐ 搜索价格"))
        assertTrue(out, out.contains("☐ 读取页面"))
    }

    @Test
    fun `mark advances progress with checkbox rendering`() {
        setPlan()
        val out = tool.execute("""{"action":"mark","step":1}""")
        assertTrue(out, out.contains("1/3"))
        assertTrue(out, out.contains("☑ 搜索价格"))
        assertTrue(out, out.contains("☐ 读取页面"))
    }

    @Test
    fun `marking the last step completes and clears the plan`() {
        setPlan()
        tool.execute("""{"action":"mark","step":1}""")
        tool.execute("""{"action":"mark","step":2}""")
        val final = tool.execute("""{"action":"mark","step":3}""")
        assertTrue(final, final.contains("全部完成"))
        assertTrue(final, final.contains("3/3"))
        // cleared afterwards
        assertTrue(tool.execute("""{"action":"status"}""").contains("没有进行中的计划"))
    }

    @Test
    fun `status without a plan degrades gracefully`() {
        // ensure empty state
        tool.execute("""{"action":"set","goal":"t","steps":["a"]}""")
        tool.execute("""{"action":"mark","step":1}""")
        assertTrue(tool.execute("""{"action":"status"}""").contains("没有进行中的计划"))
    }

    @Test
    fun `invalid step numbers produce readable errors`() {
        setPlan()
        assertTrue(tool.execute("""{"action":"mark","step":0}""").startsWith("错误"))
        assertTrue(tool.execute("""{"action":"mark","step":99}""").startsWith("错误"))
        assertTrue(tool.execute("""{"action":"mark"}""").startsWith("错误"))
    }

    @Test
    fun `set without steps errors out`() {
        assertTrue(tool.execute("""{"action":"set","goal":"x","steps":[]}""").startsWith("错误"))
        assertTrue(tool.execute("""{"action":"set"}""").startsWith("错误"))
    }

    @Test
    fun `malformed json degrades into error`() {
        assertTrue(tool.execute("not json").startsWith("错误"))
    }

    @Test
    fun `step list is capped`() {
        val steps = (1..20).joinToString(",") { "\"步骤$it\"" }
        val out = tool.execute("""{"action":"set","goal":"大任务","steps":[$steps]}""")
        assertTrue(out, out.contains("${PlanState.MAX_STEPS} 步"))
    }
}
