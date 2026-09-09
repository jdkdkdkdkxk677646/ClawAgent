package com.openclaw.clawagent.provider

import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallAccumulatorTest {

    private fun frameOf(source: BufferedSource): SseStreamParser.Frame =
        SseStreamParser().nextFrame(source)!!

    @Test
    fun `single complete tool call frame`() {
        val acc = ToolCallAccumulator()
        val buffer = Buffer()
        // Real SSE frames are single-line JSON — one line per data: event.
        buffer.writeUtf8(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"calculator","arguments":"{\"expression\":\"1+1\"}"}}]}}]}""" + "\n"
        )
        acc.feed(frameOf(buffer).toolCallFragments!!)
        val calls = acc.toCalls()
        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("calculator", calls[0].name)
        assertEquals("""{"expression":"1+1"}""", calls[0].arguments)
    }

    @Test
    fun `arguments fragmented across frames are joined`() {
        val acc = ToolCallAccumulator()
        val frames = listOf(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_9","type":"function","function":{"name":"calculator","arguments":"{\"expr"}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ession\":\"2*"}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"3}"}}]}}]}""",
        )
        frames.forEach { raw ->
            val buffer = Buffer()
            buffer.writeUtf8(raw + "\n")
            acc.feed(frameOf(buffer).toolCallFragments!!)
        }
        val calls = acc.toCalls()
        assertEquals(1, calls.size)
        assertEquals("call_9", calls[0].id)
        assertEquals("calculator", calls[0].name)
        assertEquals("""{"expression":"2*3}""", calls[0].arguments)
    }

    @Test
    fun `parallel calls are kept in index order`() {
        val acc = ToolCallAccumulator()
        val buffer = Buffer()
        // Two calls in one frame; fragments arrive out of id order (1 then 0).
        buffer.writeUtf8(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_b","type":"function","function":{"name":"current_time","arguments":"{}"}},{"index":0,"id":"call_a","type":"function","function":{"name":"calculator","arguments":"{}"}}]}}]}""" + "\n"
        )
        acc.feed(frameOf(buffer).toolCallFragments!!)
        val calls = acc.toCalls()
        assertEquals(listOf("call_a", "call_b"), calls.map { it.id })
        assertEquals(listOf("calculator", "current_time"), calls.map { it.name })
    }

    @Test
    fun `nameless fragments are dropped`() {
        val acc = ToolCallAccumulator()
        val buffer = Buffer()
        buffer.writeUtf8(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{}"}}]}}]}""" + "\n"
        )
        acc.feed(frameOf(buffer).toolCallFragments!!)
        assertTrue(acc.toCalls().isEmpty())
    }

    @Test
    fun `content frames carry text and no tool calls`() {
        val buffer = Buffer()
        buffer.writeUtf8("""data: {"choices":[{"delta":{"content":"hi"}}]}""" + "\n")
        val frame = frameOf(buffer)
        assertEquals("hi", frame.contentDelta)
        assertNull(frame.toolCallFragments)
    }

    @Test
    fun `parser exposes tool fragments without content`() {
        val buffer = Buffer()
        buffer.writeUtf8(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"x","type":"function","function":{"name":"t","arguments":""}}]}}]}""" + "\n"
        )
        val frame = frameOf(buffer)
        assertEquals("", frame.contentDelta)
        assertTrue(frame.toolCallFragments != null && frame.toolCallFragments.length() == 1)
    }

    @Test
    fun `done still terminates stream`() {
        val buffer = Buffer()
        buffer.writeUtf8("data: [DONE]\n\n")
        assertNull(SseStreamParser().nextFrame(buffer))
    }
}
