package com.openclaw.clawagent.provider

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin network layer over an [OkHttpClient] that talks the OpenAI Chat
 * Completions protocol. Knows nothing about Android UI; the caller decides
 * how to render the streamed text.
 *
 * Agent support: pass [streamChat]'s `tools` (a `tools` JSON array, see
 * [com.openclaw.clawagent.agent.AgentToolbox.requestJson]) and the flow emits
 * [StreamEvent.ToolCalls] when the model requests tool executions. The caller
 * runs the tools and loops with the results appended as `role:"tool"`
 * messages ([Message.toolCallId]).
 */
class ChatService(
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * Send a chat request and stream the assistant reply.
     *
     * @param endpoint fully-qualified chat completions URL
     * @param apiKey  bearer token; pass empty string for providers that don't
     *                require auth (Ollama, Pollinations free tier)
     * @param model   model id
     * @param history conversation so far, in chronological order. May include
     *                a leading system message and, mid-agent-loop, assistant
     *                messages with [Message.toolCalls] plus their matching
     *                `role:"tool"` results.
     * @param stream  true for SSE, false for single-shot JSON
     * @param tools   optional `tools` JSON array to advertise to the model;
     *                null/empty disables function calling entirely
     */
    fun streamChat(
        endpoint: String,
        apiKey: String,
        model: String,
        history: List<Message>,
        stream: Boolean,
        tools: JSONArray? = null,
    ): Flow<StreamEvent> = callbackFlow {
        // Drop a trailing empty assistant placeholder that the UI inserts
        // before kicking off the request.
        val trimmedHistory = if (history.lastOrNull()?.let { it.role == "assistant" && it.content.isEmpty() && it.toolCalls == null } == true) {
            history.dropLast(1)
        } else {
            history
        }

        val messagesJson = JSONArray()
        trimmedHistory.forEach { msg ->
            messagesJson.put(serializeMessage(msg))
        }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messagesJson)
            put("stream", stream)
            if (tools != null && tools.length() > 0) {
                put("tools", tools)
            }
        }

        val request = Request.Builder()
            .url(endpoint)
            .apply {
                if (apiKey.isNotEmpty()) {
                    addHeader("Authorization", "Bearer $apiKey")
                }
                addHeader("Accept", if (stream) "text/event-stream" else "application/json")
            }
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = client.newCall(request)
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(c: okhttp3.Call, e: IOException) {
                trySend(StreamEvent.Error(e.message ?: "网络请求失败", e))
                close(e)
            }

            override fun onResponse(c: okhttp3.Call, response: Response) {
                handleResponse(this@callbackFlow, response, stream)
            }
        })

        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    /** OpenAI wire shape for one conversation message. */
    private fun serializeMessage(msg: Message): JSONObject = JSONObject().apply {
        put("role", msg.role)
        if (msg.role == "tool") {
            // Tool result message: content carries the raw tool output.
            put("content", msg.content)
            msg.toolCallId?.let { put("tool_call_id", it) }
            msg.toolName?.let { put("name", it) }
        } else {
            put("content", msg.content)
            msg.toolCalls?.let { calls ->
                if (calls.isNotEmpty()) {
                    put("tool_calls", JSONArray().apply {
                        calls.forEach { call ->
                            put(JSONObject().apply {
                                put("id", call.id)
                                put("type", "function")
                                put("function", JSONObject().apply {
                                    put("name", call.name)
                                    put("arguments", call.arguments)
                                })
                            })
                        }
                    })
                }
            }
        }
    }

    private fun handleResponse(scope: ProducerScope<StreamEvent>, response: Response, stream: Boolean) {
        response.use { r ->
            if (!r.isSuccessful) {
                val errBody = try { r.body?.string().orEmpty() } catch (_: Exception) { "" }
                val msg = "HTTP ${r.code} ${r.message}" +
                    if (errBody.isNotBlank()) "\n$errBody" else ""
                scope.trySend(StreamEvent.Error(msg))
                scope.close()
                return@use
            }

            val body = r.body
            if (body == null) {
                scope.trySend(StreamEvent.Error("空响应体"))
                scope.close()
                return@use
            }

            if (!stream) {
                handleNonStreamBody(scope, body)
                return@use
            }

            // Streaming: feed chunks through the SSE parser.
            val source = body.source()
            val parser = SseStreamParser()
            val toolAccumulator = ToolCallAccumulator()
            try {
                while (!source.exhausted()) {
                    val frame = parser.nextFrame(source)
                        // EOF or [DONE] — finished.
                        ?: break
                    if (frame.contentDelta.isNotEmpty()) {
                        scope.trySend(StreamEvent.Delta(frame.contentDelta))
                    }
                    frame.toolCallFragments?.let { toolAccumulator.feed(it) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "stream interrupted", e)
                scope.trySend(StreamEvent.Error("流中断:${e.message}", e))
                scope.close()
                return@use
            }
            if (toolAccumulator.hasCalls) {
                scope.trySend(StreamEvent.ToolCalls(toolAccumulator.toCalls()))
            }
            scope.trySend(StreamEvent.Done)
            scope.close()
        }
    }

    private fun handleNonStreamBody(scope: ProducerScope<StreamEvent>, body: okhttp3.ResponseBody) {
        val text = try { body.string() } catch (e: Exception) {
            scope.trySend(StreamEvent.Error("读取响应失败:${e.message}", e))
            scope.close()
            return
        }
        val message = try {
            JSONObject(text)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
        } catch (e: Exception) {
            scope.trySend(StreamEvent.Error("解析响应失败:${e.message}", e))
            scope.close()
            return
        }

        scope.trySend(StreamEvent.Delta(message.optString("content", "")))

        // Function calling: the reply may (only) ask for tool executions.
        val toolCallsJson = message.optJSONArray("tool_calls")
        if (toolCallsJson != null && toolCallsJson.length() > 0) {
            val accumulator = ToolCallAccumulator()
            accumulator.feed(toolCallsJson)
            if (accumulator.hasCalls) {
                scope.trySend(StreamEvent.ToolCalls(accumulator.toCalls()))
            }
        }

        scope.trySend(StreamEvent.Done)
        scope.close()
    }

    /** One function call the model asks the app to execute. */
    data class ToolCall(
        val id: String,
        val name: String,
        /** JSON arguments string as produced by the model. */
        val arguments: String,
    )

    /**
     * A conversation message. Plain turns only use [role]/[content]; agent
     * turns additionally carry either [toolCalls] (assistant asking for tool
     * executions) or [toolCallId]/[toolName] (`role:"tool"` results).
     */
    data class Message(
        val role: String,
        val content: String,
        val toolCalls: List<ToolCall>? = null,
        val toolCallId: String? = null,
        val toolName: String? = null,
    )

    sealed class StreamEvent {
        data class Delta(val text: String) : StreamEvent()
        data class ToolCalls(val calls: List<ToolCall>) : StreamEvent()
        data class Error(val message: String, val cause: Throwable? = null) : StreamEvent()
        data object Done : StreamEvent()
    }

    companion object {
        private const val TAG = "ChatService"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // No read timeout: stream chunks can be sparse on slow networks.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
