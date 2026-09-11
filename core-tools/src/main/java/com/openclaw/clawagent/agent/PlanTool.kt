package com.openclaw.clawagent.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * The agent's planning claw — the counterpart of a working agent's
 * task-tracker. For multi-step work the model is instructed to
 * `set` a goal + steps before starting, `mark` each step as it completes,
 * and summarize when the last one is checked off. The state is a plain
 * in-memory singleton (one plan per process — the agent is a single
 * conversation at a time) and every reply renders a ☐/☑ checklist, so the
 * user can watch progress move in the bubble.
 *
 * Pure JVM, fully testable; thread-safety is coarse (synchronized) because
 * tool calls execute on Dispatchers.Default.
 */
object PlanState {

    private var goal: String? = null
    private val done = mutableListOf<Boolean>()
    private var steps: List<String> = emptyList()

    @Synchronized
    fun set(goalText: String, stepList: List<String>): String {
        val cleanSteps = stepList.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_STEPS)
        if (cleanSteps.isEmpty()) return "错误:计划至少需要一个步骤。"
        goal = goalText.trim().ifEmpty { "未命名任务" }
        steps = cleanSteps
        done.clear()
        repeat(cleanSteps.size) { done.add(false) }
        return "计划已建立(共 ${cleanSteps.size} 步):\n" + render()
    }

    /** 1-based index; out-of-range degrades into a readable error. */
    @Synchronized
    fun mark(stepNumber: Int): String {
        if (steps.isEmpty()) return "错误:还没有计划,先用 action=set 建立。"
        if (stepNumber < 1 || stepNumber > steps.size) {
            return "错误:步骤编号要在 1~${steps.size} 之间。"
        }
        done[stepNumber - 1] = true
        val finished = done.count { it }
        return if (finished == steps.size) {
            val summary = "✅ 计划全部完成(${steps.size}/${steps.size}):\n" + render()
            reset()
            summary
        } else {
            "进度 $finished/${steps.size}:\n" + render()
        }
    }

    @Synchronized
    fun status(): String =
        if (steps.isEmpty()) "当前没有进行中的计划。" else "进度 ${done.count { it }}/${steps.size}:\n" + render()

    @Synchronized
    fun render(): String = steps.mapIndexed { i, s ->
        (if (done[i]) "☑" else "☐") + " " + s
    }.joinToString("\n")

    @Synchronized
    private fun reset() {
        goal = null
        steps = emptyList()
        done.clear()
    }

    const val MAX_STEPS = 12
}

/**
 * Tool surface for [PlanState]. The agent directive tells the model when to
 * plan; this tool is how it actually does.
 */
class PlanTool : AgentTool {

    override val name = "task_plan"
    override val description =
        "任务规划与进度追踪。复杂任务(3 步以上)开工前用 action=set 建立计划(goal + steps 列表);" +
            "每完成一步用 action=mark(step 为 1 起始的步骤编号)打勾,系统会自动汇报进度;" +
            "action=status 随时查看当前计划。最终回复前请确认所有步骤已完成。"
    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["set", "mark", "status"],
              "description": "set 建立计划,mark 标记某步完成,status 查看进度"
            },
            "goal": {
              "type": "string",
              "description": "任务目标(action=set 时必填)"
            },
            "steps": {
              "type": "array",
              "items": {"type": "string"},
              "description": "步骤列表(action=set 时必填,如 [\"搜索价格\",\"写入笔记\",\"设提醒\"])"
            },
            "step": {
              "type": "integer",
              "description": "要标记完成的步骤编号,从 1 开始(action=mark 时必填)"
            }
          },
          "required": ["action"]
        }
    """.trimIndent()

    override fun execute(arguments: String): String {
        val args = try {
            JSONObject(arguments)
        } catch (_: Exception) {
            return "错误:参数不是合法 JSON。"
        }
        return when (args.optString("action", "").trim().lowercase()) {
            "set" -> {
                val stepsJson = args.optJSONArray("steps") ?: JSONArray()
                val steps = (0 until stepsJson.length()).map { stepsJson.optString(it, "") }
                PlanState.set(args.optString("goal", ""), steps)
            }

            "mark" -> {
                val step = args.optInt("step", -1)
                if (step == -1) "错误:mark 需要 step 参数(1 起始的步骤编号)。" else PlanState.mark(step)
            }

            "status" -> PlanState.status()

            else -> "错误:未知 action,支持 set / mark / status。"
        }
    }
}
