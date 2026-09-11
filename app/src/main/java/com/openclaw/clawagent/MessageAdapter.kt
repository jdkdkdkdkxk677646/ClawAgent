package com.openclaw.clawagent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LineBackgroundSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.openclaw.clawagent.databinding.ItemMessageBinding
import com.openclaw.clawagent.markdown.MarkdownParser
import com.openclaw.clawagent.markdown.Segment

data class ChatMessage(val role: String, var content: String)

/**
 * Renders chat bubbles. Assistant content is parsed by [MarkdownParser] and
 * rendered with spans — the previous Html.fromHtml approach silently dropped
 * all `style=` attributes, so code blocks rendered as plain prose.
 *
 * Text segments keep the span pipeline (one or more TextViews inside the
 * bubble), while table segments render as a real widget table: a
 * HorizontalScrollView wrapping a stock android.widget.TableLayout, so wide
 * tables scroll horizontally inside the bubble and short tables fill the
 * bubble width (fillViewport). item_message.xml stays untouched —
 * onCreateViewHolder wraps the existing messageText into a vertical panel
 * (the padding / layout slot / background role move to the panel), letting
 * text blocks and table widgets stack inside one and the same bubble.
 *
 * The raw (unrendered) text is emitted through [onCopy] on long-press so the
 * clipboard gets exactly what the model wrote. Position is passed back so
 * the Activity can also offer branch-related actions ("fork from here").
 *
 * v4.1: extends [ListAdapter] — diffed submits replace the old full-attach
 * `notifyDataSetChanged` mirror. Items are position-keyed ([ChatMessage] has
 * no identity), and content changes rebind in place, so streaming only
 * rebinds the tail message instead of every visible row. Callers must submit
 * *snapshot copies* — `ChatMessage.content` is mutable and the loop writes it
 * in place, so a submitted item that is still aliased by the live tree would
 * defeat the diff (old list would read the new text).
 */
