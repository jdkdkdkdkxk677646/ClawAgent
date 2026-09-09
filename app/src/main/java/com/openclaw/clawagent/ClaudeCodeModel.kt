package com.openclaw.clawagent

/**
 * Claude Code Model — 支持 Claude Code 风格的对话数据结构
 *
 * 核心特性：
 * - System prompt 内置工具调用能力
 * - 支持 multi-turn tool use（工具调用 → 观察结果 → 继续）
 * - 与 OpenAI 兼容的 messages 格式无缝转换
 */

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, ParameterSchema>
)

data class ParameterSchema(
    val type: String,
    val description: String = "",
    val required: Boolean = false,
    val enum: List<String> = emptyList()
)

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any?>
)

data class ToolResult(
    val toolCallId: String,
    val content: String,
    val isError: Boolean = false
)

data class ClaudeMessage(
    val role: String,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolResults: List<ToolResult> = emptyList()
)

/**
 * Claude Code 风格的对话 Builder
 * 自动在 system prompt 中注入工具定义
 */
class ClaudeCodeConversation(private val systemPrompt: String) {

    private val _messages = mutableListOf<ClaudeMessage>()
    val messages: List<ClaudeMessage> get() = _messages.toList()

    private val _tools = mutableListOf<ToolDefinition>()
    val tools: List<ToolDefinition> get() = _tools.toList()

    fun addTool(tool: ToolDefinition): ClaudeCodeConversation {
        _tools.add(tool)
        return this
    }

    fun addTools(tools: List<ToolDefinition>): ClaudeCodeConversation {
        _tools.addAll(tools)
        return this
    }

    fun addUserMessage(content: String): ClaudeCodeConversation {
        _messages.add(ClaudeMessage("user", content))
        return this
    }

    fun addAssistantMessage(
        content: String,
        toolCalls: List<ToolCall> = emptyList()
    ): ClaudeCodeConversation {
        _messages.add(ClaudeMessage("assistant", content, toolCalls))
        return this
    }

    fun addToolResult(result: ToolResult): ClaudeCodeConversation {
        _messages.add(
            ClaudeMessage(
                role = "user",
                content = buildString {
                    appendLine("Tool result for ${result.toolCallId}:")
                    if (result.isError) {
                        appendLine("Error: ${result.content}")
                    } else {
                        appendLine(result.content)
                    }
                },
                toolResults = listOf(result)
            )
        )
        return this
    }

    /**
     * 生成 OpenAI 兼容的请求消息列表
     */
    fun toOpenAIMessages(): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()

        val enhancedSystem = buildSystemPromptWithTools()
        result.add(mapOf("role" to "system", "content" to enhancedSystem))

        for (msg in _messages) {
            when (msg.role) {
                "user" -> {
                    if (msg.toolResults.isNotEmpty()) {
                        val toolContent = msg.toolResults.joinToString("\n\n") { r ->
                            "<tool_response>\n${r.content}\n</tool_response>"
                        }
                        result.add(mapOf("role" to "user", "content" to toolContent))
                    } else {
                        result.add(mapOf("role" to "user", "content" to msg.content))
                    }
                }
                "assistant" -> {
                    if (msg.toolCalls.isNotEmpty()) {
                        val toolCallsJson = msg.toolCalls.map { tc ->
                            mapOf(
                                "id" to tc.id,
                                "type" to "function",
                                "function" to mapOf(
                                    "name" to tc.name,
                                    "arguments" to (tc.arguments as? Map<*, *> ?: emptyMap<String, Any>())
                                        .let { args ->
                                            args.entries.joinToString(",") { e ->
                                                "\"${e.key}\":${toJsonValue(e.value)}"
                                            }.let { "{${it}}" }
                                        }
                                )
                            )
                        }
                        result.add(
                            mapOf(
                                "role" to "assistant",
                                "content" to msg.content.ifEmpty { null },
                                "tool_calls" to toolCallsJson
                            )
                        )
                    } else {
                        result.add(mapOf("role" to "assistant", "content" to msg.content))
                    }
                }
                "system" -> { /* already handled */ }
            }
        }
        return result
    }

    fun clear(): ClaudeCodeConversation {
        _messages.clear()
        return this
    }

    private fun buildSystemPromptWithTools(): String {
        return if (_tools.isEmpty()) {
            systemPrompt
        } else {
            buildString {
                appendLine("## Available Tools")
                appendLine("You have access to the following tools. When you need to use a tool,")
                appendLine("output a tool_call block in JSON format:")
                appendLine()
                appendLine("```json")
                appendLine("{")
                appendLine("  \"tool_call\": {")
                appendLine("    \"name\": \"tool_name\",")
                appendLine("    \"parameters\": { ... }")
                appendLine("  }")
                appendLine("}")
                appendLine("```")
                appendLine()
                appendLine("Available tools:")
                appendLine()
                for (tool in _tools) {
                    appendLine("### ${tool.name}")
                    if (tool.description.isNotEmpty()) {
                        appendLine(tool.description)
                    }
                    appendLine("Parameters:")
                    for ((key, schema) in tool.parameters) {
                        val req = if (schema.required) " (required)" else " (optional)"
                        appendLine("  - $key (${schema.type})$req: ${schema.description}")
                        if (schema.enum.isNotEmpty()) {
                            appendLine("    enum: ${schema.enum.joinToString(", ")}")
                        }
                    }
                    appendLine()
                }
                appendLine("---")
                appendLine()
                append(systemPrompt)
            }
        }
    }

    private fun toJsonValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"${value.replace("\"", "\\\"")}\""
        is Number -> value.toString()
        is Boolean -> value.toString()
        is List<*> -> value.joinToString(",", "[", "]") { toJsonValue(it) }
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { e ->
            "\"${e.key}\":${toJsonValue(e.value)}"
        }
        else -> "\"${value.toString()}\""
    }
}
