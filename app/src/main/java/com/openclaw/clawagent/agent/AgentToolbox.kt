package com.openclaw.clawagent.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * AgentToolbox — the registry that turns Claw into an agent. Holds the set
 * of [AgentTool]s the model may reach for via OpenAI function calling and
 * handles both wire directions:
 *
 *  - [requestJson] builds the `tools` array for the chat completions body;
 *  - [execute] dispatches a model-issued call back into the right tool.
 *
 * Two factory sets:
 *  - [core]: framework-free tools only (JVM unit tests, demo builds);
 *  - [forAndroid]: the full claw — adds everything that touches the
 *    Android framework (device info, clipboard, notifications, ...).
 */
class AgentToolbox(private val tools: List<AgentTool>) {

    private val byName: Map<String, AgentTool> = tools.associateBy { it.name }

    /** Tool names in registration order (used in UI copy and tests). */
    val names: List<String> get() = tools.map { it.name }

    /** One-line-per-tool summary, injected into the agent directive prompt. */
    fun summary(): String = tools.joinToString("\n") { "- **${it.name}**:${it.description}" }

    /** The `tools` array for the chat completions request body. */
    fun requestJson(): JSONArray =
        JSONArray().apply {
            tools.forEach { tool ->
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

    /**
     * A toolbox restricted to the given tool names — the per-tool kill
     * switches behind Settings → 工具配置. Disabled tools are neither
     * advertised in `tools` nor dispatchable, so the model can never call
     * something the user turned off.
     */
    fun filtered(enabledNames: Collection<String>): AgentToolbox =
        AgentToolbox(tools.filter { it.name in enabledNames })

    companion object {

        /**
         * Framework-free toolset: math, clock, notes (+search), web search,
         * http fetch and planning. [notesDir] must be provided (tests pass a
         * temp dir).
         */
        fun core(notesDir: File): AgentToolbox = AgentToolbox(
            listOf(
                CalculatorTool(),
                CurrentTimeTool(),
                NoteTool(notesDir),
                HttpRequestTool(),
                WebSearchTool(),
                PlanTool(),
            )
        )

        /** The full claw: core tools + everything that needs the framework. */
        fun forAndroid(context: Context): AgentToolbox = AgentToolbox(
            listOf(
                CalculatorTool(),
                CurrentTimeTool(),
                NoteTool(File(context.filesDir, "agent_notes")),
                HttpRequestTool(),
                WebSearchTool(),
                PlanTool(),
                DeviceInfoTool(context.applicationContext),
                ClipboardTool(context.applicationContext),
                NotificationTool(context.applicationContext),
                OpenUrlTool(context.applicationContext),
                ReminderTool(context.applicationContext),
            )
        )
    }
}
