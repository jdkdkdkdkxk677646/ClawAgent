package com.openclaw.clawagent.provider

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.TreeMap

/**
 * Accumulates streamed `tool_calls` fragments into complete calls.
 *
 * OpenAI-compatible streaming sends a tool call in pieces across frames:
 *
 *   frame 1: {"index":0,"id":"call_1","type":"function",
 *             "function":{"name":"calculator","arguments":""}}
 *   frame 2: {"index":0,"function":{"arguments":"{\"expression\":"}}
 *   frame 3: {"index":0,"function":{"arguments":"\"1+1}\"}}
 *
 * The `id` and `name` arrive on the first fragment (set-if-absent semantics),
 * while `arguments` is appended fragment by fragment. Frames carry an `index`
 * key that ties fragments to the right call when the model issues several at
 * once.
 */
class ToolCallAccumulator {

    private class Partial {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    private val parts = TreeMap<Int, Partial>()

    /** Whether at least one tool call fragment has been received. */
    val hasCalls: Boolean get() = parts.isNotEmpty()

    /**
     * Feed one frame's `delta.tool_calls` array. Malformed fragments are
     * skipped — a broken frame must not kill the whole stream.
     */
    fun feed(fragments: JSONArray) {
        for (i in 0 until fragments.length()) {
            val fragment = try {
                fragments.getJSONObject(i)
            } catch (_: JSONException) {
                continue
            }

            // Default the bucket to "next slot" for providers that omit index.
            val index = fragment.optInt("index", parts.size)
            val part = parts.getOrPut(index) { Partial() }

            val id = fragment.optString("id", "")
            if (id.isNotEmpty() && part.id == null) part.id = id

            val fn = fragment.optJSONObject("function") ?: continue
            val fnName = fn.optString("name", "")
            if (fnName.isNotEmpty() && part.name == null) part.name = fnName

            // Arguments may arrive in many fragments (and may legitimately be
            // an empty string on the first frame) — always append.
            part.arguments.append(fn.optString("arguments", ""))
        }
    }

    /**
     * Snapshot the complete calls, ordered by their stream index — the
     * position the model assigned each call in its tool_calls array.
     * Calls whose name never arrived are dropped: they cannot be dispatched.
     */
    fun toCalls(): List<ChatService.ToolCall> =
        parts.entries
            .mapIndexed { position, (_, part) ->
                val id = part.id ?: "call_$position"
                ChatService.ToolCall(id, part.name ?: "", part.arguments.toString())
            }
            .filter { it.name.isNotEmpty() }

    fun clear() = parts.clear()
}
