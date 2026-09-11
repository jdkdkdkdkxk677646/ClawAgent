package com.openclaw.clawagent.provider

import kotlinx.coroutines.flow.Flow
import org.json.JSONArray

/**
 * The one thing the agent loop needs from the network layer, expressed as an
 * interface so [com.openclaw.clawagent.agent.AgentLoop] can be unit-tested
 * against a fake transport: no OkHttp, no sockets, deterministic events.
 *
 * Production implementation is [ChatService]; the contract is the OpenAI
 * Chat Completions protocol with optional function-calling tools.
 */
interface ChatTransport {
    fun stream(
        endpoint: String,
        apiKey: String,
        model: String,
        history: List<ChatService.Message>,
        stream: Boolean,
        tools: JSONArray?,
    ): Flow<StreamEvent>
}
