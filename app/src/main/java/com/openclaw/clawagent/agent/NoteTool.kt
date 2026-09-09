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
            "list 列出全部笔记、delete 删除。当用户要求记住某事、之后要引用、或询问之前记过的内容时使用。" +
            "参数:action(save/read/list/delete)、title(除 list 外必填)、content(仅 save 需要)。"
    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["save", "read", "list", "delete"],
              "description": "要执行的笔记本操作"
            },
            "title": {
              "type": "string",
              "description": "笔记标题(save/read/delete 时必填),如 \"用户的咖啡偏好\""
            },
            "content": {
              "type": "string",
              "description": "笔记内容(save 时必填,支持 markdown)"
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
                "delete" -> delete(args.optString("title", ""))
                else -> "错误:未知 action \"$action\",支持 save / read / list / delete。"
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
    }
}
