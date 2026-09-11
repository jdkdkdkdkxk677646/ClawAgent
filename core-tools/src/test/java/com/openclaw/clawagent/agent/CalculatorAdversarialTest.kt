package com.openclaw.clawagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-105 对抗用例:CalculatorTool。现有 CalculatorToolTest 覆盖主干运算,
 * 这里只打解析器边界:限长与深嵌套、多小数点、负指数、0^0、非 ASCII 数字、
 * 科学计数法、溢出/NaN。全部断言固化当前实现行为。
 */
class CalculatorAdversarialTest {

    private val tool = CalculatorTool()

    private fun eval(expression: String): String =
        tool.execute("""{"expression":"$expression"}""")

    // ── 超长嵌套括号 ──────────────────────────────────────────────

    @Test
    fun `hundreds of nested parens hit the length cap`() {
        // 500 层共 1002 字符:必须先被 MAX_LENGTH=200 拦截,而不是靠递归栈硬扛
        val deep = "(".repeat(500) + "1" + ")".repeat(500)
        val out = eval(deep)
        assertTrue(out, out.startsWith("错误") && out.contains("过长"))
    }

    @Test
    fun `deep nesting within cap still evaluates`() {
        // 90 层 = 181 字符,在上限之内;递归深度安全且结果正确
        val deep = "(".repeat(90) + "1" + ")".repeat(90)
        assertEquals("1", eval(deep))
    }

    @Test
    fun `deep nesting with arithmetic within cap`() {
        val deep = "(".repeat(40) + "2+3" + ")".repeat(40) + "*2"
        assertEquals("10", eval(deep))
    }

    // ── 数字格式对抗 ─────────────────────────────────────────────

    @Test
    fun `multiple decimal points rejected`() {
        val out = eval("1.2.3")
        assertTrue(out, out.startsWith("错误") && out.contains("格式不正确"))
    }

    @Test
    fun `full width digits rejected`() {
        // 全角"１"不在字符白名单,必须整串拒绝而不是静默转半角
        val out = eval("１+１")
        assertTrue(out, out.startsWith("错误") && out.contains("不允许的字符"))
    }

    @Test
    fun `chinese numerals rejected`() {
        val out = eval("一十二")
        assertTrue(out, out.startsWith("错误") && out.contains("不允许的字符"))
    }

    @Test
    fun `scientific notation rejected per docs`() {
        // 文档只承诺数字与 + - * / % ^ ( ),"1e5" 的 'e' 必须被拒(文档行为)
        val out = eval("1e5")
        assertTrue(out, out.startsWith("错误") && out.contains("不允许的字符"))
    }

    // ── 幂运算边界 ───────────────────────────────────────────────

    @Test
    fun `negative exponent evaluates`() {
        assertEquals("0.125", eval("2^-3"))
        assertEquals("0.1", eval("10^-1"))
    }

    @Test
    fun `zero to the zero equals one`() {
        assertEquals("1", eval("0^0"))
    }

    @Test
    fun `overflow reports instead of returning inf`() {
        val out = eval("10^309")
        assertTrue(out, out.startsWith("错误") && out.contains("溢出"))
    }

    @Test
    fun `complex root of negative base reports nan`() {
        // Math.pow(-1, 0.5) == NaN → 必须作为错误返回,不能静默输出 "NaN"
        val out = eval("(-1)^0.5")
        assertTrue(out, out.startsWith("错误") && out.contains("不是数字"))
    }

    // ── 其他边界 ─────────────────────────────────────────────────

    @Test
    fun `modulo by zero reports error`() {
        val out = eval("5%0")
        assertTrue(out, out.startsWith("错误") && out.contains("取模"))
    }

    @Test
    fun `missing expression argument reports error`() {
        assertEquals("错误:缺少 expression 参数。", tool.execute("{}"))
    }

    @Test
    fun `trailing operator reports parse error`() {
        assertTrue(eval("1+").startsWith("错误"))
    }
}
