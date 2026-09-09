package com.openclaw.clawagent.agent

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Built-in Agent tools exposed to the model via OpenAI-compatible function
 * calling (`tools` request parameter). Keeping this package free of Android
 * imports means every tool is unit-testable on the plain JVM.
 *
 * Wire contract: the model answers with `tool_calls` naming one of the tools
 * plus a JSON `arguments` string; the app executes the tool locally and sends
 * the result back as a `role:"tool"` message. See MainActivity's agent loop.
 */
interface AgentTool {
    /** Function name the model sees, e.g. "calculator". */
    val name: String

    /** Human/model-readable description of when to use this tool. */
    val description: String

    /** JSON Schema (as a string) describing the `arguments` object. */
    val parametersJson: String

    /**
     * Execute the tool. Must never throw — return a model-readable error
     * string instead, so a bad tool call degrades into an answer rather
     * than crashing the agent loop.
     */
    fun execute(arguments: String): String
}

/** Registry: request-side JSON + dispatch. */
object AgentTools {

    val ALL: List<AgentTool> = listOf(CalculatorTool(), CurrentTimeTool())

    private val byName = ALL.associateBy { it.name }

    /**
     * The `tools` array for the chat completions request body.
     */
    fun requestJson(): JSONArray =
        JSONArray().apply {
            ALL.forEach { tool ->
                put(
                    JSONObject().apply {
                        put("type", "function")
                        put(
                            "function",
                            JSONObject().apply {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", JSONObject(tool.parametersJson))
                            }
                        )
                    }
                )
            }
        }

    /** Dispatch a model-issued call. Unknown names degrade into an error string. */
    fun execute(name: String, arguments: String): String =
        byName[name]?.execute(arguments)
            ?: "未找到名为 \"$name\" 的工具。可用工具:${byName.keys.joinToString()}"
}

/**
 * Safe arithmetic evaluator. No reflection, no eval — a hand-written
 * recursive-descent parser over a whitelisted grammar:
 *
 *   expr   := term (('+' | '-') term)*
 *   term   := unary (('*' | '/' | '%') unary)*
 *   unary  := ('-')? power
 *   power  := atom ('^' unary)?          // right-associative
 *   atom   := NUMBER | '(' expr ')'
 *
 * Inputs are length-capped and charset-checked before parsing, so even a
 * hostile model cannot smuggle in anything beyond arithmetic.
 */
class CalculatorTool : AgentTool {

