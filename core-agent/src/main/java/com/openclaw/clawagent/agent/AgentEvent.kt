package com.openclaw.clawagent.agent

/**
 * What the agent loop reports back to the UI, in order. Deliberately free of
 * any presentation concern (no bubble formatting here — the loop decides
 * *what* happened, the UI decides *how to show it*):
 *
 * - [Delta]        streamed model text
 * - [ToolCalls]    the model asked for tools (UI can show 🔧 lines)
 * - [ToolResult]   a tool ran; carries full result + a UI-safe preview
 * - [Usage]        provider-reported token usage (ledger already updated)
 * - [RoundLimitReached] the anti-loop guard tripped
 * - [Error]        transport/protocol failure; prior Delta text stays valid
 * - [Done]         the turn is over
 */
sealed class AgentEvent {
    data class Delta(val text: String) : AgentEvent()
    data class ToolCalls(val calls: List<com.openclaw.clawagent.provider.ChatService.ToolCall>) : AgentEvent()
    data class ToolResult(
        val name: String,
        val arguments: String,
        /** Truncated for the chat bubble; the full text always reached the model. */
        val preview: String,
        val fullResult: String,
    ) : AgentEvent()
    data class Usage(
        val promptTokens: Int,
        val completionTokens: Int,
        val totalTokens: Int,
    ) : AgentEvent()
    data class RoundLimitReached(val rounds: Int) : AgentEvent()
    data class Error(val message: String, val cause: Throwable? = null) : AgentEvent()
    data object Done : AgentEvent()
}
