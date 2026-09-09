package com.openclaw.clawagent

import android.text.Html
import android.text.Spanned
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.openclaw.clawagent.databinding.ItemMessageBinding

data class ChatMessage(val role: String, var content: String)

class MessageAdapter(private val messages: List<ChatMessage>) :
    RecyclerView.Adapter<MessageAdapter.VH>() {

    inner class VH(val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root)

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

        // Simple markdown rendering
        val rendered = renderMarkdown(msg.content)

        if (msg.role == "user") {
            binding.messageText.background = holder.itemView.context
                .getDrawable(R.drawable.msg_user_bg)
            binding.messageText.setTextColor(0xFFFFFFFF.toInt())
        } else {
            binding.messageText.background = holder.itemView.context
                .getDrawable(R.drawable.msg_assistant_bg)
            binding.messageText.setTextColor(0xFFE2E8F0.toInt())
        }

        binding.messageText.text = rendered
    }

    override fun getItemCount() = messages.size

    private fun renderMarkdown(text: String): Spanned {
        var html = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        // Code blocks
        html = html.replace(Regex("```(\\w*)\\n([\\s\\S]*?)```")) {
            "<font color='#a78bfa'><b>${it.groupValues[1]}</b></font><br>" +
            "<pre style='background:#111827;padding:8px;border-radius:6px;font-size:11px;overflow-x:auto;'>" +
            "${it.groupValues[2]}</pre>"
        }

        // Inline code
        html = html.replace(Regex("`([^`]+)`")) {
            "<font color='#fbbf24'><code style='background:#111827;padding:1px 4px;border-radius:3px;'>" +
            "${it.groupValues[1]}</code></font>"
        }

        // Bold
        html = html.replace(Regex("\\*\\*(.+?)\\*\\*")) {
            "<b>${it.groupValues[1]}</b>"
        }

        // Line breaks
        html = html.replace("\n", "<br>")

        return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
    }
}
