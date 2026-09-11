package com.openclaw.clawagent.mcp

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal MCP (Model Context Protocol) client over the Streamable HTTP
 * transport (spec 2025-11-25), living in pure-JVM `:core-agent` next to the
 * OpenAI transport.
 *
 * What it does — the three calls this app needs and nothing more:
 *  1. [connect]     → `initialize` + `notifications/initialized` handshake;
 *  2. [listTools]   → `tools/list` (follows `nextCursor` pagination);
 *  3. [callTool]    → `tools/call`, flattening `content[].text` into one
 *     string. Tool failures become readable error strings, never exceptions
 *     (same never-throw contract as the built-in claws).
 *
 * Wire format per spec: every POST carries `Accept: application/json,
 * text/event-stream`; the server may answer either with a single JSON body
 * or an SSE stream whose `data:` lines carry the JSON-RPC reply — both are
 * handled here. Session affinity rides the `MCP-Session-Id` response header
 * when the server assigns one, and `MCP-Protocol-Version` is echoed on every
 * request after the handshake.
 *
 * Deliberately out of scope for v4.2: resources/prompts/sampling, the GET
 * server-initiated stream, stdio transport (no subprocesses on Android).
 */
class McpClient(
    private val endpoint: String,
    private val authToken: String? = null,
    private val clientName: String = "ClawAgent",
    private val clientVersion: String = "1.0",
    private val protocolVersion: String = PROTOCOL_VERSION,
    connectTimeoutMs: Long = 10_000,
    callTimeoutMs: Long = 60_000,
    private val log: (String) -> Unit = {},
    /** Test seam: inject a prebuilt client (interceptor-based fake server). */
    httpClient: OkHttpClient? = null,
) {
    private val client: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private val nextId = AtomicLong(1)

    /** Server-assigned session, null when the server is stateless. */
    var sessionId: String? = null
        private set

    /** The protocol version the server agreed to (may differ from ours). */
    var negotiatedVersion: String = protocolVersion
        private set

    /** Human-readable server identity from the handshake, e.g. "GitHub MCP 1.2". */
    var serverInfo: String = ""
        private set

    // ── handshake ──────────────────────────────────────────────────

    /**
     * Runs the initialize handshake. Throws [McpException] on any failure —
     * connect happens explicitly at startup where the caller wants to know.
     */
    fun connect() {
        val params = JSONObject()
            .put("protocolVersion", protocolVersion)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", clientName).put("version", clientVersion))

        val reply = request("initialize", params, expectReply = true)
        if (reply.has("error")) throw McpException("initialize 被拒绝:${reply.optJSONObject("error")}")
        val result = reply.optJSONObject("result")
            ?: throw McpException("initialize 响应缺少 result")

        negotiatedVersion = result.optString("protocolVersion", protocolVersion)
        val info = result.optJSONObject("serverInfo")
        serverInfo = info?.let { "${it.optString("name")}${it.optString("version")?.let { v -> " $v" } ?: ""}" } ?: ""

        // The notification has no id and expects no body back (202).
        notify("notifications/initialized")
        log("MCP connected: $serverInfo (protocol $negotiatedVersion, session ${sessionId ?: "stateless"})")
    }

    // ── tools ──────────────────────────────────────────────────────

    /** All tools the server exposes, following `nextCursor` pagination. */
    fun listTools(): List<McpToolDef> {
        val tools = ArrayList<McpToolDef>()
        var cursor: String? = null
        do {
            val params = JSONObject()
            if (cursor != null) params.put("cursor", cursor)
            val reply = request("tools/list", params, expectReply = true)
            if (reply.has("error")) throw McpException("tools/list 失败:${reply.optJSONObject("error")}")
            val result = reply.optJSONObject("result") ?: break
            val arr = result.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                tools += McpToolDef(
                    name = t.getString("name"),
                    title = t.optString("title", "").ifEmpty { null },
                    description = t.optString("description", ""),
                    inputSchemaJson = t.optJSONObject("inputSchema")?.toString()
                        ?: """{"type":"object","properties":{}}""",
                )
            }
            cursor = result.optString("nextCursor", "").ifEmpty { null }
        } while (cursor != null)
        return tools
    }

    /**
     * Executes a remote tool. Full result text is returned to the model;
     * tool-level failures (isError=true) come back as readable strings and
     * transport/protocol failures degrade into error strings as well — a
     * broken MCP server must never crash the agent loop.
     */
    fun callTool(name: String, argumentsJson: String): String {
        val args = try {
            if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)
        } catch (_: Exception) {
            // The model emitted non-JSON arguments; pass them through as a
            // single "input" so the remote tool can still see the intent.
            JSONObject().put("input", argumentsJson)
        }
        val params = JSONObject()
            .put("name", name)
            .put("arguments", args)

        return try {
            val reply = request("tools/call", params, expectReply = true)
            if (reply.has("error")) {
                return "MCP 工具错误:${reply.optJSONObject("error")?.optString("message") ?: reply.toString()}"
            }
            val result = reply.optJSONObject("result")
                ?: return "MCP 工具返回缺少 result"
            if (result.optBoolean("isError", false)) {
                "MCP 工具报告执行失败:${flattenContent(result)}"
            } else {
                flattenContent(result).ifEmpty { "(工具执行成功,无文本输出)" }
            }
        } catch (e: Exception) {
            "MCP 工具调用失败:${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Explicit session teardown per spec (server may answer 405 — fine). */
    fun close() {
        val id = sessionId ?: return
        try {
            val req = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", negotiatedVersion)
                .applyAuth()
                .header("MCP-Session-Id", id)
                .delete()
                .build()
            client.newCall(req).execute().use { /* 200 or 405, either is fine */ }
        } catch (_: Exception) {
        } finally {
            sessionId = null
        }
    }

    // ── wire ───────────────────────────────────────────────────────

    private fun notify(method: String) {
        request(method, null, expectReply = false)
    }

    /**
     * One Streamable-HTTP round trip. When [expectReply], waits for the
     * JSON-RPC response — whether the server answered with plain JSON or an
     * SSE stream. When !expectReply (notifications), a 2xx is success.
     */
    private fun request(method: String, params: JSONObject?, expectReply: Boolean): JSONObject {
        val payload = JSONObject().put("jsonrpc", "2.0").put("method", method)
        if (params != null) payload.put("params", params)
        if (expectReply) payload.put("id", nextId.getAndIncrement())

        val req = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", negotiatedVersion)
            .applyAuth()
            .apply { sessionId?.let { header("MCP-Session-Id", it) } }
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw McpException("HTTP ${resp.code} $method:${body.take(200)}")
            }
            if (!expectReply) return JSONObject() // 202 Accepted, nothing to parse

            resp.header("MCP-Session-Id")?.let { sessionId = it }

            val contentType = resp.header("Content-Type").orEmpty()
            val json = if (contentType.contains("text/event-stream")) {
                parseSseReply(body, method)
                    ?: throw McpException("SSE 流中没有 $method 的响应")
            } else {
                JSONObject(body)
            }
            return json
        }
    }

    /**
     * Pulls the first JSON-RPC response object out of an SSE body. SSE lines
     * look like `event: message` / `data: {...}`; the spec puts the JSON-RPC
     * message in `data:` (possibly across multiple data lines).
     */
    private fun parseSseReply(body: String, method: String): JSONObject? {
        val dataLines = ArrayList<String>()
        for (raw in body.lineSequence()) {
            when {
                raw.startsWith("data:") -> dataLines += raw.removePrefix("data:").trim()
                raw.isEmpty() && dataLines.isNotEmpty() -> {
                    // event boundary: try to parse what we gathered so far
                    parseReplyChunk(dataLines.joinToString("\n"))?.let { return it }
                    dataLines.clear()
                }
            }
        }
        // stream ended without a blank-line boundary — try the tail anyway
        if (dataLines.isNotEmpty()) return parseReplyChunk(dataLines.joinToString("\n"))
        log("MCP SSE body for $method carried no response")
        return null
    }

    private fun parseReplyChunk(text: String): JSONObject? = try {
        val json = JSONObject(text)
        if (json.has("result") || json.has("error")) json else null
    } catch (_: Exception) {
        null
    }

    private fun flattenContent(result: JSONObject): String {
        val content = result.optJSONArray("content") ?: return result.toString()
        return buildString {
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                when (block.optString("type")) {
                    "text" -> append(block.optString("text"))
                    "resource" -> append(block.optJSONObject("resource")?.optString("text") ?: "")
                    "image", "audio" -> append("(二进制内容已省略)")
                    else -> append(block.toString())
                }
                if (i < content.length() - 1) append("\n")
            }
        }
    }

    private fun Request.Builder.applyAuth(): Request.Builder =
        authToken?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } ?: this

    companion object {
        const val PROTOCOL_VERSION = "2025-11-25"
    }
}

/** One tool advertised by an MCP server. */
data class McpToolDef(
    val name: String,
    val title: String?,
    val description: String,
    /** JSON Schema (MCP `inputSchema`) passed through verbatim. */
    val inputSchemaJson: String,
)

class McpException(message: String) : Exception(message)
