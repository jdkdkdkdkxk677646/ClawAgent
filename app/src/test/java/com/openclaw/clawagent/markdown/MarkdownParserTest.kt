package com.openclaw.clawagent.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownParserTest {

    private fun parse(text: String) = MarkdownParser.parse(text)

    @Test
    fun `empty input yields no segments`() {
        assertEquals(emptyList<Segment>(), parse(""))
    }

    @Test
    fun `plain text passes through`() {
        assertEquals(listOf(Segment.Text("hello world")), parse("hello world"))
    }

    @Test
    fun `bold is extracted`() {
        assertEquals(
            listOf(Segment.Text("a "), Segment.Bold("b"), Segment.Text(" c")),
            parse("a **b** c"),
        )
    }

    @Test
    fun `inline code is extracted`() {
        assertEquals(
            listOf(Segment.Text("run "), Segment.InlineCode("npm install"), Segment.Text(" now")),
            parse("run `npm install` now"),
        )
    }

    @Test
    fun `code block with language`() {
        assertEquals(
            listOf(Segment.CodeBlock("python", "print(1)\n")),
            parse("```python\nprint(1)\n```"),
        )
    }

    @Test
    fun `code block without language`() {
        assertEquals(
            listOf(Segment.CodeBlock("", "x = 1\n")),
            parse("```\nx = 1\n```"),
        )
    }

    @Test
    fun `prose before and after code block`() {
        assertEquals(
            listOf(
                Segment.Text("look:\n\n"),
                Segment.CodeBlock("js", "let a = 1\n"),
                Segment.Text("done"),
            ),
            parse("look:\n\n```js\nlet a = 1\n```\ndone"),
        )
    }

    @Test
    fun `unterminated fence renders rest as code - streaming case`() {
        // While streaming, the closing fence hasn't arrived yet.
        assertEquals(
            listOf(Segment.CodeBlock("python", "def quick_sort(")),
            parse("```python\ndef quick_sort("),
        )
    }

    @Test
    fun `bold inside inline code stays literal`() {
        assertEquals(
            listOf(Segment.InlineCode("**not bold**")),
            parse("`**not bold**`"),
        )
    }

    @Test
    fun `backticks inside bold stay literal`() {
        assertEquals(
            listOf(Segment.Bold("code `x` here")),
            parse("**code `x` here**"),
        )
    }

    @Test
    fun `multiple code blocks alternate with prose`() {
        assertEquals(
            listOf(
                Segment.CodeBlock("a", "1\n"),
                Segment.Text("mid\n"), // trailing \n kept: code block starts on its own line
                Segment.CodeBlock("b", "2\n"),
            ),
            parse("```a\n1\n```\nmid\n```b\n2\n```"),
        )
    }

    @Test
    fun `CRLF is normalized before parsing`() {
        assertEquals(
            listOf(Segment.CodeBlock("py", "x = 1\n")),
            parse("```py\r\nx = 1\r\n```"),
        )
    }

    @Test
    fun `adjacent bold and code are both parsed`() {
        assertEquals(
            listOf(
                Segment.Bold("bold"),
                Segment.Text(" and "),
                Segment.InlineCode("code"),
            ),
            parse("**bold** and `code`"),
        )
    }

    @Test
    fun `fence language tag is trimmed`() {
        assertEquals(
            listOf(Segment.CodeBlock("kotlin", "1\n")),
            parse("```kotlin   \n1\n```"),
        )
    }

    // ----- T-103: 表格 -----

    @Test
    fun `table with header separator and data rows`() {
        assertEquals(
            listOf(
                Segment.Table(
                    headers = listOf("A", "B"),
                    rows = listOf(listOf("1", "2"), listOf("3", "4")),
                ),
            ),
            parse("| A | B |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |"),
        )
    }

    @Test
    fun `table without leading or trailing pipes`() {
        assertEquals(
            listOf(
                Segment.Table(
                    headers = listOf("Name", "Age"),
                    rows = listOf(listOf("Ada", "36")),
                ),
            ),
            parse("Name | Age\n--- | ---\nAda | 36"),
        )
    }

    @Test
    fun `table with escaped pipe inside cell`() {
        assertEquals(
            listOf(
                Segment.Table(
                    headers = listOf("X", "Y"),
                    rows = listOf(listOf("a|b", "c")),
                ),
            ),
            parse("| X | Y |\n| --- | --- |\n| a\\|b | c |"),
        )
    }

    @Test
    fun `table is not parsed without separator row`() {
        // 只有含 | 的行、没有分隔行 —— 应保持普通文本。
        assertEquals(
            listOf(Segment.Text("A | B\nC | D")),
            parse("A | B\nC | D"),
        )
    }

    // ----- T-103: 删除线 -----

    @Test
    fun `strikethrough is extracted`() {
        assertEquals(
            listOf(Segment.Text("a "), Segment.Strikethrough("gone"), Segment.Text(" b")),
            parse("a ~~gone~~ b"),
        )
    }

    @Test
    fun `strikethrough inside inline code stays literal`() {
        assertEquals(
            listOf(Segment.InlineCode("~~not strike~~")),
            parse("`~~not strike~~`"),
        )
    }

    @Test
    fun `strikethrough inside code block stays literal`() {
        assertEquals(
            listOf(Segment.CodeBlock("", "~~x~~\n")),
            parse("```\n~~x~~\n```"),
        )
    }

    // ----- T-103: 任务列表 -----

    @Test
    fun `task list unchecked and checked items`() {
        assertEquals(
            listOf(
                Segment.TaskItem(checked = false, text = "todo"),
                Segment.TaskItem(checked = true, text = "done"),
            ),
            parse("- [ ] todo\n- [x] done"),
        )
    }

    @Test
    fun `task list uppercase X is checked`() {
        assertEquals(
            listOf(Segment.TaskItem(checked = true, text = "done")),
            parse("- [X] done"),
        )
    }

    @Test
    fun `plain list without checkbox is not a task item`() {
        assertEquals(
            listOf(Segment.Text("- just a list\n- another item")),
            parse("- just a list\n- another item"),
        )
    }

    @Test
    fun `task item followed by prose keeps both`() {
        assertEquals(
            listOf(
                Segment.TaskItem(checked = false, text = "todo"),
                Segment.Text("then prose"),
            ),
            parse("- [ ] todo\nthen prose"),
        )
    }
}
