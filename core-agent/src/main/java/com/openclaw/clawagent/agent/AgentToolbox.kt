package com.openclaw.clawagent.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * AgentToolbox — the registry that turns Claw into an agent. Holds the set
 * of [AgentTool]s the model may reach for via OpenAI function calling and
 * handles both wire directions:
 *
 *  - [requestJson] builds the `tools` array for the chat completions body;
 *  - [execute] dispatches a model-issued call back into the right tool.
 *
 * Lives in the pure-JVM `:core-agent` module: the registry itself knows
 * nothing about Android or about which concrete tools exist. The factories
 * that assemble concrete toolsets live where their dependencies live —
 * `Toolsets.core()` in `:core-tools`, `AgentWiring.forAndroid()` in `:app`.
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

    /**
     * A toolbox with extra claws bolted on — the MCP bridge appends remote
     * tools at runtime after a successful handshake (v4.2).
     */
    fun withTools(extra: List<AgentTool>): AgentToolbox =
        AgentToolbox(tools + extra)
}
