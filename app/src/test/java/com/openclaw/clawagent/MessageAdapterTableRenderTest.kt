package com.openclaw.clawagent

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.test.core.app.ApplicationProvider
import com.openclaw.clawagent.markdown.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Unit tests for [MessageAdapter]'s table rendering path.
 *
 * T-302:表格已从原生 `TableLayout` 换成 Compose 原生渲染(承载在
 * [ComposeView] 里)。因此断言从"TableLayout 行/单元格结构"下调为"气泡面板里
 * 出现一个 ComposeView"这一结构级契约——表格内部的 Compose 布局不在 Robolectric
 * 的 View 树里,不做像素级断言;MarkdownParser 的解析仍由 MarkdownParserTest 覆盖。
 *
 * Robolectric inflates the real layouts on the JVM, so we can build the view tree
 * the adapter produces without an emulator. The adapter is a ListAdapter — [bind]
 * pumps the main looper so the async diff finishes before binding.
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

    /** 气泡面板里承载表格的 ComposeView(T-302 起表格改为 Compose 原生)。 */
    private fun tableViews(vh: MessageAdapter.VH): List<ComposeView> =
        (0 until vh.bubble.childCount)
            .map { vh.bubble.getChildAt(it) }
            .filterIsInstance<ComposeView>()

    // ── 结构:表格 ↔ ComposeView ───────────────────────────────────

    @Test
    fun `table message renders a ComposeView and hides the text block`() {
        val vh = bind(ChatMessage("assistant", "| A | B |\n| --- | --- |\n| 1 | 2 |"))

        // 表格开头、无前置文本:常驻首块隐藏,气泡 = 隐藏的 messageText + 表格 ComposeView
        assertEquals(2, vh.bubble.childCount)
        assertEquals(View.GONE, vh.binding.messageText.visibility)
        assertEquals(1, tableViews(vh).size)
    }

    @Test
    fun `text before and after table stacks in document order`() {
        val vh = bind(
            ChatMessage("assistant", "intro\n\n| A |\n| --- |\n| 1 |\n\noutro")
        )

        assertEquals(3, vh.bubble.childCount)
        val first = vh.bubble.getChildAt(0) as TextView
        val middle = vh.bubble.getChildAt(1)
        val last = vh.bubble.getChildAt(2) as TextView

        assertTrue(first.text.toString().startsWith("intro"))
        assertTrue("表格位置应是 ComposeView", middle is ComposeView)
        assertTrue(last.text.toString().endsWith("outro"))
        assertEquals(View.VISIBLE, vh.binding.messageText.visibility)
    }

    @Test
    fun `multiple tables in one message each get their own ComposeView`() {
        val vh = bind(
            ChatMessage(
                "assistant",
                "| A |\n| --- |\n| 1 |\n\nmiddle\n\n| B |\n| --- |\n| 2 |",
            )
        )
        assertEquals(2, tableViews(vh).size)
    }

    @Test
    fun `user message renders verbatim without table widgets`() {
        val raw = "| A | B |\n| --- | --- |\n| 1 | 2 |"
        val vh = bind(ChatMessage("user", raw))

        assertEquals(1, vh.bubble.childCount) // 只有 messageText，无表格
        assertEquals(View.VISIBLE, vh.binding.messageText.visibility)
        assertEquals(raw, vh.binding.messageText.text.toString())
        assertTrue(tableViews(vh).isEmpty())
    }

    @Test
    fun `code fence with pipes is not rendered as table`() {
        val vh = bind(ChatMessage("assistant", "```\n| A |\n| --- |\n| 1 |\n```"))

        assertEquals(1, vh.bubble.childCount)
        assertEquals(View.VISIBLE, vh.binding.messageText.visibility)
        assertTrue(tableViews(vh).isEmpty())
    }

    // ── 边界:空表 / 只有表头 / 长文本 ───────────────────────────

    @Test
    fun `header only table still renders a ComposeView`() {
        val vh = bind(ChatMessage("assistant", "| H1 | H2 |\n| --- | --- |"))
        assertEquals(1, tableViews(vh).size)
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
    fun `very long cell text builds without crash`() {
        val longCell = "超长单元格内容不会换行而是撑宽横向滚动".repeat(50)
        val vh = bind(ChatMessage("assistant", "| A |\n| --- |\n| $longCell |"))

        assertEquals(1, tableViews(vh).size)
    }

    // ── 复用:RecyclerView holder 反复重绑必须幂等 ────────────────

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
        assertEquals(1, tableViews(vh).size)
    }

    // ── 交互:长按表格也能复制原始 markdown ──────────────────────

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

        val table = tableViews(vh).single()
        assertTrue(table.isLongClickable)
        table.performLongClick()

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

        val msg = ChatMessage("assistant", "")
        adapter.submitList(listOf(msg))
        shadowOf(Looper.getMainLooper()).idle()
        val vh = adapter.onCreateViewHolder(LinearLayout(context), 0)

        msg.content = "al"
        adapter.onBindViewHolder(vh, 0)
        assertEquals("first chunk should parse", 1, parser.calls)

        msg.content = "alph"
        adapter.onBindViewHolder(vh, 0)
        assertEquals("second chunk should add exactly one parse", 2, parser.calls)

        msg.content = "alpha"
        adapter.onBindViewHolder(vh, 0)
        assertEquals("third chunk should add exactly one parse", 3, parser.calls)

        // Revisiting an old content key is a cache hit.
        msg.content = "alph"
        adapter.onBindViewHolder(vh, 0)
        assertEquals("revisiting an old key is a cache hit", 3, parser.calls)
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
