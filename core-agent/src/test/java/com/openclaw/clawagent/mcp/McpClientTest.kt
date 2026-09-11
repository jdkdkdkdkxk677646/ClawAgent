package com.openclaw.clawagent.mcp

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol tests for [McpClient], over an interceptor that stands in for the
 * network (no socket, no mockwebserver dependency — same pattern as
 * ChatServiceTest). Routes on the JSON-RPC `method` field and pins the
 * Streamable-HTTP contract: Accept headers, session affinity, protocol
 * version echo, JSON *and* SSE answer shapes, pagination, error flattening.
 */
class McpClientTest {

    /**
     * Simulated MCP server: method → fully-built canned Response. The
     * handler builds the whole Response (status line included) — the
     * interceptor adds nothing afterwards.
     */
    private class FakeMcpServer(
        private val handler: (method: String, body: JSONObject, request: Request) -> Response,
    ) : Interceptor {
        val seenRequests = mutableListOf<Request>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val json = okioBody(request)
            seenRequests += request
            return handler(json.getString("method"), json, request)
        }
    }

    private fun client(server: FakeMcpServer, authToken: String? = null): McpClient {
        val http = OkHttpClient.Builder().addInterceptor(server).build()
        return McpClient("https://mcp.example/mcp", authToken = authToken, httpClient = http)
    }

    // ── handshake ──────────────────────────────────────────────────

    @Test
    fun `handshake assigns session, negotiates version, sends initialized`() {
        val server = FakeMcpServer { method, body, request ->
            when (method) {
                "initialize" -> {
                    assertEquals(
                        "2025-11-25",
                        body.getJSONObject("params").getString("protocolVersion"),
                    )
                    assertEquals(
                        "ClawAgent",
                        body.getJSONObject("params").getJSONObject("clientInfo").getString("name"),
                    )
                    jsonReply(request, JSONObject().put("result", JSONObject()
                        .put("protocolVersion", "2025-06-18")
                        .put("serverInfo", JSONObject().put("name", "TestServer").put("version", "1.2"))
                    ), session = "sess-42")
                }
                "notifications/initialized" -> plainAccepted(request)
                else -> error("unexpected $method")
            }
        }
        val c = client(server)
        c.connect()
        assertEquals("sess-42", c.sessionId)
        assertEquals("2025-06-18", c.negotiatedVersion)
        assertTrue(c.serverInfo.contains("TestServer"))

        // The initialized notification must have gone out (3 requests total).
        assertEquals(listOf("initialize", "notifications/initialized"),
            server.seenRequests.map { okioBody(it).getString("method") })

        // Session id + negotiated version ride every later request.
        assertEquals("sess-42", server.seenRequests.last().header("MCP-Session-Id"))
        assertEquals("2025-06-18", server.seenRequests.last().header("MCP-Protocol-Version"))
        // Accept header per Streamable HTTP spec.
        assertTrue(server.seenRequests.first().header("Accept")!!.contains("text/event-stream"))
        c.close()
    }

    @Test
    fun `bearer token rides every request when configured`() {
        val server = FakeMcpServer { method, _, request ->
            if (method == "initialize") {
                assertEquals("Bearer tok-1", request.header("Authorization"))
                jsonReply(request, JSONObject().put("result", JSONObject()
                    .put("protocolVersion", "2025-11-25")))
            } else plainAccepted(request)
        }
        val c = client(server, authToken = "tok-1")
        c.connect()
    }

    @Test
    fun `sse streamed reply is parsed`() {
        val server = FakeMcpServer { method, _, request ->
            when (method) {
                "initialize" -> sseReply(request, JSONObject().put("result", JSONObject()
                    .put("protocolVersion", "2025-11-25")))
                else -> plainAccepted(request)
            }
        }
        val c = client(server)
        c.connect() // must not throw — the reply arrived inside an SSE stream
        assertEquals("2025-11-25", c.negotiatedVersion)
    }

    // ── tools ──────────────────────────────────────────────────────

    private fun toolJson(name: String, description: String = "does things") = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("inputSchema", JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("q", JSONObject().put("type", "string"))))

    @Test
    fun `listTools follows cursor pagination`() {
        val server = FakeMcpServer { method, body, request ->
            when (method) {
                "initialize" -> jsonReply(request, JSONObject().put("result", JSONObject()
                    .put("protocolVersion", "2025-11-25")))
                "notifications/initialized" -> plainAccepted(request)
                "tools/list" -> {
                    val cursor = body.getJSONObject("params").optString("cursor", "")
                    val result = JSONObject()
                    if (cursor.isEmpty()) {
                        result.put("tools", JSONArray().put(toolJson("first")))
                            .put("nextCursor", "page-2")
                    } else {
                        assertEquals("page-2", cursor)
                        result.put("tools", JSONArray().put(toolJson("second")))
                    }
                    jsonReply(request, JSONObject().put("result", result))
                }
                else -> error("unexpected $method")
            }
        }
        val c = client(server)
        c.connect()
        val tools = c.listTools()
        assertEquals(listOf("first", "second"), tools.map { it.name })
        assertTrue(tools[0].inputSchemaJson.contains("\"q\""))
    }

    @Test
    fun `callTool flattens text content blocks`() {
        val server = routingServer { method, request ->
            when (method) {
                "tools/call" -> {
                    val body = okioBody(request)
                    assertEquals("get_weather", body.getJSONObject("params").getString("name"))
                    assertEquals(
                        "NYC",
                        body.getJSONObject("params").getJSONObject("arguments").getString("city"),
                    )
                    jsonReply(request, JSONObject().put("result", JSONObject()
                        .put("isError", false)
                        .put("content", JSONArray()
                            .put(JSONObject().put("type", "text").put("text", "Sunny 21C"))
                            .put(JSONObject().put("type", "text").put("text", "Wind: low")))))
                }
                else -> handshakeOrAccepted(method, request)
            }
        }
        val c = client(server)
        c.connect()
        val out = c.callTool("get_weather", """{"city":"NYC"}""")
        assertEquals("Sunny 21C\nWind: low", out)
    }

    @Test
    fun `tool-level error surfaces as readable string, never throws`() {
        val server = routingServer { method, request ->
            when (method) {
                "tools/call" -> jsonReply(request, JSONObject().put("result", JSONObject()
                    .put("isError", true)
                    .put("content", JSONArray().put(
                        JSONObject().put("type", "text").put("text", "bad city")))))
                else -> handshakeOrAccepted(method, request)
            }
        }
        val c = client(server)
        c.connect()
        val out = c.callTool("get_weather", "{}")
        assertTrue(out.contains("执行失败"))
        assertTrue(out.contains("bad city"))
    }

    @Test
    fun `transport failure degrades into error string`() {
        val server = routingServer { method, request ->
            when (method) {
                "tools/call" -> plain(request).code(500).build()
                else -> handshakeOrAccepted(method, request)
            }
        }
        val c = client(server)
        c.connect()
        val out = c.callTool("x", "{}")
        assertTrue(out.contains("MCP 工具调用失败"))
    }

    @Test
    fun `non-json arguments are wrapped as input`() {
        val server = routingServer { method, request ->
            when (method) {
                "tools/call" -> {
                    val args = okioBody(request).getJSONObject("params").getJSONObject("arguments")
                    assertEquals("2+2", args.getString("input"))
                    jsonReply(request, JSONObject().put("result", JSONObject()
                        .put("content", JSONArray().put(
                            JSONObject().put("type", "text").put("text", "4")))))
                }
                else -> handshakeOrAccepted(method, request)
            }
        }
        val c = client(server)
        c.connect()
        assertEquals("4", c.callTool("calculator", "2+2"))
    }

    @Test
    fun `rpc error reply comes back through callTool`() {
        val server = routingServer { method, request ->
            when (method) {
                "tools/call" -> jsonReply(request, JSONObject().put("error",
                    JSONObject().put("code", -32602).put("message", "unknown tool")))
                else -> handshakeOrAccepted(method, request)
            }
        }
        val c = client(server)
        c.connect()
        assertTrue(c.callTool("nope", "{}").contains("unknown tool"))
    }

    // ── helpers ────────────────────────────────────────────────────

    private fun okioBody(request: Request): JSONObject {
        val buffer = okio.Buffer()
        request.body!!.writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }

    private fun routingServer(route: (method: String, request: Request) -> Response) =
        FakeMcpServer { method, _, request -> route(method, request) }

    private fun handshakeOrAccepted(method: String, request: Request): Response =
        when (method) {
            "initialize" -> jsonReply(request, JSONObject().put("result", JSONObject()
                .put("protocolVersion", "2025-11-25")))
            else -> plainAccepted(request)
        }

    private fun jsonReply(request: Request, reply: JSONObject, session: String? = null): Response =
        plain(request)
            .header("Content-Type", "application/json")
            .apply { session?.let { header("MCP-Session-Id", it) } }
            .body(reply.toString().toRequestBody("application/json".toMediaType()))
            .build()

    private fun sseReply(request: Request, reply: JSONObject): Response {
        val body = "event: message\ndata: ${reply}\n\n"
        return plain(request)
            .header("Content-Type", "text/event-stream")
            .body(body.toRequestBody("text/event-stream".toMediaType()))
            .build()
    }

    private fun plainAccepted(request: Request): Response =
        plain(request).code(202).build()

    private fun plain(request: Request): Response.Builder =
        Response.Builder()
            .request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200)
            .message("test")
}
