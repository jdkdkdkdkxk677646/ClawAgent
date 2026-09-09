package com.openclaw.clawagent

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LineBackgroundSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.ViewGroup
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
 * The raw (unrendered) text is emitted through [onCopy] on long-press so the
 * clipboard gets exactly what the model wrote. Position is passed back so
 * the Activity can also offer branch-related actions ("fork from here").
 */
class MessageAdapter(
    private val messages: List<ChatMessage>,
    private val onCopy: (String) -> Unit = {},
    private val onMessageLongClick: ((position: Int, message: ChatMessage) -> Unit)? = null,
) : RecyclerView.Adapter<MessageAdapter.VH>() {

    inner class VH(val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.messageText.setOnLongClickListener {
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener true
                onMessageLongClick?.invoke(pos, messages[pos])
                onCopy(messages[pos].content)
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = messages[position]
        val binding = holder.binding

        binding.avatar.text = if (msg.role == "user") "👤" else "🦀"

        if (msg.role == "user") {
            binding.messageText.background = holder.itemView.context
                .getDrawable(R.drawable.msg_user_bg)
            binding.messageText.setTextColor(0xFFFFFFFF.toInt())
        } else {
            binding.messageText.background = holder.itemView.context
                .getDrawable(R.drawable.msg_assistant_bg)
            binding.messageText.setTextColor(0xFFE2E8F0.toInt())
        }

        binding.messageText.text = renderContent(msg)
    }

    override fun getItemCount() = messages.size

    // ----- rendering -------------------------------------------------------

    private fun renderContent(msg: ChatMessage): CharSequence {
        // User messages render verbatim — no markdown surprises for your own
        // words. Assistant messages get the full treatment.
        if (msg.role == "user") return msg.content

        val sb = SpannableStringBuilder()
        MarkdownParser.parse(msg.content).forEach { seg ->
            when (seg) {
                is Segment.Text -> sb.append(seg.text)

                is Segment.Bold -> {
                    val start = sb.length
                    sb.append(seg.text)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                is Segment.InlineCode -> {
                    val start = sb.length
                    sb.append(seg.text)
                    sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(0xFFFBBF24.toInt()), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(0xFF1E293B.toInt()), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                is Segment.CodeBlock -> {
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
            }
        }

        // Make plain URLs tappable. Works on the spannable we just built.
        Linkify.addLinks(sb, Linkify.WEB_URLS)
        return sb
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
}
