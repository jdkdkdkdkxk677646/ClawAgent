package com.openclaw.clawagent

import org.json.JSONArray
import org.json.JSONObject

/**
 * ToolCallParser — 解析 AI 返回的工具调用 JSON
 *
 * 支持三种格式：
 * 1. OpenAI function calling 格式（tool_calls 字段）
 * 2. Claude Code 风格的内联 JSON 块（```json ... ```）
 * 3. 裸 JSON 对象（{"tool_call": {...}}）
 */
object ToolCallParser {

    /**
     * 从 assistant 回复中提取所有工具调用
     */
    fun parse(text: String): List<ParsedToolCall> {
        val results = mutableListOf<ParsedToolCall>()

        // 策略 1: 提取 ```json ... ``` 代码块中的 tool_call
        val codeBlockPattern = Regex("```json\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        for (match in codeBlockPattern.findAll(text)) {
            val block = match.groupValues[1].trim()
            try {
                val json = JSONObject(block)
                val toolCall = json.optJSONObject("tool_call")
                    ?: json.optJSONObject("tool_use")
                    ?: json
                if (toolCall.has("name")) {
                    results.add(parseToolCallObject(toolCall))
                }
            } catch (_: Exception) { /* not valid JSON */ }
        }

        // 策略 2: 查找裸 JSON 中的 tool_call
        if (results.isEmpty()) {
            try {
                val json = JSONObject(text.trim())
                val toolCall = json.optJSONObject("tool_call")
                    ?: json.optJSONObject("tool_use")
                if (toolCall != null && toolCall.has("name")) {
                    results.add(parseToolCallObject(toolCall))
                }
                val arr = json.optJSONArray("tool_calls")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val tc = arr.getJSONObject(i)
                        results.add(
                            ParsedToolCall(
                                id = tc.optString("id", generateId()),
                                name = tc.optString("name", ""),
                                parameters = jsonObjectToMap(
                                    tc.optJSONObject("parameters") ?: JSONObject()
                                )
                            )
                        )
                    }
                }
            } catch (_: Exception) { /* not valid JSON */ }
        }

        // 策略 3: 宽松匹配
        if (results.isEmpty()) {
            val loosePattern = Regex(
                """\{[^{}]*"name"\s*:\s*"([^"]+)"[^{}]*\}""",
                RegexOption.DOT_MATCHES_ALL
            )
            for (match in loosePattern.findAll(text)) {
                try {
                    val json = JSONObject(match.value)
                    val name = json.optString("name")
                    if (name.isNotEmpty()) {
                        val params = json.optJSONObject("parameters")
                            ?: json.optJSONObject("arguments")
                            ?: JSONObject()
                        results.add(
                            ParsedToolCall(
                                id = generateId(),
                                name = name,
                                parameters = jsonObjectToMap(params)
                            )
                        )
                    }
                } catch (_: Exception) {}
            }
        }

        return results
    }

    /**
     * 提取纯文本回复（去除工具调用块）
     */
    fun extractTextResponse(text: String): String {
        var cleaned = text.replace(
            Regex("```json\\s*[\\s\\S]*?```", RegexOption.IGNORE_CASE), ""
        )
        cleaned = cleaned.replace(Regex("\\{[\\s\\S]*?\"tool_call\"[\\s\\S]*?\\}"), "")
        cleaned = cleaned.replace(Regex("\\{[\\s\\S]*?\"tool_use\"[\\s\\S]*?\\}"), "")
        cleaned = cleaned.replace(Regex("\n{3,}"), "\n\n")
        return cleaned.trim()
    }

    fun containsToolCall(text: String): Boolean = parse(text).isNotEmpty()

    private fun parseToolCallObject(obj: JSONObject): ParsedToolCall {
        val name = obj.optString("name", "")
        val params = obj.optJSONObject("parameters")
            ?: obj.optJSONObject("arguments")
            ?: JSONObject()
        return ParsedToolCall(
            id = obj.optString("id", generateId()),
            name = name,
            parameters = jsonObjectToMap(params)
        )
    }

    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = when (val v = obj.opt(key)) {
                null -> null
                is JSONObject -> jsonObjectToMap(v)
                is JSONArray -> jsonArrayToList(v)
                else -> v
            }
        }
        return map
    }

    private fun jsonArrayToList(arr: JSONArray): List<Any?> {
        return (0 until arr.length()).map { i ->
            when (val v = arr.opt(i)) {
                is JSONObject -> jsonObjectToMap(v)
                is JSONArray -> jsonArrayToList(v)
                else -> v
            }
        }
    }

    private fun generateId(): String =
        "call_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"

    private val BUILTIN_TOOL_NAMES = listOf(
        "get_weather", "search", "calculate", "read_file",
        "write_file", "execute_command", "fetch_url"
    )
}

/**
 * 解析后的工具调用
 */
data class ParsedToolCall(
    val id: String,
    val name: String,
    val parameters: Map<String, Any?>
) {
    fun getString(key: String, default: String = ""): String =
        (parameters[key] as? String) ?: default

    fun getInt(key: String, default: Int = 0): Int =
        (parameters[key] as? Number)?.toInt() ?: default

    fun getBoolean(key: String, default: Boolean = false): Boolean =
        (parameters[key] as? Boolean) ?: default
}
