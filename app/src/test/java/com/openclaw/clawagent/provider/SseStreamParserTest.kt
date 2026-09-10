package com.openclaw.clawagent.provider

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests. The Android stub org.json throws at runtime, so a real
 * org.json is wired in via testImplementation — see app/build.gradle.kts.
 */
class SseStreamParserTest {

    private fun deltaOf(vararg frames: String): String? {
        val buffer = Buffer()
        frames.forEach { buffer.writeUtf8(it) }
        return SseStreamParser().nextContentDelta(buffer)
    }

    @Test
    fun `basic delta frame`() {
        assertEquals(
            "hi",
            deltaOf("""data: {"choices":[{"delta":{"content":"hi"}}]}""" + "\n\n"),
        )
    }

    @Test
    fun `done sentinel terminates stream`() {
        assertNull(deltaOf("data: [DONE]\n\n"))
    }

    @Test
    fun `exhausted source returns null`() {
        assertNull(deltaOf(""))
    }

    @Test
    fun `comments and heartbeats are ignored`() {
        assertEquals(
            "ok",
            deltaOf(
                ": keep-alive\n\n",
                "event: message\n",
                """data: {"choices":[{"delta":{"content":"ok"}}]}""" + "\n\n",
            ),
        )
    }

    @Test
    fun `data without leading space is accepted`() {
        assertEquals(
            "n",
            deltaOf("""data:{"choices":[{"delta":{"content":"n"}}]}""" + "\n"),
        )
    }

    @Test
    fun `CRLF line endings are accepted`() {
        assertEquals(
            "w",
            deltaOf("""data: {"choices":[{"delta":{"content":"w"}}]}""" + "\r\n\r\n"),
        )
    }

    @Test
    fun `sequential frames are consumed one at a time`() {
        val buffer = Buffer()
        buffer.writeUtf8("""data: {"choices":[{"delta":{"content":"a"}}]}""" + "\n\n")
        buffer.writeUtf8("""data: {"choices":[{"delta":{"content":"b"}}]}""" + "\n\n")
        val parser = SseStreamParser()
        assertEquals("a", parser.nextContentDelta(buffer))
        assertEquals("b", parser.nextContentDelta(buffer))
        assertNull(parser.nextContentDelta(buffer))
    }

    @Test
    fun `non-stream message frames fall back to message content`() {
        assertEquals(
            "full",
            deltaOf("""data: {"choices":[{"message":{"content":"full"}}]}""" + "\n"),
        )
    }

    @Test
    fun `empty choices array yields empty delta`() {
        // e.g. the final usage-only frame some providers send.
        assertEquals("", deltaOf("""data: {"choices":[]}""" + "\n"))
    }

    @Test
    fun `missing choices yields empty delta`() {
        assertEquals("", deltaOf("""data: {"error": null}""" + "\n"))
    }

    @Test
    fun `malformed JSON is skipped and next valid frame returned`() {
        val buffer = Buffer()
        buffer.writeUtf8("data: {broken json\n")
        buffer.writeUtf8("""data: {"choices":[{"delta":{"content":"good"}}]}""" + "\n")
        assertEquals("good", SseStreamParser().nextContentDelta(buffer))
    }

    @Test
    fun `multi-byte UTF-8 deltas survive`() {
        assertEquals(
            "你好🦀",
            deltaOf("""data: {"choices":[{"delta":{"content":"你好🦀"}}]}""" + "\n"),
        )
    }

