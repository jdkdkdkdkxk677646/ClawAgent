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
 *  - ~~strikethrough~~
 *  - GFM tables (header row + `| --- |` separator row + data rows)
 *  - task list items (`- [ ]` / `- [x]`)
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

    /** ~~strikethrough~~ */
    data class Strikethrough(val text: String) : Segment()

    /** Task list item: `- [ ]` / `- [x]`. [checked] is the box state. */
    data class TaskItem(val checked: Boolean, val text: String) : Segment()

    /** GFM table. [headers] is the header row; [rows] are the data rows. */
    data class Table(val headers: List<String>, val rows: List<List<String>>) : Segment()
}

object MarkdownParser {

    private const val FENCE = "```"

    // Inline elements are scanned left-to-right so inline-code content is never
    // nested-parsed (`~~x~~` inside backticks stays literal). Order matters:
    // inline code wins over bold wins over strikethrough.
    private val INLINE = Regex("`([^`\\n]+)`|\\*\\*(.+?)\\*\\*|~~(.+?)~~")

    // Task list items must start at column 0 (`- [ ]` / `- [x]`, any of - * +).
    private val TASK_ITEM = Regex("^[-*+]\\s+\\[([ xX])\\]\\s+(.*)$")

    // A table separator row cell is `---`, `:---`, `---:` or `:---:`.
    private val SEPARATOR_CELL = Regex("^:?-+:?$")

    fun parse(raw: String): List<Segment> {
        if (raw.isEmpty()) return emptyList()
        val text = raw.replace("\r\n", "\n")

        val segments = mutableListOf<Segment>()
        var i = 0

        // Phase 1: split into code blocks and prose runs on ``` fences.
        while (i < text.length) {
            val fenceAt = text.indexOf(FENCE, i)
            if (fenceAt < 0) {
                emitBlocks(segments, text.substring(i))
                break
            }
            // Prose before the fence.
            if (fenceAt > i) emitBlocks(segments, text.substring(i, fenceAt))

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

    /**
     * Phase 2 (block level): scan a prose run line-by-line, extracting block
     * structures — tables and task list items — and passing everything else
     * through to [emitInline] for the inline pass. Line breaks inside ordinary
     * prose are preserved verbatim.
     */
    private fun emitBlocks(sink: MutableList<Segment>, prose: String) {
        if (prose.isEmpty()) return

        val buffer = StringBuilder()

        fun flush() {
            if (buffer.isNotEmpty()) emitInline(sink, buffer.toString())
            buffer.setLength(0)
        }

        var i = 0
        while (i < prose.length) {
            val line = readLine(prose, i)
            val content = line.content

            val task = TASK_ITEM.matchEntire(content)
            if (task != null) {
                flush()
                val checked = task.groupValues[1].lowercase() == "x"
                sink.add(Segment.TaskItem(checked, task.groupValues[2]))
                i = line.nextIndex
                continue
            }

            if (isTableStartAt(prose, i)) {
                flush()
                val parsed = parseTableAt(prose, i)
                sink.add(parsed.table)
                i = parsed.nextIndex
                continue
            }

            buffer.append(line.contentWithNewline)
            i = line.nextIndex
        }
        flush()
    }

    /** Phase 3 (inline level): plain text / bold / inline code / strikethrough. */
    private fun emitInline(sink: MutableList<Segment>, prose: String) {
        if (prose.isEmpty()) return

        var last = 0
        for (m in INLINE.findAll(prose)) {
            if (m.range.first > last) sink.add(Segment.Text(prose.substring(last, m.range.first)))
            val code = m.groupValues[1]
            val bold = m.groupValues[2]
            val strike = m.groupValues[3]
            when {
                code.isNotEmpty() -> sink.add(Segment.InlineCode(code))
                bold.isNotEmpty() -> sink.add(Segment.Bold(bold))
                else -> sink.add(Segment.Strikethrough(strike))
            }
            last = m.range.last + 1
        }
        if (last < prose.length) sink.add(Segment.Text(prose.substring(last)))
    }

    // ----- line / table helpers -------------------------------------------

    /** A single line: content without its newline, content with it, next index. */
    private data class Line(val content: String, val contentWithNewline: String, val nextIndex: Int)

    private fun readLine(text: String, start: Int): Line {
        val nl = text.indexOf('\n', start)
        val end = if (nl < 0) text.length else nl
        val next = if (nl < 0) text.length else nl + 1
        return Line(text.substring(start, end), text.substring(start, next), next)
    }

    private fun isTableStartAt(text: String, lineStart: Int): Boolean {
        val headerLine = readLine(text, lineStart)
        if (!headerLine.content.contains('|')) return false
        val separatorLine = readLine(text, headerLine.nextIndex)
        return isSeparatorRow(splitTableRow(separatorLine.content))
    }

    private data class ParsedTable(val table: Segment.Table, val nextIndex: Int)

    private fun parseTableAt(text: String, start: Int): ParsedTable {
        val headerLine = readLine(text, start)
        val headers = splitTableRow(headerLine.content)
        // Skip header + separator rows; both were validated by isTableStartAt.
        var i = readLine(text, headerLine.nextIndex).nextIndex
        val rows = mutableListOf<List<String>>()
        while (i < text.length) {
            val line = readLine(text, i)
            if (line.content.isBlank()) break
            if (!line.content.contains('|')) break
            val cells = splitTableRow(line.content)
            if (isSeparatorRow(cells)) break
            rows.add(cells)
            i = line.nextIndex
        }
        return ParsedTable(Segment.Table(headers, rows), i)
    }

    private fun isSeparatorRow(cells: List<String>): Boolean =
        cells.isNotEmpty() && cells.all { SEPARATOR_CELL.matches(it) }

    /**
     * Splits a table row on unescaped pipes. A `\|` is a literal pipe inside a
     * cell, not a column separator. The GFM convention's optional leading /
     * trailing pipes (`| a | b |`) are trimmed first.
     */
    private fun splitTableRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.substring(0, s.length - 1)
        val cells = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            when {
                s[i] == '\\' && i + 1 < s.length && s[i + 1] == '|' -> {
                    sb.append('|')
                    i += 2
                }
                s[i] == '|' -> {
                    cells.add(sb.toString().trim())
                    sb.setLength(0)
                    i++
                }
                else -> {
                    sb.append(s[i])
                    i++
                }
            }
        }
        cells.add(sb.toString().trim())
        return cells
    }
}
