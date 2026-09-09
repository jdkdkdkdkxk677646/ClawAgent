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
     * @param history conversation so far, in chronological order. The last
     *                entry is expected to be the just-inserted empty assistant
     *                placeholder; it is *not* sent to the server.
     * @param stream  true for SSE, false for single-shot JSON
     */
    fun streamChat(
        endpoint: String,
        apiKey: String,
        model: String,
        history: List<Message>,
        stream: Boolean,
    ): Flow<StreamEvent> = callbackFlow {
        // Drop a trailing empty assistant placeholder that the UI inserts
        // before kicking off the request.
        val trimmedHistory = if (history.lastOrNull()?.let { it.role == "assistant" && it.content.isEmpty() } == true) {
            history.dropLast(1)
        } else {
            history
        }

        val messagesJson = JSONArray()
        trimmedHistory.forEach { msg ->
            messagesJson.put(
                JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                }
            )
        }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messagesJson)
            put("stream", stream)
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
                val text = try { body.string() } catch (e: Exception) {
                    scope.trySend(StreamEvent.Error("读取响应失败:${e.message}", e))
                    scope.close()
                    return@use
                }
                val reply = try {
                    JSONObject(text)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .optString("content", "")
                } catch (e: Exception) {
                    scope.trySend(StreamEvent.Error("解析响应失败:${e.message}", e))
                    scope.close()
                    return@use
                }
                scope.trySend(StreamEvent.Delta(reply))
                scope.trySend(StreamEvent.Done)
                scope.close()
                return@use
            }

            // Streaming: feed chunks through the SSE parser.
            val source = body.source()
            val parser = SseStreamParser()
            try {
                while (!source.exhausted()) {
                    val delta = parser.nextContentDelta(source)
                    if (delta == null) {
                        // EOF or [DONE] — finished.
                        break
                    }
                    if (delta.isNotEmpty()) {
                        scope.trySend(StreamEvent.Delta(delta))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "stream interrupted", e)
                scope.trySend(StreamEvent.Error("流中断:${e.message}", e))
                scope.close()
                return@use
            }
            scope.trySend(StreamEvent.Done)
            scope.close()
        }
    }

    data class Message(val role: String, val content: String)

    sealed class StreamEvent {
        data class Delta(val text: String) : StreamEvent()
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
