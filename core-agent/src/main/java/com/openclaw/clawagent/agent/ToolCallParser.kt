package com.openclaw.clawagent.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Text-embedded tool-call parser — the fallback for providers that don't speak
 * OpenAI's native `tool_calls` channel and instead print a call as JSON in the
 * message body.
 *
 * Migrated (and hardened) out of the app module into `:core-agent` so the agent
 * loop can use it with no Android dependency. Three strategies, tried in order:
 *
 *  1. fenced ```json … ``` blocks — a block that fails to parse as one document
 *     is re-scanned for balanced objects, so surrounding prose doesn't defeat it;
 *  2. the whole message as a single JSON document/array;
 *  3. every balanced `{…}` object found in free text — a **string- and
 *     escape-aware** brace scan, so nested `parameters` objects parse (the old
 *     `[^{}]*` regex could not).
 *
 * Accepted shapes: `tool_calls` (array *or* object), `tool_call` / `tool_use`
 * wrappers, and a bare `{"name":…, "parameters"|"arguments":{…}}`. Duplicate
 * calls (same name + arguments) collapse to one. Parsing never throws — broken
 * fragments degrade into "no call".
 */
object ToolCallParser {

    private val FENCE = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
    private val BLANK_LINES = Regex("\\n{3,}")

    /** Extracts every tool call embedded in [text] (empty when there are none). */
    fun parse(text: String): List<ParsedToolCall> {
        val found = LinkedHashMap<String, ParsedToolCall>()

        // Strategy 1 — fenced blocks.
        for (block in FENCE.findAll(text).map { it.groupValues[1].trim() }) {
            if (collect(block, found)) continue
            for (fragment in balancedObjects(block)) collect(fragment, found)
        }

        // Strategy 2 — the whole message is one JSON document.
        if (found.isEmpty()) collect(text.trim(), found)

        // Strategy 3 — balanced objects scattered through free text.
        if (found.isEmpty()) {
            for (fragment in balancedObjects(text)) collect(fragment, found)
        }

        return found.values.toList()
    }

    /** True if [text] contains at least one parseable call. */
    fun containsToolCall(text: String): Boolean = parse(text).isNotEmpty()

    /**
     * Strips embedded tool-call JSON from an assistant reply so the UI shows
     * only prose. Uses the same balanced scan, so nested argument objects are
     * removed cleanly (the old regexes leaked trailing braces).
     */
    fun extractTextResponse(text: String): String {
        val withoutFences = FENCE.replace(text, "")
        val sb = StringBuilder()
        var i = 0
        while (i < withoutFences.length) {
            val c = withoutFences[i]
            if (c == '{') {
                val end = matchBrace(withoutFences, i)
                if (end > i) {
                    val fragment = withoutFences.substring(i, end + 1)
                    if (looksLikeCall(fragment)) {
                        i = end + 1
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        return BLANK_LINES.replace(sb.toString(), "\n\n").trim()
    }

    // ----- internals --------------------------------------------------------

    /** Returns true when at least one call was added from this fragment. */
    private fun collect(jsonText: String, out: MutableMap<String, ParsedToolCall>): Boolean {
        if (jsonText.isEmpty()) return false
        val before = out.size
        try {
            extract(JSONObject(jsonText), out)
        } catch (_: Exception) {
            // not a single object — maybe a bare array of calls
        }
        if (out.size == before) {
            try {
                val arr = JSONArray(jsonText)
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { extract(it, out) }
            } catch (_: Exception) {
                // not JSON at all
            }
        }
        return out.size > before
    }

    private fun extract(obj: JSONObject, out: MutableMap<String, ParsedToolCall>) {
        obj.optJSONArray("tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { extract(it, out) }
        }
        obj.optJSONObject("tool_calls")?.let { extract(it, out) }
        (obj.optJSONObject("tool_call") ?: obj.optJSONObject("tool_use"))?.let { extract(it, out) }

        val name = obj.optString("name", "")
        if (name.isNotEmpty()) {
            val params = obj.optJSONObject("parameters")
                ?: obj.optJSONObject("arguments")
                ?: JSONObject()
            add(out, name, obj.optString("id", ""), params)
        }
    }

    private fun add(
        out: MutableMap<String, ParsedToolCall>,
        name: String,
        id: String,
        params: JSONObject,
    ) {
        val key = name + '\u0000' + params.toString()
        if (out.containsKey(key)) return
        out[key] = ParsedToolCall(
            id = id.ifEmpty { generateId() },
            name = name,
            parameters = jsonObjectToMap(params),
        )
    }

    /** Every balanced `{…}` object in [text]; string- and escape-aware. */
    private fun balancedObjects(text: String): List<String> {
        val results = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            if (text[i] == '{') {
                val end = matchBrace(text, i)
                if (end > i) {
                    results.add(text.substring(i, end + 1))
                    i = end + 1
                    continue
                }
            }
            i++
        }
        return results
    }

    /** Index of the `}` matching the `{` at [start], or -1 if unbalanced. */
    private fun matchBrace(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return -1
    }

    private fun looksLikeCall(fragment: String): Boolean = try {
        val obj = JSONObject(fragment)
        obj.has("name") || obj.has("tool_call") || obj.has("tool_use") || obj.has("tool_calls")
    } catch (_: Exception) {
        false
    }

    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
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

    private fun jsonArrayToList(arr: JSONArray): List<Any?> =
        (0 until arr.length()).map { i ->
            when (val v = arr.opt(i)) {
                is JSONObject -> jsonObjectToMap(v)
                is JSONArray -> jsonArrayToList(v)
                else -> v
            }
        }

    private fun generateId(): String =
        "call_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
}

/**
 * A tool call recovered from assistant text (as opposed to the native
 * `tool_calls` channel). [parameters] is the decoded argument map.
 */
data class ParsedToolCall(
    val id: String,
    val name: String,
    val parameters: Map<String, Any?>,
) {
    /** Arguments re-encoded as a JSON string for [ChatService.ToolCall]. */
    fun argumentsJson(): String = wrapObject(parameters).toString()

    fun getString(key: String, default: String = ""): String =
        (parameters[key] as? String) ?: default

    fun getInt(key: String, default: Int = 0): Int =
        (parameters[key] as? Number)?.toInt() ?: default

    fun getBoolean(key: String, default: Boolean = false): Boolean =
        (parameters[key] as? Boolean) ?: default

    private companion object {
        fun wrapObject(map: Map<String, Any?>): JSONObject {
            val obj = JSONObject()
            for ((k, v) in map) obj.put(k, wrapValue(v))
            return obj
        }

        fun wrapValue(value: Any?): Any? = when (value) {
            is Map<*, *> -> {
                val obj = JSONObject()
                for ((k, v) in value) obj.put(k.toString(), wrapValue(v))
                obj
            }

            is List<*> -> {
                val arr = JSONArray()
                for (item in value) arr.put(wrapValue(item))
                arr
            }

            else -> value
        }
    }
}
