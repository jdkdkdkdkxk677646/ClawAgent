package com.openclaw.clawagent.agent

/**
 * Pure, dependency-free retrieval scoring for [NoteTool]'s `search`.
 *
 * The old search was a single verbatim `contains`, so asking for「咖啡偏好」
 * never recalled a note that says「拿铁」. This object adds a tiny bit of
 * lexical smarts without any embedding service, network call or new dependency:
 *
 *  - [tokenize] splits a query into latin/digit words plus **CJK bigrams**
 *    (a single CJK char is kept whole), de-duplicated;
 *  - [score] gives an exact (case-insensitive) whole-query hit top marks, and
 *    otherwise sums per-token hits with a title-heavy weight and an all-tokens
 *    bonus;
 *  - [snippet] extracts a short window around the first hit for display.
 *
 * No I/O, no `org.json` — trivially unit-testable.
 */
object NoteSearchLogic {

    /** Weight when the whole query matches the note title. */
    const val EXACT_TITLE = 40

    /** Weight when the whole query matches the note body. */
    const val EXACT_BODY = 25

    private const val TITLE_WEIGHT = 3
    private const val BODY_WEIGHT = 1
    private const val ALL_TOKENS_BONUS = 10

    /**
     * Splits [query] into search tokens: runs of latin letters/digits become
     * one lowercased token; runs of CJK characters become sliding bigrams
     * (a single CJK character stays a unigram). Order-preserving de-dup.
     */
    fun tokenize(query: String): List<String> {
        val lower = query.lowercase()
        val tokens = LinkedHashSet<String>()
        var i = 0
        while (i < lower.length) {
            val c = lower[i]
            when {
                isLatinOrDigit(c) -> {
                    val start = i
                    while (i < lower.length && isLatinOrDigit(lower[i])) i++
                    tokens.add(lower.substring(start, i))
                }

                isCjk(c) -> {
                    val start = i
                    while (i < lower.length && isCjk(lower[i])) i++
                    val run = lower.substring(start, i)
                    if (run.length == 1) {
                        tokens.add(run)
                    } else {
                        for (j in 0 until run.length - 1) tokens.add(run.substring(j, j + 2))
                    }
                }

                else -> i++
            }
        }
        return tokens.toList()
    }

    /**
     * Relevance of a note to [query]. An exact whole-query hit short-circuits
     * (title [EXACT_TITLE] beats body [EXACT_BODY]); otherwise each token that
     * appears in the title scores [TITLE_WEIGHT] and in the body [BODY_WEIGHT],
     * with an [ALL_TOKENS_BONUS] when every token is found. Zero means no hit.
     */
    fun score(query: String, title: String, body: String): Int {
        val needle = query.trim()
        if (needle.isEmpty()) return 0
        if (title.contains(needle, ignoreCase = true)) return EXACT_TITLE
        if (body.contains(needle, ignoreCase = true)) return EXACT_BODY

        val tokens = tokenize(needle)
        if (tokens.isEmpty()) return 0

        var total = 0
        var matched = 0
        for (token in tokens) {
            var hit = false
            if (title.contains(token, ignoreCase = true)) {
                total += TITLE_WEIGHT
                hit = true
            }
            if (body.contains(token, ignoreCase = true)) {
                total += BODY_WEIGHT
                hit = true
            }
            if (hit) matched++
        }
        if (matched == 0) return 0
        if (matched == tokens.size) total += ALL_TOKENS_BONUS
        return total
    }

    /**
     * A whitespace-collapsed excerpt of [body] centred on the first hit of
     * [query] (whole query first, then any token), up to [maxLen] characters.
     * Ellipses mark trimmed ends; falls back to the head when nothing matches.
     */
    fun snippet(body: String, query: String, maxLen: Int = 60): String {
        val flat = body.replace(WHITESPACE, " ").trim()
        if (flat.isEmpty()) return ""

        val lowerFlat = flat.lowercase()
        val needle = query.trim().lowercase()

        var hit = if (needle.isEmpty()) -1 else lowerFlat.indexOf(needle)
        if (hit < 0) {
            for (token in tokenize(query)) {
                val idx = lowerFlat.indexOf(token)
                if (idx >= 0) {
                    hit = idx
                    break
                }
            }
        }
        if (hit < 0) {
            return if (flat.length <= maxLen) flat else flat.take(maxLen) + "…"
        }

        val half = maxLen / 2
        var start = (hit - half).coerceAtLeast(0)
        var end = (start + maxLen).coerceAtMost(flat.length)
        start = (end - maxLen).coerceAtLeast(0)

        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < flat.length) "…" else ""
        return prefix + flat.substring(start, end) + suffix
    }

    private val WHITESPACE = Regex("\\s+")

    private fun isLatinOrDigit(c: Char): Boolean =
        c in 'a'..'z' || c in '0'..'9'

    private fun isCjk(c: Char): Boolean =
        c.code in 0x4E00..0x9FFF || // CJK Unified Ideographs
            c.code in 0x3400..0x4DBF || // CJK Extension A
            c.code in 0xF900..0xFAFF // CJK Compatibility Ideographs
}
