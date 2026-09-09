package com.openclaw.clawagent.provider

import okio.Buffer
import org.junit.Assert.assertEquals
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
                """data: {"choices":[{"delta":{"content":"ok"}}]}""" + "\n",
            ),
        )
    }

    @Test
    fun `data without leading space is accepted`() {
        // "data:{...}" — no space after the colon (SSE permits both).
        assertEquals(
            "x",
            deltaOf("""data:{"choices":[{"delta":{"content":"x"}}]}""" + "\n"),
        )
    }

    @Test
    fun `CRLF line endings are accepted`() {
        assertEquals(
            "y",
            deltaOf("""data: {"choices":[{"delta":{"content":"y"}}]}""" + "\r\n\r\n"),
        )
    }

    @Test
    fun `sequential frames are consumed one at a time`() {
        val buffer = Buffer()
        buffer.writeUtf8("""data: {"choices":[{"delta":{"content":"a"}}]}""" + "\n")
        buffer.writeUtf8("""data: {"choices":[{"delta":{"content":"b"}}]}""" + "\n")
        buffer.writeUtf8("data: [DONE]\n")

        val parser = SseStreamParser()
        assertEquals("a", parser.nextContentDelta(buffer))
        assertEquals("b", parser.nextContentDelta(buffer))
        assertNull(parser.nextContentDelta(buffer))
    }

    @Test
    fun `non-stream message frames fall back to message content`() {
        // Some providers emit a full message object instead of a delta.
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
}
