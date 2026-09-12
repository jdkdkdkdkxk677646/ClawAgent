package com.openclaw.clawagent

import android.content.Context
import android.graphics.Typeface
import android.os.Looper
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.openclaw.clawagent.markdown.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Unit tests for the table rendering path of [MessageAdapter].
 *
 * Robolectric inflates the real layouts on the JVM, so we can assert the view
 * tree the adapter builds (row/cell structure, block stacking, holder reuse)
 * without an emulator. MarkdownParser itself is covered by MarkdownParserTest;
 * here we only assert what the adapter makes of its output.
 *
 * v4.1: the adapter is a ListAdapter — [bind] pumps the main looper so the
 * async diff finishes before binding, mirroring what RecyclerView sees.
 */
@RunWith(RobolectricTestRunner::class)
class MessageAdapterTableRenderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Inflate + bind one message the same way RecyclerView would. */
    private fun bind(msg: ChatMessage): MessageAdapter.VH {
        val adapter = MessageAdapter()
        adapter.submitList(listOf(msg))
        shadowOf(Looper.getMainLooper()).idle()
        val vh = adapter.onCreateViewHolder(LinearLayout(context), 0)
        adapter.onBindViewHolder(vh, 0)
        return vh
    }

    /** Locate the TableLayout inside a bubble's HorizontalScrollView. */
    private fun tableOf(vh: MessageAdapter.VH): TableLayout {
        val scroll = vh.bubble.getChildAt(1) as HorizontalScrollView
        return scroll.findViewById(R.id.tableLayout)
    }

    private fun cellText(row: TableRow, column: Int): String =
        (row.getChildAt(column) as TextView).text.toString()

    // ── 结构：表格 ↔ 真实视图 ─────────────────────────────────────

    @Test
    fun `table message renders scroll view with header and data rows`() {
        val vh = bind(ChatMessage("assistant", "| A | B |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |"))

        // 表格开头、无前置文本：常驻首块隐藏，气泡 = 隐藏的 messageText + 表格
        assertEquals(2, vh.bubble.childCount)
        assertEquals(View.GONE, vh.binding.messageText.visibility)

        val table = tableOf(vh)
        assertEquals(3, table.childCount) // 表头 + 2 数据行

        val header = table.getChildAt(0) as TableRow
        assertEquals(2, header.childCount)
        assertEquals("A", cellText(header, 0))
        assertEquals("B", cellText(header, 1))

        val row0 = table.getChildAt(1) as TableRow
        assertEquals("1", cellText(row0, 0))
        assertEquals("2", cellText(row0, 1))

        val row1 = table.getChildAt(2) as TableRow
        assertEquals("3", cellText(row1, 0))
        assertEquals("4", cellText(row1, 1))
    }

    @Test
    fun `text before and after table stacks in document order`() {
        val vh = bind(
            ChatMessage("assistant", "intro\n\n| A |\n| --- |\n| 1 |\n\noutro")
        )

        assertEquals(3, vh.bubble.childCount)
        val first = vh.bubble.getChildAt(0) as TextView
        val scroll = vh.bubble.getChildAt(1) as HorizontalScrollView
        val last = vh.bubble.getChildAt(2) as TextView

        assertTrue(first.text.toString().startsWith("intro"))
        assertEquals(1, scroll.findViewById<TableLayout>(R.id.tableLayout).childCount - 1) // 数据行数
        assertTrue(last.text.toString().endsWith("outro"))
        assertEquals(View.VISIBLE, vh.binding.messageText.visibility)
    }

    @Test
    fun `multiple tables in one message each get their own scroll view`() {
        val vh = bind(
            ChatMessage(
                "assistant",
                "| A |\n| --- |\n| 1 |\n\nmiddle\n\n| B |\n| --- |\n| 2 |",
            )
        )
        val scrolls = (0 until vh.bubble.childCount)
            .map { vh.bubble.getChildAt(it) }
            .filterIsInstance<HorizontalScrollView>()
        assertEquals(2, scrolls.size)
    }

    @Test
    fun `user message renders verbatim without table widgets`() {
        val raw = "| A | B |\n| --- | --- |\n| 1 | 2 |"
        val vh = bind(ChatMessage("user", raw))

        assertEquals(1, vh.bubble.childCount) // 只有 messageText，无表格
        assertEquals(View.VISIBLE, vh.binding.messageText.visibility)
        assertEquals(raw, vh.binding.messageText.text.toString())
    }

    @Test
    fun `code fence with pipes is not rendered as table`() {
        val vh = bind(ChatMessage("assistant", "```\n| A |\n| --- |\n| 1 |\n```"))

        assertEquals(1, vh.bubble.childCount)
        assertEquals(View.VISIBLE, vh.binding.messageText.visibility)
    }

    // ── 边界：空表 / 只有表头 / 长短不齐 / 长文本 ─────────────────

    @Test
    fun `header only table renders single row`() {
        val vh = bind(ChatMessage("assistant", "| H1 | H2 |\n| --- | --- |"))

        val table = tableOf(vh)
        assertEquals(1, table.childCount)
        assertEquals(2, (table.getChildAt(0) as TableRow).childCount)
    }

    @Test
    fun `ragged rows are padded to column count`() {
        val vh = bind(
            ChatMessage("assistant", "| A | B | C |\n| --- | --- | --- |\n| only |")
        )

        val row = (tableOf(vh).getChildAt(1) as TableRow)
        assertEquals(3, row.childCount)
        assertEquals("only", cellText(row, 0))
        assertEquals("", cellText(row, 1))
        assertEquals("", cellText(row, 2))
    }

    @Test
    fun `empty table widget is skipped without crash`() {
        val adapter = MessageAdapter()
        val parent = LinearLayout(context)
        parent.addView(TextView(context))

        adapter.appendTableWidget(context, parent, Segment.Table(emptyList(), emptyList()), null)

        assertEquals(1, parent.childCount) // 没有追加任何视图
    }

    @Test
    fun `very long cell text builds without crash and keeps full content`() {
        val longCell = "超长单元格内容不会换行而是撑宽横向滚动".repeat(50)
        val vh = bind(ChatMessage("assistant", "| A |\n| --- |\n| $longCell |"))

        val row = (tableOf(vh).getChildAt(1) as TableRow)
        assertEquals(longCell, cellText(row, 0))
    }

    // ── 样式：表头加粗 + 底色，分隔线，padding ────────────────────

    @Test
    fun `header cells are bold with header background`() {
        val vh = bind(ChatMessage("assistant", "| A | B |\n| --- | --- |\n| 1 | 2 |"))

        val headerCell = ((tableOf(vh).getChildAt(0) as TableRow).getChildAt(0)) as TextView
        val bodyCell = ((tableOf(vh).getChildAt(1) as TableRow).getChildAt(0)) as TextView

        assertTrue(
            "表头应加粗（typeface 或 fake bold 之一）",
            headerCell.typeface.style == Typeface.BOLD || headerCell.paint.isFakeBoldText,
        )
        assertTrue("表头应有底色 drawable", headerCell.background != null)
        assertTrue("数据行不应有底色", bodyCell.background == null)
    }

    @Test
    fun `table layout uses divider drawable between rows`() {
        val vh = bind(ChatMessage("assistant", "| A |\n| --- |\n| 1 |\n| 2 |"))

        val table = tableOf(vh)
        assertTrue("行间分隔线应启用", (table.showDividers and LinearLayout.SHOW_DIVIDER_MIDDLE) != 0)
    }

    // ── 复用：RecyclerView holder 反复重绑必须幂等 ────────────────

    @Test
    fun `rebinding a table holder to plain text drops the old widget`() {
        val adapter = MessageAdapter()
        adapter.submitList(
            listOf(
                ChatMessage("assistant", "| A |\n| --- |\n| 1 |"),
                ChatMessage("assistant", "just text"),
            )
        )
        shadowOf(Looper.getMainLooper()).idle()
        val vh = adapter.onCreateViewHolder(LinearLayout(context), 0)

        adapter.onBindViewHolder(vh, 0)
        assertEquals(2, vh.bubble.childCount) // 隐藏的 messageText + 表格

        adapter.onBindViewHolder(vh, 1)
        assertEquals(1, vh.bubble.childCount) // 动态块已清空
        assertEquals("just text", vh.binding.messageText.text.toString())
        assertEquals(View.VISIBLE, vh.binding.messageText.visibility)
    }

    @Test
    fun `rebinding a plain text holder back to a table rebuilds the widget`() {
        val adapter = MessageAdapter()
        adapter.submitList(
            listOf(
                ChatMessage("assistant", "just text"),
                ChatMessage("assistant", "| A |\n| --- |\n| 1 |"),
            )
        )
        shadowOf(Looper.getMainLooper()).idle()
        val vh = adapter.onCreateViewHolder(LinearLayout(context), 0)

        adapter.onBindViewHolder(vh, 0)
        assertEquals(1, vh.bubble.childCount)

        adapter.onBindViewHolder(vh, 1)
        assertEquals(2, vh.bubble.childCount)
        assertEquals(View.GONE, vh.binding.messageText.visibility)
        assertEquals(2, tableOf(vh).childCount) // 表头 + 1 数据行
    }

    // ── 交互：长按表格也能复制原始 markdown ──────────────────────

    @Test
    fun `long press on table copies raw markdown`() {
        val raw = "| A |\n| --- |\n| 1 |"
        var copied: String? = null
        var clickedPosition: Int? = null
        val adapter = MessageAdapter(
            onCopy = { copied = it },
            onMessageLongClick = { pos, _ -> clickedPosition = pos },
        )
        adapter.submitList(listOf(ChatMessage("assistant", raw)))
        shadowOf(Looper.getMainLooper()).idle()
        val vh = adapter.onCreateViewHolder(LinearLayout(context), 0)
        adapter.onBindViewHolder(vh, 0)

        val scroll = vh.bubble.getChildAt(1) as HorizontalScrollView
        assertTrue(scroll.isLongClickable)
        scroll.performLongClick()

        assertEquals(raw, copied)
        assertEquals(0, clickedPosition)
    }

    // ----- T-201: parse-cache -------------------------------------------------

    /**
     * Counts each call to the injected parser. Tests use this to assert that
     * the adapter only invokes the parser when the content key changes (or
     * the cache evicts the entry).
     */
    private class CountingParser : (String) -> List<Segment> {
        var calls: Int = 0
        override fun invoke(content: String): List<Segment> {
            calls++
            // Body parser: keeps the same observable output as the real one
            // for plain text — sufficient for the cache test, which only
            // cares about *whether* the parser runs, not what's inside.
            return listOf(Segment.Text(content))
        }
    }

    @Test
    fun `same content rebound yields a single parse call`() {
        val parser = CountingParser()
        val adapter = MessageAdapter(parser = parser, parseCacheSize = 64)
        val msg = ChatMessage("assistant", "hello world")
        adapter.submitList(listOf(msg))
        shadowOf(Looper.getMainLooper()).idle()
        val vh = adapter.onCreateViewHolder(LinearLayout(context), 0)

        adapter.onBindViewHolder(vh, 0)
        adapter.onBindViewHolder(vh, 0) // streaming tail — same content
        adapter.onBindViewHolder(vh, 0) // ditto

        assertEquals("expected parser to run once for repeated binds of the same content", 1, parser.calls)
    }

    @Test
    fun `content change triggers exactly one new parse`() {
        val parser = CountingParser()
        val adapter = MessageAdapter(parser = parser, parseCacheSize = 64)

        // First bind: content A.
        adapter.submitList(listOf(ChatMessage("assistant", "alpha")))
        shadowOf(Looper.getMainLooper()).idle()
        val vh = adapter.onCreateViewHolder(LinearLayout(context), 0)
        adapter.onBindViewHolder(vh, 0)
        assertEquals(1, parser.calls)

        // Second bind: same content — cache hit.
        adapter.onBindViewHolder(vh, 0)
        assertEquals(1, parser.calls)

        // Third bind: mutated to content B — one new parse.
        // The streaming scenario is approximated by submitting a new list
        // with changed content and rebinding; the adapter can't mutate an
        // already-submitted snapshot in place because DiffUtil does its
        // own list handling.
        adapter.onBindViewHolder(vh, 0) // unchanged; still cache hit
        adapter.submitList(listOf(ChatMessage("assistant", "beta")))
        shadowOf(Looper.getMainLooper()).idle()
        adapter.onBindViewHolder(vh, 0)
        assertEquals("content change should add exactly one parse", 2, parser.calls)
    }

    @Test
    fun `lru evicts oldest entry when capacity is exceeded`() {
        val parser = CountingParser()
        // Tiny cap of 2 so we can exercise eviction without sending 64 messages.
        val adapter = MessageAdapter(parser = parser, parseCacheSize = 2)

        val msgs = listOf(
            ChatMessage("assistant", "first"),
            ChatMessage("assistant", "second"),
            ChatMessage("assistant", "third"),
        )
        adapter.submitList(msgs)
        shadowOf(Looper.getMainLooper()).idle()
        val vh = adapter.onCreateViewHolder(LinearLayout(context), 0)
        // Bind all three: each one is a fresh content key, so the parser
        // should run three times. After the third bind, the cache should
        // hold "second" and "third" (accessOrder bumps "second" to MRU
        // when "third" arrives and the eldest "first" drops out).
        adapter.onBindViewHolder(vh, 0)
        adapter.onBindViewHolder(vh, 1)
        adapter.onBindViewHolder(vh, 2)
        assertEquals(3, parser.calls)

        // Re-binding index 0 ("first") is now a cache miss because the
        // entry was evicted, so the parser must run once more.
        adapter.onBindViewHolder(vh, 0)
        assertEquals(4, parser.calls)
    }

    @Test
    fun `user messages bypass the parser entirely`() {
        // User roles render verbatim — they should never invoke the parser,
        // so they shouldn't even be cached.
        val parser = CountingParser()
        val adapter = MessageAdapter(parser = parser, parseCacheSize = 64)
        adapter.submitList(listOf(ChatMessage("user", "raw user text")))
        shadowOf(Looper.getMainLooper()).idle()
        val vh = adapter.onCreateViewHolder(LinearLayout(context), 0)
        adapter.onBindViewHolder(vh, 0)
        adapter.onBindViewHolder(vh, 0)
        assertEquals("user messages must not call the parser", 0, parser.calls)
    }
}
