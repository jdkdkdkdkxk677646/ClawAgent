package com.openclaw.clawagent.provider

import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Server-Sent Events parser for OpenAI-compatible streaming responses.
 *
 * Why hand-rolled? The original code did `chunk.split("\n")` which silently
 * breaks at chunk boundaries: if one network `read` ends mid-line and the
 * next begins mid-line, the affected line is malformed JSON. This parser keeps
 * a stateful buffer across reads, so a line never straddles chunk boundaries
 * in a way that gets lost.
 *
 * The wire format we parse:
 *   data: {"choices":[{"delta":{"content":"hi"}}]}
 *   data: {"choices":[{"delta":{"tool_calls":[...]}}]}   // agent tool calls
 *   data: {"choices":[],"usage":{...}}                   // usage (usually final chunk)
 *   data: [DONE]
 *
 * Non-`data:` lines (event ids, comments, heartbeats) are ignored, which is
 * fine for OpenAI-compatible APIs.
 */
class SseStreamParser {

    /** One parsed stream frame: text content, tool-call fragments, or both. */
    class Frame(
        /** Text delta; empty string when the frame carried none. */
        val contentDelta: String,
        /** Raw `delta.tool_calls` fragments, or null when absent. */
        val toolCallFragments: JSONArray?,
        /**
         * Top-level `usage` object carried by this frame (usually only the
         * final, choices-less chunk has one). Null when absent — many
         * compatible endpoints never report usage at all, which is a normal
         * state, not an error.
         */
        val usage: Usage? = null,
    )

    /**
     * Token accounting from the OpenAI `usage` object
     * (`{"prompt_tokens":..,"completion_tokens":..,"total_tokens":..}`).
     * Lives on the parser because streaming chunks and non-streaming
     * response bodies carry exactly this shape, and both paths in
     * [ChatService] extract it through [fromJson].
     */
    data class Usage(
        val promptTokens: Long,
        val completionTokens: Long,
        val totalTokens: Long,
    ) {
        companion object {
            /**
             * Extract `usage` from a stream chunk or response body JSON.
             * Returns null when usage is absent, JSON-null, or an empty
             * object — "provider didn't report usage" must never surface
             * as an error, so callers simply skip accounting.
             */
            fun fromJson(json: JSONObject): Usage? {
                val usage = json.optJSONObject("usage") ?: return null
                if (!usage.has("prompt_tokens") &&
                    !usage.has("completion_tokens") &&
                    !usage.has("total_tokens")
                ) {
                    return null
                }
                val prompt = usage.optLong("prompt_tokens", 0L)
                val completion = usage.optLong("completion_tokens", 0L)
                // Some compatible endpoints omit total_tokens; deriving it
                // keeps summaries from undercounting instead of showing a
                // misleading 0 total.
                val total = if (usage.has("total_tokens")) {
                    usage.optLong("total_tokens", 0L)
                } else {
                    prompt + completion
                }
                return Usage(prompt, completion, total)
            }
        }
    }

    /**
     * The last usage object seen on this stream, or null when none arrived.
     * Keeping the last occurrence (instead of forwarding every frame's) lets
     * [ChatService] emit exactly one Usage event per request, right before
     * Done — some providers repeat usage on several chunks.
     */
    var lastUsage: Usage? = null
        private set

    /**
     * Pull the next complete data payload out of [source], or return null if
     * the source is exhausted and no more data is coming. The caller should
     * keep calling this until null is returned.
     *
     * Line splitting relies on okio's [BufferedSource.readUtf8Line], which
     * handles both LF and CR LF and never returns a line that straddles a
     * chunk boundary — that was the bug the original `chunk.split("\n")`
     * approach had.
     */
    fun nextDataPayload(source: BufferedSource): String? {
        while (true) {
            // Read one logical line, delimited by LF (SSE spec) or CR LF.
            val line = source.readUtf8Line() ?: return null

            if (line.startsWith("data:")) {
                // SSE allows "data:" with optional leading space. Strip both.
                val payload = line.removePrefix("data:").trimStart()
                if (payload.isEmpty()) continue
                return payload
            }
            // Comments (":..."), event ids, retries: ignore.
        }
    }

    /**
     * Pull the next frame and split it into a content delta plus optional
     * tool-call fragments. Returns null when the stream ended ([DONE] or EOF).
     * Frames without any choices payload yield an empty content delta, so
     * callers can keep pulling. A top-level `usage` object (typically on the
     * final, choices-less chunk) is surfaced on [Frame.usage] and remembered
     * in [lastUsage]; frames without one are completely unaffected.
     */
    fun nextFrame(source: BufferedSource): Frame? {
        while (true) {
            val payload = nextDataPayload(source) ?: return null
            if (payload == "[DONE]") return null

            try {
                val json = JSONObject(payload)
                val usage = Usage.fromJson(json)
                if (usage != null) lastUsage = usage
                val choices = json.optJSONArray("choices") ?: return Frame("", null, usage)
                if (choices.length() == 0) return Frame("", null, usage)

                val first = choices.getJSONObject(0)
                // Streaming frames carry `delta`; some providers send a full
                // `message` object instead — accept both.
                val delta = first.optJSONObject("delta")
                    ?: first.optJSONObject("message")

                if (delta == null) return Frame("", null, usage)

                val content = if (delta.has("content")) {
                    delta.optString("content", "")
                } else {
                    ""
                }
                val toolCalls = delta.optJSONArray("tool_calls")
                return Frame(content, toolCalls, usage)
            } catch (e: JSONException) {
                // Malformed frame — skip and try the next one. The OpenAI
                // protocol rarely sends broken JSON, so this is just a safety
                // net for edge cases like proxies that prepend bytes.
                continue
            }
        }
    }

    /**
     * Convenience: pull a frame and return only its text delta.
     * Returns the delta string, or null if the stream ended.
     */
    fun nextContentDelta(source: BufferedSource): String? = nextFrame(source)?.contentDelta
}
