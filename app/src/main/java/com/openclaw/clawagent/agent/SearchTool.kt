package com.openclaw.clawagent.agent

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * The agent's research claw: keyword search over the live web via DuckDuckGo's
 * lite endpoint (no API key, no SDK, minimal HTML). Pairs with [HttpRequestTool]:
 * search finds *where*, http_get reads *what*. Together they close the loop
 * that "I don't know the URL" used to leave open.
 *
 * [SearchLogic] holds the pure parsing functions — unit-tested against a
 * frozen HTML sample, no network involved.
 */
class WebSearchTool : AgentTool {

    override val name = "web_search"
    override val description =
        "联网搜索(DuckDuckGo)。当你不知道确切网址、需要找资料/新闻/价格/文档的入口时,先用本工具" +
            "拿到候选链接,再用 http_get 读取具体页面。参数:query(必填,关键词)、max_results(可选,默认 6,最大 15)。"
    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "搜索关键词,不宜过长,一次一个主题"
            },
            "max_results": {
              "type": "integer",
              "description": "返回结果条数上限,默认 6"
            }
          },
          "required": ["query"]
        }
    """.trimIndent()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    override fun execute(arguments: String): String {
        val args = try {
            JSONObject(arguments)
        } catch (_: Exception) {
            return "错误:参数不是合法 JSON。"
        }
        val query = args.optString("query", "").trim()
        if (query.isEmpty()) return "错误:缺少 query 参数。"
        if (query.length > MAX_QUERY_LEN) return "错误:query 过长(>${MAX_QUERY_LEN} 字符),拆成更精确的关键词。"

        val maxResults = args.optInt("max_results", DEFAULT_RESULTS).coerceIn(1, MAX_RESULTS)

        return try {
            val url = URL + URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return "搜索失败:HTTP ${response.code}(端点限流或不可用,稍后重试或换关键词)。"
                }
                val html = response.body?.bytes()?.let { String(it, Charsets.UTF_8) } ?: ""
                val results = SearchLogic.parseResults(html)
                if (results.isEmpty()) {
                    return "没有找到与「$query」相关的结果(换个关键词试试)。"
                }
                buildString {
                    append("搜索「$query」,共 ${results.size} 条结果:\n")
                    results.take(maxResults).forEachIndexed { i, r ->
                        append("\n${i + 1}. ${r.title}\n   ${r.url}")
                        if (r.snippet.isNotBlank()) append("\n   ${r.snippet}")
                    }
                    append("\n\n用 http_get 抓取以上任一链接可读取全文。")
                }
            }
        } catch (e: Exception) {
            "搜索失败:${e.message ?: e.javaClass.simpleName}(检查网络,或改用 http_get 直接抓取已知网址)"
        }
    }

    companion object {
        const val URL = "https://lite.duckduckgo.com/lite/?q="
        const val DEFAULT_RESULTS = 6
        const val MAX_RESULTS = 15
        const val MAX_QUERY_LEN = 200
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    }
}

/** Pure parsing for DuckDuckGo lite responses — no network, fully testable. */
object SearchLogic {

    data class Result(val title: String, val url: String, val snippet: String)

    // lite endpoint layout: result rows carry <a href="//duckduckgo.com/l/?uddg=<encoded>&rut=...">title</a>,
    // snippets live in <td class="result-snippet">. Links and snippets appear
    // in the same order, so zip them by position.
    private val LINK_RE = Regex("""<a[^>]+href="([^"]*)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    private val SNIPPET_RE = Regex(
        """<td[^>]*class="result-snippet"[^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL
    )
    private val TAG_RE = Regex("(?s)<[^>]*>")

    fun parseResults(html: String): List<Result> {
        val links = LINK_RE.findAll(html).mapNotNull { m ->
            val rawHref = m.groupValues[1]
            val real = unwrapRedirect(rawHref) ?: return@mapNotNull null
            val title = clean(m.groupValues[2])
            if (title.isEmpty()) null else Result(title, real, "")
        }.toList()

        val snippets = SNIPPET_RE.findAll(html).map { clean(it.groupValues[1]) }.toList()

        return links.mapIndexed { i, r ->
            if (i < snippets.size) r.copy(snippet = snippets[i]) else r
        }
    }

    /**
     * lite endpoint wraps target URLs as
     * `//duckduckgo.com/l/?uddg=<urlencoded>&rut=<hex>` — decode the `uddg`
     * parameter to get the real destination. Non-redirect anchors (about
     * pages, ad links) return null.
     */
    fun unwrapRedirect(href: String): String? {
        val marker = "uddg="
        val idx = href.indexOf(marker)
        if (idx < 0) return null
        val tail = href.substring(idx + marker.length)
        val encoded = tail.substringBefore('&')
        return try {
            val decoded = java.net.URLDecoder.decode(encoded, "UTF-8")
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) decoded else null
        } catch (_: Exception) {
            null
        }
    }

    private fun clean(fragment: String): String =
        TAG_RE.replace(fragment, "")
            .replace("&nbsp;", " ")
            .let { HttpToolLogic.decodeEntities(it) }
            .replace(Regex("\\s+"), " ")
            .trim()
}
