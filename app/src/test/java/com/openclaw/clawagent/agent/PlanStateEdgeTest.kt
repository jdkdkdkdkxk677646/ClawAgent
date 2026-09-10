package com.openclaw.clawagent.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-105 状态机用例:PlanState / PlanTool。现有 PlanToolTest 覆盖
 * set→mark→完成主干,这里针对 set 覆盖清零、mark 跳序、mark 中途的
 * status 快照、无计划 mark、空白步骤过滤与 MAX_STEPS 边界。
 * PlanState 是进程级单例,每个用例都先 set 自建前置状态,避免测试间耦合。
 */
class PlanStateEdgeTest {

    private val tool = PlanTool()

    private fun setPlan(goal: String, steps: List<String>): String = tool.execute(
        """{"action":"set","goal":"$goal","steps":${steps.joinToString(",", "[", "]") { "\"$it\"" }}}"""
    )

    @Test
    fun `re-set overwrites the plan and clears progress`() {
        setPlan("旧计划", listOf("一步", "二步", "三步"))
        tool.execute("""{"action":"mark","step":1}""")
        // 覆盖为新计划:旧进度必须清零,旧步骤不得残留
        setPlan("新计划", listOf("A", "B"))
        val status = tool.execute("""{"action":"status"}""")
        assertTrue("覆盖后进度应清零: $status", status.contains("0/2"))
        assertTrue(status, status.contains("☐ A"))
        assertFalse("旧计划的步骤不应残留: $status", status.contains("一步"))
    }

    @Test
    fun `mark out of order is allowed and renders correctly`() {
        setPlan("跳序", listOf("第一步", "第二步"))
        val out = tool.execute("""{"action":"mark","step":2}""")
        assertTrue(out, out.contains("1/2"))
        assertTrue(out, out.contains("☐ 第一步"))
        assertTrue(out, out.contains("☑ 第二步"))
    }

    @Test
    fun `status mid-mark is a consistent snapshot`() {
        setPlan("快照", listOf("s1", "s2", "s3"))
        tool.execute("""{"action":"mark","step":1}""")
        tool.execute("""{"action":"mark","step":3}""")
        val status = tool.execute("""{"action":"status"}""")
        assertTrue("应为 2/3: $status", status.contains("2/3"))
        assertTrue(status, status.contains("☑ s1"))
        assertTrue(status, status.contains("☐ s2"))
        assertTrue(status, status.contains("☑ s3"))
    }

    @Test
    fun `mark without any plan degrades gracefully`() {
        // 用一个 1 步计划走完(自动清空),把单例置于"无计划"态再 mark
        setPlan("清场", listOf("done"))
        tool.execute("""{"action":"mark","step":1}""")
        val out = tool.execute("""{"action":"mark","step":1}""")
        assertTrue(out, out.startsWith("错误") && out.contains("还没有计划"))
    }

    @Test
    fun `blank steps are filtered before building the plan`() {
        val out = setPlan("过滤", listOf("", "   ", "真实步骤"))
        assertTrue(out, out.contains("1 步"))
        assertTrue(out, out.contains("真实步骤"))
    }

    @Test
    fun `mark beyond the current plan size errors even under max`() {
        setPlan("边界", (1..PlanState.MAX_STEPS).map { "步骤$it" })
        val out = tool.execute("""{"action":"mark","step":${PlanState.MAX_STEPS + 1}}""")
        assertTrue(out, out.startsWith("错误") && out.contains("1~${PlanState.MAX_STEPS}"))
    }
}