    @Test
    fun `tool call frames expose their fragments`() {
        val buffer = Buffer()
        buffer.writeUtf8(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"current_time","arguments":""}}]}}]}""" +
                "\n\n"
        )
        val frame = SseStreamParser().nextFrame(buffer)
        assertNotNull(frame)
        assertEquals("", frame!!.contentDelta)
        val fragments = frame.toolCallFragments
        assertNotNull(fragments)
        assertEquals(1, fragments!!.length())
        assertEquals("call_1", fragments.getJSONObject(0).optString("id"))
    }

    @Test
    fun `text and tool call fragments can share one frame`() {
        val buffer = Buffer()
        buffer.writeUtf8(
            """data: {"choices":[{"delta":{"content":"hm","tool_calls":[{"index":0,"function":{"arguments":"{}"}}]}}]}""" +
                "\n\n"
        )
        val frame = SseStreamParser().nextFrame(buffer)
        assertNotNull(frame)
        assertEquals("hm", frame!!.contentDelta)
        assertEquals(1, frame.toolCallFragments!!.length())
    }

    // ── usage extraction ─────────────────────────────────────────

    @Test
    fun `usage chunk exposes usage on the frame and as lastUsage`() {
        // OpenAI streaming puts usage on a final, choices-less chunk.
        val buffer = Buffer()
        buffer.writeUtf8(
            """data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}""" + "\n\n"
        )
        val parser = SseStreamParser()
        val frame = parser.nextFrame(buffer)
        assertNotNull(frame)
        assertEquals(SseStreamParser.Usage(10, 20, 30), frame!!.usage)
        assertEquals(SseStreamParser.Usage(10, 20, 30), parser.lastUsage)
    }

    @Test
    fun `frames without usage keep it null and lastUsage stays null`() {
        val buffer = Buffer()
        buffer.writeUtf8("""data: {"choices":[{"delta":{"content":"hi"}}]}""" + "\n\n")
        val parser = SseStreamParser()
        val frame = parser.nextFrame(buffer)
        assertNotNull(frame)
        assertNull(frame!!.usage)
        assertNull(parser.lastUsage)
    }

    @Test
    fun `usage after content chunks is remembered as lastUsage`() {
        val buffer = Buffer()
        buffer.writeUtf8("""data: {"choices":[{"delta":{"content":"a"}}]}""" + "\n\n")
        buffer.writeUtf8("""data: {"choices":[{"delta":{}}]}""" + "\n\n")
        buffer.writeUtf8(
            """data: {"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}""" + "\n\n"
        )
        buffer.writeUtf8("data: [DONE]\n\n")
        val parser = SseStreamParser()
        while (parser.nextFrame(buffer) != null) {
            // Drain the whole stream.
        }
        assertEquals(SseStreamParser.Usage(1, 2, 3), parser.lastUsage)
    }

    @Test
    fun `several usage frames keep the final occurrence`() {
        val buffer = Buffer()
        buffer.writeUtf8(
            """data: {"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""" + "\n\n"
        )
        buffer.writeUtf8(
            """data: {"choices":[],"usage":{"prompt_tokens":7,"completion_tokens":8,"total_tokens":15}}""" + "\n\n"
        )
        val parser = SseStreamParser()
        while (parser.nextFrame(buffer) != null) {
            // Drain.
        }
        assertEquals(SseStreamParser.Usage(7, 8, 15), parser.lastUsage)
    }

    @Test
    fun `usage missing total_tokens is derived from the parts`() {
        val buffer = Buffer()
        buffer.writeUtf8(
            """data: {"choices":[],"usage":{"prompt_tokens":5,"completion_tokens":7}}""" + "\n\n"
        )
        val frame = SseStreamParser().nextFrame(buffer)
        assertEquals(SseStreamParser.Usage(5, 7, 12), frame!!.usage)
    }

    @Test
    fun `empty or null usage object counts as absent`() {
        val parser = SseStreamParser()
        val buffer = Buffer()
        buffer.writeUtf8("""data: {"choices":[],"usage":{}}""" + "\n\n")
        buffer.writeUtf8("""data: {"choices":[],"usage":null}""" + "\n\n")
        assertNull(parser.nextFrame(buffer)!!.usage)
        assertNull(parser.nextFrame(buffer)!!.usage)
        assertNull(parser.lastUsage)
    }

    @Test
    fun `fromJson reads top-level usage of a non-stream response`() {
        val json = org.json.JSONObject(
            """{"id":"x","choices":[{"message":{"content":"hi"}}],"usage":""" +
                """{"prompt_tokens":11,"completion_tokens":22,"total_tokens":33}}"""
        )
        assertEquals(SseStreamParser.Usage(11, 22, 33), SseStreamParser.Usage.fromJson(json))
    }

    @Test
    fun `fromJson returns null when usage is absent`() {
        val json = org.json.JSONObject("""{"choices":[{"message":{"content":"hi"}}]}""")
        assertNull(SseStreamParser.Usage.fromJson(json))
    }
}