class MessageAdapter(
    private val onCopy: (String) -> Unit = {},
    private val onMessageLongClick: ((position: Int, message: ChatMessage) -> Unit)? = null,
) : ListAdapter<ChatMessage, MessageAdapter.VH>(DIFF) {

    inner class VH(val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root) {

        /** Vertical bubble panel: text blocks first, table widgets appended below. */
        val bubble: LinearLayout

        /**
         * Position recorded at bind time. [adapterPosition] is -1 until the
         * holder is attached to a RecyclerView (and manual binds in tests
         * never attach), so the long-press handler prefers this.
         */
        var boundPosition: Int = RecyclerView.NO_POSITION

        /** Long-press copies the raw markdown, no matter which part was pressed. */
        val copyLongClick = View.OnLongClickListener {
            val pos = boundPosition.takeIf { it != RecyclerView.NO_POSITION }
                ?: adapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val msg = getItem(pos)
                onMessageLongClick?.invoke(pos, msg)
                onCopy(msg.content)
            }
            true
        }

        init {
            binding.messageText.setOnLongClickListener(copyLongClick)

            val text = binding.messageText
            // item_message.xml 是共享资源、不允许改动：在构造期把 TextView 包进
            // 一个垂直面板，表格 widget 才能和文本块同住一个气泡。面板原样接管
            // TextView 的布局槽位（0dp+weight+marginEnd）、padding 与背景角色，
            // 气泡外观与改造前完全一致。
            val panelSlot = text.layoutParams as LinearLayout.LayoutParams
            val panel = LinearLayout(text.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(text.paddingLeft, text.paddingTop, text.paddingRight, text.paddingBottom)
            }
            text.setPadding(0, 0, 0, 0)
            text.background = null // 气泡底色移到面板上，onBind 时按角色设置
            text.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val slot = text.parent as ViewGroup
            val index = slot.indexOfChild(text)
            slot.removeViewInLayout(text)
            panel.addView(text)
            slot.addView(panel, index, panelSlot)
            bubble = panel
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.boundPosition = position
        val msg = getItem(position)
        val binding = holder.binding
        val context = holder.itemView.context

        binding.avatar.text = if (msg.role == "user") "👤" else "🦀"

        // 气泡底色挂在面板上（文本块与表格共享），文字颜色仍逐块设置。
        val textColor: Int
        if (msg.role == "user") {
            holder.bubble.background = context.getDrawable(R.drawable.msg_user_bg)
            textColor = 0xFFFFFFFF.toInt()
        } else {
            holder.bubble.background = context.getDrawable(R.drawable.msg_assistant_bg)
            textColor = 0xFFE2E8F0.toInt()
        }

        bindContent(holder, msg, textColor)
    }

    // ----- rendering -------------------------------------------------------

    /**
     * Fills the bubble in document order: text segments accumulate into span
     * blocks (same pipeline as the single-TextView era), table segments become
     * widgets inserted between them. Streaming calls notifyItemChanged on the
     * same holder over and over, so rebinding must be idempotent — every
     * dynamic child left by the previous message is dropped first.
     */
    private fun bindContent(holder: VH, msg: ChatMessage, textColor: Int) {
        val context = holder.itemView.context
        val bubble = holder.bubble
        val messageText = holder.binding.messageText

        // 复位：清掉上一条消息遗留的动态块（表格、追加文本段）。
        // index 0 永远是布局提供的 messageText，保留复用。
        for (i in bubble.childCount - 1 downTo 1) bubble.removeViewAt(i)

        if (msg.role == "user") {
            // User messages render verbatim — no markdown surprises for your
            // own words.
            messageText.visibility = View.VISIBLE
            messageText.text = msg.content
            return
        }

        // 文本块：第一块复用布局里的 messageText，表格把内容截断后的后续文本
        // 落到 item_message_text 模板新建的块里。
        var block: TextView? = null
        var firstBlockUsed = false
        val sb = SpannableStringBuilder()

        fun ensureBlock(): TextView {
            block?.let { return it }
            val tv = if (!firstBlockUsed) {
                firstBlockUsed = true
                messageText
            } else {
                val extra = LayoutInflater.from(context)
                    .inflate(R.layout.item_message_text, bubble, false) as TextView
                bubble.addView(extra)
                extra
            }
            tv.visibility = View.VISIBLE
            tv.setTextColor(textColor)
            block = tv
            return tv
        }

        fun flushText() {
            val tv = block ?: return
            block = null
            if (sb.isNotEmpty()) {
                // Make plain URLs tappable. Works on the spannable we just built.
                Linkify.addLinks(sb, Linkify.WEB_URLS)
                tv.text = sb
            } else if (tv !== messageText) {
                bubble.removeView(tv)
            }
            sb.clear()
        }

        MarkdownParser.parse(msg.content).forEach { seg ->
            when (seg) {
                is Segment.Text -> {
                    ensureBlock()
                    sb.append(seg.text)
                }

                is Segment.Bold -> {
                    ensureBlock()
                    val start = sb.length
                    sb.append(seg.text)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                is Segment.InlineCode -> {
                    ensureBlock()
                    val start = sb.length
                    sb.append(seg.text)
                    sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(0xFFFBBF24.toInt()), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(0xFF1E293B.toInt()), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                is Segment.CodeBlock -> {
                    ensureBlock()
                    if (seg.lang.isNotEmpty()) {
                        val ls = sb.length
                        sb.append(seg.lang)
                        sb.append('\n')
                        sb.setSpan(ForegroundColorSpan(0xFF64748B.toInt()), ls, sb.length - 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(StyleSpan(Typeface.ITALIC), ls, sb.length - 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(RelativeSizeSpan(0.85f), ls, sb.length - 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    val start = sb.length
                    sb.append(seg.code)
                    if (seg.code.isNotEmpty() && !seg.code.endsWith("\n")) sb.append('\n')
                    sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(CodeBackgroundSpan(), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(0xFFD6E2F0.toInt()), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                is Segment.Strikethrough -> {
                    ensureBlock()
                    val start = sb.length
                    sb.append(seg.text)
                    sb.setSpan(StrikethroughSpan(), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                is Segment.TaskItem -> {
                    // 勾选框只读，用字符呈现，不接交互。
                    ensureBlock()
                    sb.append(if (seg.checked) "☑ " else "☐ ")
                    sb.append(seg.text)
                    sb.append('\n')
                }

                is Segment.Table -> {
                    flushText()
                    appendTableWidget(context, bubble, seg, holder.copyLongClick)
                }
            }
        }
        flushText()

        // 消息以表格开头（没有任何文本块被用到）时，常驻首块让位隐藏。
        if (!firstBlockUsed) messageText.visibility = View.GONE
    }

    /**
     * Renders a [Segment.Table] as a real widget table: a HorizontalScrollView
     * (horizontal scrolling enabled for this row only, height wraps its
     * content — tall tables grow vertically, there is no inner vertical
     * scroll) wrapping a stock android.widget.TableLayout. Cell text never
     * wraps (the scroll container measures with unbounded width), so overly
     * wide content simply widens the scrollable area. No third-party
     * dependency is involved. Marked internal so the render path is unit
     * testable with Robolectric.
     */
    internal fun appendTableWidget(
        context: Context,
        parent: LinearLayout,
        table: Segment.Table,
        longClick: View.OnLongClickListener?,
    ) {
        // 空表格（无表头且无数据行）没有可渲染内容，直接跳过而不是渲染空壳。
        val colCount = (listOf(table.headers) + table.rows).maxOfOrNull { it.size } ?: 0
        if (colCount == 0) return

        val scroll = LayoutInflater.from(context)
            .inflate(R.layout.item_table, parent, false) as HorizontalScrollView
        longClick?.let { scroll.setOnLongClickListener(it) }
        val tableLayout = scroll.findViewById<TableLayout>(R.id.tableLayout)

        // 只有表头、没有数据行也照常渲染表头（GFM 允许的合法表格）。
        if (table.headers.isNotEmpty()) {
            tableLayout.addView(buildTableRow(context, table.headers, colCount, isHeader = true))
        }
        table.rows.forEach { row ->
            tableLayout.addView(buildTableRow(context, row, colCount, isHeader = false))
        }
        parent.addView(scroll)
    }

    /**
     * One TableRow; ragged rows (shorter or longer than the header) are padded
     * with empty cells up to [colCount], mirroring the old text renderer.
     */
    private fun buildTableRow(
        context: Context,
        cells: List<String>,
        colCount: Int,
        isHeader: Boolean,
    ): TableRow {
        val row = TableRow(context)
        val template = if (isHeader) R.layout.item_table_header_cell else R.layout.item_table_cell
        repeat(colCount) { c ->
            val cell = LayoutInflater.from(context).inflate(template, row, false) as TextView
            cell.text = cells.getOrElse(c) { "" }
            row.addView(cell)
        }
        return row
    }

    /**
     * Paints a soft panel behind every line of a code block. LineBackgroundSpan
     * is invoked per line, and consecutive lines produce a continuous panel.
     */
    private class CodeBackgroundSpan : LineBackgroundSpan {
        override fun drawBackground(
            canvas: Canvas, paint: Paint,
            left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int, lineNumber: Int,
        ) {
            val prev = paint.color
            paint.color = 0xFF111827.toInt()
            canvas.drawRect(left.toFloat() + 2, top + 1f, right.toFloat() - 2, bottom - 1f, paint)
            paint.color = prev
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: List<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            // "text" payload: same row, content changed (streaming tail) — a
            // full rebind of just this holder, skipping rows that didn't move.
            onBindViewHolder(holder, position)
        }
    }

    companion object {
        /**
         * Role-keyed identity: with no stable ids, the diff pairs up messages
         * by role sequence — appends and streaming-tail rebinds (the two hot
         * paths) diff to a single insert/update. Callers submit fresh copies
         * each time (see class doc), so `equals` on the data class is a
         * genuine content comparison.
         */
        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage) =
                oldItem.role == newItem.role

            override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage) =
                oldItem == newItem

            override fun getChangePayload(oldItem: ChatMessage, newItem: ChatMessage) = "text"
        }
    }
}
