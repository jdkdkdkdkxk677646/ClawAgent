package com.openclaw.clawagent.agent

import kotlinx.coroutines.runBlocking

/**
 * T-502: `search_history` tool — lets the Agent query its own conversation
 * history via SQLite FTS.
 *
 * Construction injects a synchronous query function as a test seam; production
 * code wraps a Room suspend DAO call with `runBlocking`. The tool never throws:
 * errors surface as readable strings so the agent loop keeps running.
 *
 * Thread contract: [execute] is called by AgentLoop on Dispatchers.Default; the
 * injected [queryFn] must be synchronous (no coroutine suspension) so we can
 * call it directly. Production wiring uses `runBlocking` around the suspend DAO.
 */
class HistoryTool(
    /**
     * Synchronous query seam. Returns hits from the FTS index.
     * Production: `runBlocking { dao.searchMessages(query, limit) }`.
     */
    private val queryFn: (String, Int) -> List<ConversationDao.MessageHit>,
) : AgentTool {

    override val name: String = "search_history"

    override val description: String = """
        在当前会话的历史消息中搜索。按相关度排序,返回匹配的对话片段。
        参数:
        - query: 搜索关键词(必填,非空)
        - limit: 最多返回条数(可选,默认 5,上限 20)
        输出:每条命中包含会话/分支名、角色、时间、内容摘要(前后各 60 字)。
        无命中时返回"没有找到相关的历史消息"。
    """.trimIndent()

    override val parametersJson: String = """
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "搜索关键词"
            },
            "limit": {
              "type": "integer",
              "description": "最多返回条数,默认 5,上限 20",
              "minimum": 1,
              "maximum": 20
            }
          },
          "required": ["query"]
        }
    """.trimIndent()

    override fun execute(arguments: String): String {
        return try {
            val args = org.json.JSONObject(arguments)
            val query = args.optString("query", "").trim()
            if (query.isEmpty()) {
                return "❌ 搜索关键词不能为空"
            }
            val limit = minOf(maxOf(args.optInt("limit", 5), 1), 20)
            val hits = queryFn(query, limit)
            if (hits.isEmpty()) {
                return "没有找到相关的历史消息"
            }
            buildString {
                append("找到 ${hits.size} 条相关历史消息:\n\n")
                hits.forEachIndexed { i, hit ->
                    if (i > 0) append("\n---\n\n")
                    val preview = previewContent(hit.content, window = 60)
                    append("【${hit.role}】${hit.branchId}(#${hit.idx}) · ${hit.timestamp}\n")
                    append(preview)
                }
            }
        } catch (e: Exception) {
            "搜索失败:${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun previewContent(content: String, window: Int): String {
        val idx = content.lowercase().indexOfAny(charArrayOf(
            '搜', '索', '关键', content.firstOrNull()?.lowercase() ?: ""
        ))
        return if (idx < 0) {
            content.take(window).let { if (content.length > window) it + "…" else it }
        } else {
            val start = maxOf(0, idx - window / 2)
            val end = minOf(content.length, idx + window)
            val preview = content.substring(start, end)
            "${if (start > 0) "…" else ""}$preview${if (end < content.length) "…" else ""}"
        }
    }

    companion object {
        /**
         * Factory for production wiring. Takes a Room [ConversationDao] and
         * returns a [HistoryTool] whose [queryFn] runs the DAO on the calling
         * thread via `runBlocking`.
         */
        fun create(dao: com.openclaw.clawagent.conversation.ConversationDao): HistoryTool =
            HistoryTool(queryFn = { q, l -> runBlocking { dao.searchMessages(q, l) } })
    }
}
