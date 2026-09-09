package com.openclaw.clawagent.agent

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Gives the agent eyes on the live web: fetches any http(s) URL — an API
 * endpoint or a web page — and returns the status plus a compacted text
 * view of the body. This is the single most important "real agent" tool:
 * with it the model can answer questions about weather, prices, docs,
 * release notes... anything reachable by HTTP GET.
 *
 * Networking uses OkHttp (already a project dependency) and runs on the
 * caller's dispatcher (MainActivity executes tools off the main thread).
 *
 * [HttpToolLogic] holds the pure string/sanitising functions so they stay
 * unit-testable without network access.
 */
class HttpRequestTool : AgentTool {

    override val name = "http_get"
    override val description =
        "抓取一个 HTTP(S) 网址并返回内容(GET)。适合查询实时信息:网页文章、公开 API 数据、" +
            "天气/汇率/版本号等。网页 HTML 会自动转为纯文本。参数:url(必填)、max_chars(可选,返回正文最大字符数,默认 6000)。"
    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "url": {
              "type": "string",
              "description": "要抓取的完整 URL,以 http:// 或 https:// 开头"
            },
            "max_chars": {
              "type": "integer",
              "description": "返回正文的最大字符数,默认 6000,最大 20000"
            }
          },
          "required": ["url"]
        }
    """.trimIndent()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun execute(arguments: String): String {
        val args = try {
            JSONObject(arguments)
        } catch (_: Exception) {
            return "错误:参数不是合法 JSON。"
        }
        val rawUrl = args.optString("url", "").trim()
        if (rawUrl.isEmpty()) return "错误:缺少 url 参数。"

        val validated = HttpToolLogic.validateUrl(rawUrl)
            ?: return "错误:URL 不合法(仅支持 http/https,且不能包含用户信息或空白)。收到:\"$rawUrl\""

        val maxChars = (args.optInt("max_chars", DEFAULT_MAX_CHARS))
            .coerceIn(200, MAX_LIMIT_CHARS)

        return try {
            val request = Request.Builder()
                .url(validated)
                .header("User-Agent", "ClawAgent/2.0 (Android agent; +https://github.com/jdkdkdkdkxk677646/ClawAgent)")
                .header("Accept", "text/html,application/json,text/plain;q=0.9,*/*;q=0.5")
                .build()

            client.newCall(request).execute().use { response ->
                // Refuse bodies that would blow up memory before reading them.
                val declared = response.header("Content-Length")?.toLongOrNull() ?: -1L
                if (declared > MAX_BODY_BYTES) {
                    return "HTTP ${response.code} ${response.message}\n内容过大($declared 字节,上限 ${MAX_BODY_BYTES}),已放弃抓取。"
                }

                val contentType = response.header("Content-Type") ?: ""
                val rawBody = try {
                    response.body?.bytes()?.let { String(it, Charsets.UTF_8) } ?: ""
                } catch (e: Exception) {
                    return "HTTP ${response.code}:读取响应体失败:${e.message}"
                }

                val body = if (contentType.contains("html", ignoreCase = true) ||
                    rawBody.trimStart().startsWith("<", true) && rawBody.contains("</html>", true)
                ) {
                    HttpToolLogic.htmlToText(rawBody)
                } else {
                    rawBody
                }

                val final = HttpToolLogic.truncate(body, maxChars)
                buildString {
                    append("HTTP ${response.code} ${response.message}")
                    if (response.priorResponse != null) append("(经过重定向)")
                    append("\n最终 URL:").append(response.request.url)
                    append("\n类型:").append(contentType.ifEmpty { "未知" })
                    append("\n\n")
                    if (final.isBlank()) {
                        append("(响应体为空或提取不到文本)")
                    } else {
                        append(final)
                    }
                }
            }
        } catch (e: Exception) {
            "抓取失败:${e.message ?: e.javaClass.simpleName}(检查 URL 是否可达、是否需要代理)"
        }
    }

    companion object {
        const val DEFAULT_MAX_CHARS = 6000
        const val MAX_LIMIT_CHARS = 20000
        const val MAX_BODY_BYTES = 3_000_000L
    }
}

/** Pure helpers shared by [HttpRequestTool] and its unit tests. */
object HttpToolLogic {

    private val ILLEGAL_URL_CHARS = Regex("[\\s<>\"'`]")

    /**
     * Accepts absolute http/https URLs only. Returns null for anything
     * questionable: relative paths, other schemes (file:, javascript:,
     * intent:...), embedded credentials or control characters.
     */
    fun validateUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (ILLEGAL_URL_CHARS.containsMatchIn(trimmed)) return null
        val lower = trimmed.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        return try {
            val url = java.net.URL(trimmed)
            if (url.host.isNullOrBlank()) return null
            // Reject userinfo form (https://user:pass@host) — never needed
            // for the fetch targets we want and leaks credentials into logs.
            if (!url.userInfo.isNullOrBlank()) return null
            url.toString()
        } catch (_: Exception) {
            null
        }
    }

    private val SCRIPT_RE = Regex("(?is)<script\\b.*?</script\\s*>")
    private val STYLE_RE = Regex("(?is)<style\\b.*?</style\\s*>")
    private val NOSCRIPT_RE = Regex("(?is)<noscript\\b.*?</noscript\\s*>")
    private val TEMPLATE_RE = Regex("(?is)<template\\b.*?</template\\s*>")
    private val COMMENT_RE = Regex("(?s)<!--.*?-->")
    private val BLOCK_TAG_RE = Regex(
        "(?i)</?(p|div|br|hr|li|tr|h[1-6]|section|article|header|footer|nav|ul|ol|table|blockquote|pre|form|option)\\b[^>]*>"
    )
    private val TAG_RE = Regex("(?s)<[^>]*>")
    private val SPACES_RE = Regex("[ \\t\\x0B\\f\\r]+")
    private val BLANK_LINES_RE = Regex("\\n{3,}")

    /**
     * Compact an HTML page into readable plain text: drop scripts/styles/
     * comments/templates, turn block boundaries into newlines, strip the
     * remaining tags and decode the common entities. Keeps the result
     * small enough to feed straight to the model.
     */
    fun htmlToText(html: String): String {
        var text = html
        text = SCRIPT_RE.replace(text, " ")
        text = STYLE_RE.replace(text, " ")
        text = NOSCRIPT_RE.replace(text, " ")
        text = TEMPLATE_RE.replace(text, " ")
        text = COMMENT_RE.replace(text, " ")
        text = BLOCK_TAG_RE.replace(text, "\n")
        text = TAG_RE.replace(text, "")
        text = decodeEntities(text)
        text = SPACES_RE.replace(text, " ")
        text = BLANK_LINES_RE.replace(text, "\n\n")
        return text.trim()
    }

    fun decodeEntities(s: String): String = s
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#x27;", "'")
        .replace("&apos;", "'")

    fun truncate(text: String, maxChars: Int): String {
        if (maxChars <= 0 || text.length <= maxChars) return text
        return text.take(maxChars) + "\n…(正文已截断,原始长度 ${text.length} 字符)"
    }
}
