package com.openclaw.clawagent.markdown

/**
 * Minimal markdown tokenizer for chat bubbles.
 *
 * Pure Kotlin, no Android dependencies, so it is unit-testable on the JVM.
 * [MessageAdapter] turns the emitted [Segment]s into spans.
 *
 * Supported syntax (what LLMs actually emit in chat):
 *  - fenced code blocks ```lang ... ``` (closing fence optional — during
 *    streaming the fence often hasn't arrived yet, so an unterminated fence
 *    renders everything after it as code)
 *  - inline `code`
 *  - **bold**
 *
 * Everything else is plain text, rendered as-is.
 */
sealed class Segment {
    /** Plain, unstyled text. */
    data class Text(val text: String) : Segment()

    /** **bold** */
    data class Bold(val text: String) : Segment()

    /** `inline code` */
    data class InlineCode(val text: String) : Segment()

    /** ```lang ... ``` block. [lang] may be empty. */
    data class CodeBlock(val lang: String, val code: String) : Segment()
}

object MarkdownParser {

    private const val FENCE = "```"

    fun parse(raw: String): List<Segment> {
        if (raw.isEmpty()) return emptyList()
        val text = raw.replace("\r\n", "\n")

        val segments = mutableListOf<Segment>()
        var i = 0

        // Phase 1: split into code blocks and prose runs on ``` fences.
        while (i < text.length) {
            val fenceAt = text.indexOf(FENCE, i)
            if (fenceAt < 0) {
                emitProse(segments, text.substring(i))
                break
            }
            // Prose before the fence.
            if (fenceAt > i) emitProse(segments, text.substring(i, fenceAt))

            // Opening fence: optional language tag up to end of line.
            var lineEnd = text.indexOf('\n', fenceAt + FENCE.length)
            if (lineEnd < 0) lineEnd = text.length
            val lang = text.substring(fenceAt + FENCE.length, lineEnd).trim()

            // Code runs until the next fence or EOF (streaming-safe).
            val codeStart = if (lineEnd < text.length) lineEnd + 1 else lineEnd
            val close = text.indexOf(FENCE, codeStart)
            val code: String
            if (close < 0) {
                code = text.substring(codeStart)
                i = text.length
            } else {
                code = text.substring(codeStart, close)
                // Skip past the closing fence; a trailing newline after it
                // belongs to the separator, not the next prose run.
                var after = close + FENCE.length
                if (after < text.length && text[after] == '\n') after++
                i = after
            }
            segments.add(Segment.CodeBlock(lang, code))
        }

        return segments
    }

    /** Phase 2: split a prose run into plain text / bold / inline code. */
    private fun emitProse(sink: MutableList<Segment>, prose: String) {
        if (prose.isEmpty()) return

        // Scan left to right so inline-code content is never nested-parsed
        // (e.g. `**not bold**` stays literal inside backticks).
        val combined = Regex("`([^`\n]+)`|\\*\\*(.+?)\\*\\*")
        var last = 0
        for (m in combined.findAll(prose)) {
            if (m.range.first > last) sink.add(Segment.Text(prose.substring(last, m.range.first)))
            val code = m.groupValues[1]
            if (code.isNotEmpty()) {
                sink.add(Segment.InlineCode(code))
            } else {
                sink.add(Segment.Bold(m.groupValues[2]))
            }
            last = m.range.last + 1
        }
        if (last < prose.length) sink.add(Segment.Text(prose.substring(last)))
    }
}
