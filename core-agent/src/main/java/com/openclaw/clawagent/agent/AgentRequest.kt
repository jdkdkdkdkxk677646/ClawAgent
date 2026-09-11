package com.openclaw.clawagent.agent

/**
 * One agent turn, fully described. The UI layer builds this from settings +
 * user input; [AgentLoop] executes it. Nothing in here touches Android.
 */
data class AgentRequest(
    val endpoint: String,
    val apiKey: String,
    val model: String,
    val history: List<com.openclaw.clawagent.provider.ChatService.Message>,
    val stream: Boolean,
    /** The `tools` array to advertise, or null to disable function calling. */
    val tools: org.json.JSONArray?,
    /** Hard cap on model rounds per turn — the anti-loop guard. */
    val maxRounds: Int,
    /** Registry used to execute model-issued tool calls. */
    val toolset: AgentToolbox,
)
