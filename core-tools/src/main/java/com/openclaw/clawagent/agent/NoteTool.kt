package com.openclaw.clawagent.agent

import org.json.JSONObject
import java.io.File

/**
 * The agent's own persistent notebook — its long-term memory. Everything
 * saved here survives process death and app restarts, which is what makes
 * "记住这个 / 下次告诉我" style requests actually work across sessions.
 *
 * Files live as markdown under a dedicated directory (injected via the
 * constructor, so tests pass a temp dir): `filesDir/agent_notes/<title>.md`.
 *
 * Pure JVM (java.io only) — fully unit-testable without Robolectric.
 */
class NoteTool(private val baseDir: File) : AgentTool {

    override val name = "notes"
    override val description =
        "读写 Agent 的持久笔记本(跨会话保存)。支持操作:save 保存/更新笔记、read 读取、" +
            "list 列出全部笔记、search 按关键词搜索笔记内容、delete 删除。" +
            "当用户透露稳定信息(偏好、项目、习惯)时主动 save;用户提到\"之前/上次/我记过的\"时先 search。" +
            "参数:action(save/read/list/search/delete)、title(除 list/search 外必填)、content(仅 save 需要)、query(仅 search 需要)。"
    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["save", "read", "list", "search", "delete"],
              "description": "要执行的笔记本操作"
            },
            "title": {
              "type": "string",
              "description": "笔记标题(save/read/delete 时必填),如 \"用户的咖啡偏好\""
            },
            "content": {
              "type": "string",
              "description": "笔记内容(save 时必填,支持 markdown)"
            },
            "query": {
              "type": "string",
              "description": "搜索关键词(action=search 时必填,匹配标题或正文)"
            }
          },
          "required": ["action"]
        }
    """.trimIndent()

    override fun execute(arguments: String): String {
        val args = try {
            JSONObject(arguments)
        } catch (_: Exception) {
            return "错误:参数不是合法 JSON。"
        }
        val action = args.optString("action", "").trim().lowercase()
        return try {
            when (action) {
                "save" -> save(args.optString("title", ""), args.optString("content", ""))
                "read" -> read(args.optString("title", ""))
                "list" -> list()
                "search" -> search(args.optString("query", ""))
                "delete" -> delete(args.optString("title", ""))
                else -> "错误:未知 action \"$action\",支持 save / read / list / search / delete。"
            }
        } catch (e: Exception) {
            "笔记操作失败:${e.message ?: e.javaClass.simpleName}"
        }
    }

    // ----- actions ----------------------------------------------------------

    private fun save(title: String, content: String): String {
        if (title.isBlank()) return "错误:save 需要 title 参数。"
        if (content.isBlank()) return "错误:save 需要 content 参数(传空请改用 delete)。"
        ensureDir()
        val file = fileFor(title)
        val existed = file.exists()
        file.writeText(content.trim() + "\n")
        return (if (existed) "已更新笔记" else "已保存新笔记") +
            "「${file.nameWithoutExtension}」(${content.trim().length} 字符)。"
    }

    private fun read(title: String): String {
        if (title.isBlank()) return "错误:read 需要 title 参数。"
        val file = fileFor(title)
        if (!file.exists()) {
            val similar = listTitles().firstOrNull { it.contains(fileFor(title).nameWithoutExtension) }
            return "没有名为「$title」的笔记。" +
                (similar?.let { "相近的笔记:「$it」。" } ?: "用 action=list 查看全部笔记。")
        }
        return "「${file.nameWithoutExtension}」内容:\n${file.readText().trim()}"
    }

    private fun list(): String {
        ensureDir()
        val titles = listTitles()
        if (titles.isEmpty()) return "笔记本是空的。用 action=save 保存第一条笔记。"
        return "共 ${titles.size} 条笔记:\n" + titles.joinToString("\n") { "- $it" }
    }

    /**
     * Relevance-ranked lookup across titles and bodies. Recall no longer needs
     * a verbatim substring: [NoteSearchLogic] tokenises the query (latin words
     * + CJK bigrams), scores every note (exact hit ≫ title ≫ body, with an
     * all-tokens bonus) and returns the top matches with a snippet. This is the
     * agent's stand-in for semantic memory recall.
     */
    private fun search(query: String): String {
        if (query.isBlank()) return "错误:search 需要 query 参数。"
        ensureDir()
        val files = baseDir.listFiles { f -> f.isFile && f.extension == "md" }
            ?.sortedBy { it.nameWithoutExtension }
            ?: emptyList()
        if (files.isEmpty()) return "笔记本是空的。"

        val needle = query.trim()
        val ranked = files.mapNotNull { file ->
            val title = file.nameWithoutExtension
            val body = file.readText()
            val score = NoteSearchLogic.score(query, title, body)
            if (score > 0) Ranked(title, body, score) else null
        }.sortedByDescending { it.score }

        if (ranked.isEmpty()) return "笔记本中没有与「$query」相关的笔记。"

        val top = ranked.take(TOP_MATCHES)
        return buildString {
            append("找到 ${top.size} 条(按相关度,共扫描 ${files.size} 条):")
            top.forEach { hit ->
                val exact = hit.title.contains(needle, ignoreCase = true) ||
                    hit.body.contains(needle, ignoreCase = true)
                val tag = if (exact) "精确" else "相关度 ${hit.score}"
                append("\n- 「").append(hit.title).append("」[").append(tag).append("]:")
                    .append(NoteSearchLogic.snippet(hit.body, query))
            }
        }
    }

    private data class Ranked(val title: String, val body: String, val score: Int)

    private fun delete(title: String): String {
        if (title.isBlank()) return "错误:delete 需要 title 参数。"
        val file = fileFor(title)
        if (!file.exists()) return "没有名为「$title」的笔记,无需删除。"
        return if (file.delete()) "已删除笔记「${file.nameWithoutExtension}」。"
        else "删除笔记「${file.nameWithoutExtension}」失败。"
    }

    // ----- helpers ----------------------------------------------------------

    private fun listTitles(): List<String> =
        baseDir.listFiles { f -> f.isFile && f.extension == "md" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: emptyList()

    private fun fileFor(title: String): File = File(baseDir, slug(title) + ".md")

    /**
     * Filesystem-safe filename: strip path separators and characters illegal
     * on common filesystems, cap the length. Chinese titles are kept as-is.
     */
    internal fun slug(title: String): String {
        val cleaned = title
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t\\x00]"), "_")
            .trim()
            .trim('.')
            .take(MAX_TITLE_LEN)
            .trim()
            .trim('.')
        return cleaned.ifEmpty { "untitled" }
    }

    private fun ensureDir() {
        if (!baseDir.exists()) baseDir.mkdirs()
    }

    companion object {
        private const val MAX_TITLE_LEN = 80
        private const val TOP_MATCHES = 5
    }
}
