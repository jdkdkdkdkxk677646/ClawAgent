package com.openclaw.clawagent.agent

import org.json.JSONObject
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The agent's "code interpreter" claw: a restricted JavaScript sandbox so the
 * model can *verify* instead of guess — exact arithmetic, date math, JSON and
 * text munging. Backed by the embedded Rhino 1.7.14 engine, no network calls
 * and no extra runtime services.
 *
 * The sandbox is deliberately hostile to escape:
 *  - interpreter-only (`optimizationLevel = -1`) — no bytecode/JIT on ART;
 *  - a [org.mozilla.javascript.ClassShutter] that denies *every* Java class,
 *    closing the LiveConnect bridge to `java.lang.Runtime` and friends;
 *  - a double budget: an instruction observer (about 50M ops) *and* a wall-clock
 *    timeout — a `while(true){}` pays for itself within seconds;
 *  - `setMaximumInterpreterStackDepth` guards runaway recursion;
 *  - a brand-new standard-object scope on every run, so nothing leaks between
 *    invocations.
 *
 * Pure JVM (no `android.*`), so the whole sandbox is exercised by JVM tests.
 */
class JsTool : AgentTool {

    override val name = "run_js"

    override val description =
        "在受限沙箱里执行一段 JavaScript 并返回结果(ES5,箭头函数/let/const/模板字符串可用;Promise/async 不保证)。" +
            "适合口算易错的场景:精确计算、日期推算、JSON/文本加工、批量字符串处理等。" +
            "用 console.log(...) 打印中间结果;最后一个表达式的值会作为返回值返回。" +
            "沙箱禁止访问 Java/文件/网络,并有执行时间与指令数上限。" +
            "参数:code(必填)、timeout_ms(可选,默认 3000,范围 500-8000)。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "code": {
              "type": "string",
              "description": "要执行的 JavaScript 源码,例如 \"[1,2,3].map(x => x*2)\""
            },
            "timeout_ms": {
              "type": "integer",
              "description": "执行超时毫秒数,默认 3000,最小 500,最大 8000"
            }
          },
          "required": ["code"]
        }
    """.trimIndent()

    override fun execute(arguments: String): String {
        val args = try {
            JSONObject(arguments)
        } catch (_: Exception) {
            return "错误:参数不是合法 JSON。"
        }
        val code = args.optString("code", "")
        if (code.isBlank()) return "错误:缺少 code 参数。"
        if (code.length > MAX_CODE_LENGTH) {
            return "错误:脚本过长(${code.length} 字符,上限 $MAX_CODE_LENGTH)。"
        }
        val timeoutMs = args.optInt("timeout_ms", DEFAULT_TIMEOUT_MS)
            .coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "claw-run-js").apply { isDaemon = true }
        }
        return try {
            executor.submit<String> { runScript(code) }
                .get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            "错误:脚本执行超时(超过 ${timeoutMs}ms),已中止(可能存在死循环)。"
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            "错误:${cause.message ?: cause.javaClass.simpleName}"
        } catch (e: Throwable) {
            "错误:${e.message ?: e.javaClass.simpleName}"
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * Runs on a dedicated worker thread. Any thrown [Error]/[Exception] is
     * surfaced as a readable string by [execute]; this method only returns a
     * human-readable error for script-level failures (syntax/runtime).
     */
    internal fun runScript(code: String): String {
        val factory = object : ContextFactory() {
            override fun makeContext(): Context =
                super.makeContext().also { it.setOptimizationLevel(-1) }

            override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                throw Error("指令预算超限(超过 $INSTRUCTION_BUDGET 条),疑似死循环")
            }
        }

        val cx = factory.enterContext()
        try {
            cx.setOptimizationLevel(-1)
            cx.setLanguageVersion(Context.VERSION_ES6)
            cx.setMaximumInterpreterStackDepth(MAX_STACK_DEPTH)
            cx.setInstructionObserverThreshold(INSTRUCTION_BUDGET)
            // Deny every Java class: closes the LiveConnect escape hatch.
            cx.setClassShutter { false }

            val scope = cx.initStandardObjects()
            val logs = StringBuilder()
            installConsole(cx, scope, logs)

            val result: Any? = try {
                cx.evaluateString(scope, code, "<run_js>", 1, null)
            } catch (e: RhinoException) {
                return "错误:${e.details() ?: e.message}(第 ${e.lineNumber()} 行)"
            }
            return renderResult(cx, scope, logs, result)
        } finally {
            Context.exit()
        }
    }

    private fun installConsole(cx: Context, scope: Scriptable, logs: StringBuilder) {
        val console = cx.newObject(scope)
        CONSOLE_LEVELS.forEach { level ->
            val sink = object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>,
                ): Any? {
                    if (args.isNotEmpty()) {
                        logs.append(args.joinToString(" ") { toDisplayText(cx, scope, it) })
                    }
                    logs.append('\n')
                    return Context.getUndefinedValue()
                }
            }
            ScriptableObject.putProperty(console, level, sink)
        }
        ScriptableObject.putProperty(scope, "console", console)
    }

    private fun renderResult(
        cx: Context,
        scope: Scriptable,
        logs: StringBuilder,
        result: Any?,
    ): String {
        val sb = StringBuilder()
        if (logs.isNotEmpty()) sb.append("控制台输出:\n").append(logs)
        val rendered = when {
            Undefined.isUndefined(result) -> "无返回值(undefined)"
            result == null -> "null"
            result is String -> result
            result is Number || result is Boolean -> Context.toString(result)
            else -> stringify(cx, scope, result)
        }
        sb.append("返回值:").append(rendered)
        return sb.toString()
    }

    private fun stringify(cx: Context, scope: Scriptable, value: Any?): String = try {
        val json = NativeJSON.stringify(cx, scope, value, null, null)
        if (json == null || Undefined.isUndefined(json)) Context.toString(value) else json.toString()
    } catch (_: Exception) {
        Context.toString(value)
    }

    private fun toDisplayText(cx: Context, scope: Scriptable, value: Any?): String = when {
        value is String -> value
        Undefined.isUndefined(value) -> "undefined"
        value == null -> "null"
        value is Number || value is Boolean -> Context.toString(value)
        else -> stringify(cx, scope, value)
    }

    companion object {
        const val MAX_CODE_LENGTH = 20_000
        const val DEFAULT_TIMEOUT_MS = 3_000
        const val MIN_TIMEOUT_MS = 500
        const val MAX_TIMEOUT_MS = 8_000
        const val INSTRUCTION_BUDGET = 50_000_000
        const val MAX_STACK_DEPTH = 256
        private val CONSOLE_LEVELS = listOf("log", "info", "warn", "error")
    }
}