    override val name = "calculator"
    override val description =
        "计算一个算术表达式并返回结果。支持 + - * / % ^ 和括号。当需要精确数学计算时使用。" +
            "参数 expression 是表达式字符串,例如 \"(2+2)*3\"。"
    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "expression": {
              "type": "string",
              "description": "算术表达式,如 (2+2)*3,只允许数字和 + - * / % ^ ( )"
            }
          },
          "required": ["expression"]
        }
    """.trimIndent()

    override fun execute(arguments: String): String {
        val expression = try {
            JSONObject(arguments).optString("expression", "")
        } catch (_: Exception) {
            // Model sent a bare string or malformed JSON — accept it as the expression itself.
            arguments.trim().trim('"')
        }
        if (expression.isEmpty()) return "错误:缺少 expression 参数。"

        val result = try {
            evaluate(expression)
        } catch (e: ArithmeticException) {
            return "错误:${e.message}"
        } catch (e: IllegalArgumentException) {
            return "错误:${e.message}"
        } catch (e: Exception) {
            // Last-resort guard: any unexpected parser bug becomes an error
            // string instead of crashing the caller (MainActivity also wraps
            // execution in runCatching).
            return "错误:${e.message ?: e.javaClass.simpleName}"
        }
        return format(result)
    }

    // ----- evaluation -------------------------------------------------------

    internal fun evaluate(expression: String): Double {
        if (expression.length > MAX_LENGTH) {
            throw IllegalArgumentException("表达式过长(>${MAX_LENGTH}字符)")
        }
        val invalid = expression.firstOrNull { it !in ALLOWED_CHARS }
        if (invalid != null) {
            throw IllegalArgumentException("表达式包含不允许的字符 '$invalid'")
        }

        val parser = Parser(expression)
        val value = parser.parseExpression()
        parser.expectEnd()
        if (value.isNaN()) throw ArithmeticException("计算结果不是数字")
        if (value.isInfinite()) throw ArithmeticException("计算结果溢出")
        return value
    }

    private class Parser(private val src: String) {
        private var pos = 0

        fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                when (peek()) {
                    '+' -> { advance(); value += parseTerm() }
                    '-' -> { advance(); value -= parseTerm() }
                    else -> return value
                }
            }
        }

        private fun parseTerm(): Double {
            var value = parseUnary()
            while (true) {
                when (peek()) {
                    '*' -> { advance(); value *= parseUnary() }
                    '/' -> {
                        advance()
                        val divisor = parseUnary()
                        if (divisor == 0.0) throw ArithmeticException("除数不能为零")
                        value /= divisor
                    }
                    '%' -> {
                        advance()
                        val divisor = parseUnary()
                        if (divisor == 0.0) throw ArithmeticException("取模的除数不能为零")
                        value %= divisor
                    }
                    else -> return value
                }
            }
        }

        private fun parseUnary(): Double {
            if (peek() == '-') {
                advance()
                return -parseUnary()
            }
            if (peek() == '+') { // tolerate unary plus
                advance()
                return parseUnary()
            }
            return parsePower()
        }

        private fun parsePower(): Double {
            val base = parseAtom()
            if (peek() == '^') {
                advance()
                // Right-associative: 2^3^2 == 2^9, not 8^2.
                val exponent = parseUnary()
                return Math.pow(base, exponent)
            }
            return base
        }

        private fun parseAtom(): Double {
            if (peek() == '(') {
                advance()
                val value = parseExpression()
                if (peek() != ')') throw IllegalArgumentException("括号不匹配,缺少 ')'")
                advance()
                return value
            }
            val start = pos
            while (peek().isDigit() || peek() == '.') pos++
            val token = src.substring(start, pos)
            if (token.isEmpty()) throw IllegalArgumentException("在位置 $start 附近缺少数字")
            if (token.count { it == '.' } > 1) throw IllegalArgumentException("数字 \"$token\" 格式不正确")
            return token.toDoubleOrNull() ?: throw IllegalArgumentException("无法解析数字 \"$token\"")
        }

        fun expectEnd() {
            skipSpace()
            if (pos < src.length) {
                throw IllegalArgumentException("在位置 $pos 存在多余的字符 '${src[pos]}'")
            }
        }

        /** Whitespace is allowed anywhere between tokens. */
        private fun skipSpace() {
            while (pos < src.length && src[pos] == ' ') pos++
        }

        private fun peek(): Char {
            skipSpace()
            return if (pos < src.length) src[pos] else ' '
        }

        private fun advance() {
            pos++
        }
    }

    private fun format(value: Double): String {
        // Whole numbers print without the trailing ".0" — models chain nicer.
        if (value == Math.floor(value) && !value.isInfinite() && Math.abs(value) < 1e15) {
            return value.toLong().toString()
        }
        return value.toString()
    }

    companion object {
        private const val MAX_LENGTH = 200
        private const val ALLOWED_CHARS = "0123456789.+-*/%^() "
    }
}

/**
 * Returns the device's current local date/time. Lets the agent answer
 * "今天几号 / 现在几点" without the model inventing a date.
 */
class CurrentTimeTool : AgentTool {

    override val name = "current_time"
    override val description =
        "获取设备当前的日期和时间(本地时区)。当用户询问现在的时间、日期或星期时使用。" +
            "不需要任何参数。"
    override val parametersJson = """
        {
          "type": "object",
          "properties": {},
          "required": []
        }
    """.trimIndent()

    override fun execute(arguments: String): String {
        val now: ZonedDateTime = ZonedDateTime.ofInstant(Instant.now(), ZoneId.systemDefault())
        val weekday = now.dayOfWeek.getDisplayName(
            java.time.format.TextStyle.FULL, Locale.CHINESE
        )
        val formatted = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        return "当前时间:$formatted($weekday),时区:${now.zone.id},Unix 时间戳:${now.toInstant().epochSecond} 秒"
    }
}
