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
}
