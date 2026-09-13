package com.openclaw.clawagent.agent

import com.openclaw.clawagent.provider.ChatService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * The agent loop, extracted from the Activity during the v4.0 surgery and
 * living in pure-JVM `:core-agent` — which means the whole ReAct cycle
 * (stream → tool calls → execute → feed back → repeat) is unit-testable
 * against a fake [ChatTransport], something that was impossible while it
 * lived inside MainActivity.
 *
 * The loop owns exactly the orchestration policy:
 *  - up to [AgentRequest.maxRounds] model rounds per turn (anti-loop guard);
 *  - tool results go back as `role:"tool"` messages and the round repeats;
 *  - a turn ends on a model answer without tool calls, on [AgentEvent.Error],
 *    or when the round limit trips;
 *  - tool failures degrade into error strings (tools never throw);
 *  - long results reach the model in full, the UI only gets a preview.
 */
class AgentLoop(private val transport: com.openclaw.clawagent.provider.ChatTransport) {

    /** Bubble-safe preview length; the model always sees the full result. */
    var resultPreviewChars: Int = 300

    fun run(request: AgentRequest): Flow<AgentEvent> = flow {
        val conversation = request.history.toMutableList()
        var round = 0

        while (true) {
            round++
            var roundContent = ""
            var requestedCalls: List<ChatService.ToolCall>? = null
            var failed = false

            transport.streamChat(
                endpoint = request.endpoint,
                apiKey = request.apiKey,
                model = request.model,
                history = conversation,
                stream = request.stream,
                tools = request.tools,
            ).collect { event ->
                when (event) {
                    is ChatService.StreamEvent.Delta -> {
                        roundContent += event.text
                        emit(AgentEvent.Delta(event.text))
                    }
                    is ChatService.StreamEvent.ToolCalls -> {
                        requestedCalls = event.calls
                        emit(AgentEvent.ToolCalls(event.calls))
                    }
                    is ChatService.StreamEvent.Usage -> emit(
                        AgentEvent.Usage(event.promptTokens, event.completionTokens, event.totalTokens)
                    )
                    is ChatService.StreamEvent.Error -> {
                        // Keep whatever already streamed — replacing it would
                        // throw away tokens the user paid for.
                        failed = true
                        emit(AgentEvent.Error(event.message, event.cause))
                    }
                    ChatService.StreamEvent.Done -> Unit
                }
            }

            if (failed) return@flow
            val calls = requestedCalls?.takeIf { it.isNotEmpty() }
                ?: ToolCallParser.parse(roundContent)
                    // Fallback for providers that print the call as JSON text
                    // instead of using the native tool_calls channel. Only calls
                    // naming a *registered* tool are honoured, so prose that
                    // merely discusses JSON can never trigger execution.
                    .filter { request.toolset.names.contains(it.name) }
                    .map { ChatService.ToolCall(it.id, it.name, it.argumentsJson()) }
                    .ifEmpty { null }
            if (calls == null) {
                emit(AgentEvent.Done)
                return@flow
            }

            if (round >= request.maxRounds) {
                emit(AgentEvent.RoundLimitReached(round))
                return@flow
            }

            // Record the tool-call request, then execute each tool locally.
            conversation += ChatService.Message(
                role = "assistant",
                content = roundContent,
                toolCalls = calls,
            )
            for (call in calls) {
                val result = runCatching { request.toolset.execute(call.name, call.arguments) }
                    .getOrElse { "工具执行失败:${it.message}" }
                emit(AgentEvent.ToolResult(call.name, call.arguments, previewOf(result), result))
                conversation += ChatService.Message(
                    role = "tool",
                    content = result,
                    toolCallId = call.id,
                    toolName = call.name,
                )
            }
        }
    }.flowOn(Dispatchers.Default)

    private fun previewOf(result: String): String =
        if (result.length <= resultPreviewChars) {
            result
        } else {
            result.take(resultPreviewChars) +
                "…(共 ${result.length} 字符,已完整提供给模型)"
        }
}
