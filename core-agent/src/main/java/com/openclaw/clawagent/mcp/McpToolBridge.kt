package com.openclaw.clawagent.mcp

import com.openclaw.clawagent.agent.AgentTool
import org.json.JSONObject

/**
 * Adapts one remote MCP tool ([McpToolDef]) into the [AgentTool] interface so
 * it slots into the regular toolbox — advertised through the same
 * `tools` array, dispatched through the same [com.openclaw.clawagent.agent.AgentToolbox.execute].
 *
 * Naming: `mcp_` prefix keeps remote names collision-free with the built-in
 * claws AND rides the existing per-tool kill switches (`disabledTools` stores
 * the prefixed name, so Settings → 工具配置 controls MCP tools for free).
 * The description carries an `[MCP]` marker so both the model and the
 * directive prompt can tell where the claw comes from.
 */
class McpToolBridge(
    private val client: McpClient,
    private val def: McpToolDef,
) : AgentTool {

    override val name: String = "mcp_${def.name}"

    override val description: String = buildString {
        append("[MCP]")
        def.title?.let { append(" $it:") }
        append(def.description.ifBlank { "(服务器未提供描述)" })
    }

    /** MCP `inputSchema` is already a JSON Schema object — pass through. */
    override val parametersJson: String = def.inputSchemaJson

    override fun execute(arguments: String): String = client.callTool(def.name, arguments)

    companion object {
        fun bridgeAll(client: McpClient, defs: List<McpToolDef>): List<AgentTool> =
            defs.map { McpToolBridge(client, it) }
    }
}
