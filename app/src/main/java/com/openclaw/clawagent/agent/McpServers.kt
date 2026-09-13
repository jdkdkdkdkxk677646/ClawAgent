package com.openclaw.clawagent.agent

import com.openclaw.clawagent.mcp.McpClient
import com.openclaw.clawagent.mcp.McpToolBridge
import com.openclaw.clawagent.mcp.McpToolDef

/**
 * Multi-server MCP orchestrator.
 *
 * Replaces the old single-endpoint path (`prefs.mcpEndpoint` + `prefs.mcpToken`)
 * with a list of [McpServerConfig]. Each server is connected independently;
 * failures are degraded (0 tools from that server, counted in the summary).
 * Tool name collisions across servers are resolved by prefixing with the
 * server's alias (`mcp_<alias>_<tool>`), guaranteeing a flat unique namespace.
 *
 * Pure-logic — no Android imports, fully unit-testable on the JVM.
 */
object McpServers {

    /** One remote MCP server to connect to. */
    data class McpServerConfig(
        val name: String,       // human label shown in UI
        val endpoint: String,   // Streamable-HTTP URL
        val authToken: String?, // optional Bearer token
    )

    /** Result of connecting to one server: its tools + identity metadata. */
    data class McsSession(
        val config: McpServerConfig,
        val tools: List<AgentTool>,
        val serverInfo: String,
        val error: String?,
    ) {
        val success get() = error == null
    }

    /**
     * Parse a JSON array string into [McpServerConfig] list.
     * Bad entries are skipped (never throws); duplicate names get a numeric suffix.
     */
    fun parseConfigs(json: String): List<McpServerConfig> {
        val out = ArrayList<McpServerConfig>()
        val seen = mutableSetOf<String>()
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val endpoint = obj.optString("endpoint", "").trim()
                if (endpoint.isEmpty()) continue
                val name = (obj.optString("name", null) ?: endpoint.substringBeforeLast('/')).trim()
                val token = obj.optString("authToken", null)?.trim()
                val alias = name.ifEmpty { endpoint.substringBeforeLast('/') }
                // deduplicate by appending a counter
                val uniqueName = if (alias in seen) {
                    var suffix = 2
                    var candidate = "$alias-$suffix"
                    while (candidate in seen) {
                        suffix++
                        candidate = "$alias-$suffix"
                    }
                    candidate
                } else alias
                seen.add(uniqueName)
                out += McpServerConfig(name = uniqueName, endpoint = endpoint, authToken = token.ifEmpty { null })
            }
        } catch (_: Exception) {
            // Corrupt JSON: return whatever we parsed so far (usually empty).
        }
        return out
    }

    /**
     * Connect to every configured server. Single-server failure degrades to 0
     * tools from that server; the rest keep running. Returns sessions in config
     * order.
     *
     * [clientFactory] is the test seam: inject a fake that returns canned clients.
     * [onLog] is optional diagnostics sink.
     */
    fun connectAll(
        configs: List<McpServerConfig>,
        clientFactory: (endpoint: String, token: String?) -> McpClient =
            { ep, tok -> McpClient(ep, authToken = tok) },
        onLog: (String) -> Unit = {},
    ): List<McsSession> {
        val result = ArrayList<McsSession>(configs.size)
        for (cfg in configs) {
            try {
                val client = clientFactory(cfg.endpoint, cfg.authToken)
                client.connect()
                val defs = client.listTools()
                val info = client.serverInfo.ifEmpty { cfg.endpoint }
                val tools = defs.map { def ->
                    object : AgentTool {
                        override val name: String = "mcp_${cfg.name}_${def.name}"
                        override val description: String = buildString {
                            append("[MCP] ")
                            def.title?.let { append("$it: ") }
                            append(def.description.ifEmpty { "(服务器未提供描述)" })
                        }
                        override val parametersJson: String = def.inputSchemaJson
                        override fun execute(arguments: String): String = client.callTool(def.name, arguments)
                    }
                }
                result += McsSession(config = cfg, tools = tools, serverInfo = info, error = null)
                onLog("MCP connected: $info (${tools.size} tools)")
            } catch (e: Exception) {
                onLog("MCP connect failed for ${cfg.name}: ${e.message}")
                result += McsSession(
                    config = cfg,
                    tools = emptyList(),
                    serverInfo = "",
                    error = e.message ?: e.javaClass.simpleName,
                )
            }
        }
        return result
    }
}
