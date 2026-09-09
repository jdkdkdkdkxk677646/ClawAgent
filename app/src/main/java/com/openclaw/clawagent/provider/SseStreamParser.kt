package com.openclaw.clawagent.provider

import okio.Buffer
import okio.BufferedSource
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
 *   data: [DONE]
 *
 * Non-`data:` lines (event ids, comments, heartbeats) are ignored, which is
 * fine for OpenAI-compatible APIs.
 */
class SseStreamParser {

    /** Carry-over for lines that didn't end in a newline at the previous read. */
    private val pending = StringBuilder()

    /**
     * Pull the next complete data payload out of [source], or return null if
     * the source is exhausted and no more data is coming. The caller should
     * keep calling this until null is returned.
     */
    fun nextDataPayload(source: BufferedSource): String? {
        while (true) {
            // Read one logical line, delimited by LF (SSE spec) or CR LF.
            // okio's readUtf8Line handles both, including EOF.
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
     * Convenience: pull a payload and parse the `choices[0].delta.content` delta.
     * Returns the delta string, or null if the stream ended or this frame
     * carried no content.
     */
    fun nextContentDelta(source: BufferedSource): String? {
        while (true) {
            val payload = nextDataPayload(source) ?: return null
            if (payload == "[DONE]") return null

            val delta = try {
                val json = JSONObject(payload)
                val choices = json.optJSONArray("choices") ?: return ""
                if (choices.length() == 0) return ""
                val first = choices.getJSONObject(0)
                first.optJSONObject("delta")?.optString("content", "")
                    ?: first.optJSONObject("message")?.optString("content", "")
                    ?: ""
            } catch (e: JSONException) {
                // Malformed frame — skip and try the next one. The OpenAI
                // protocol rarely sends broken JSON, so this is just a safety
                // net for edge cases like proxies that prepend bytes.
                continue
            }
            return delta
        }
    }

    /** Reset state. Useful if you want to reuse one parser across requests. */
    fun reset() {
        pending.setLength(0)
    }
}
