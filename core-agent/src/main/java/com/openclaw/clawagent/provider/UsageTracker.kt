package com.openclaw.clawagent.provider

import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Daily token ledger for the chat provider.
 *
 * Why callback-injected KV? The usage numbers are tiny and naturally ride on
 * storage the app already owns (SharedPreferences). Injecting `read`/`write`
 * keeps this class free of Android imports so the ledger math, day rollover
 * and formatting are unit-testable on the plain JVM; production passes a
 * [UsageStore] through the convenience constructor.
 *
 * The day bucket key is generated internally from the injectable [todayKey]
 * clock, so data is stored per calendar day (`usage_tokens_YYYY-MM-DD`) and a
 * new day automatically starts an empty ledger — old days keep their own keys.
 *
 * Threading: [record] may be called from any thread (OkHttp's dispatcher,
 * via [ChatService]); thread safety is delegated to the injected callbacks
 * (SharedPreferences qualifies). Every entry point swallows failures — usage
 * tracking is a side quest and must never break the chat request it rides on.
 */
class UsageTracker(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
    /**
     * Returns the day bucket being accounted (local calendar date). Injectable
     * so tests can cross midnight deterministically.
     */
    private val todayKey: () -> String = { LocalDate.now().format(DAY_FORMAT) },
) {

    constructor(store: UsageStore, todayKey: () -> String = { LocalDate.now().format(DAY_FORMAT) })
        : this(store::read, store::write, todayKey)

    /** One day's accumulated numbers. */
    data class DayUsage(
        val requestCount: Long,
        val promptTokens: Long,
        val completionTokens: Long,
        val totalTokens: Long,
    )

    /**
     * Add one request to today's ledger. [totalTokens] is stored as reported
     * by the provider (not re-derived) so the ledger mirrors the bill; some
     * endpoints omit it, in which case the extractor has already derived it.
     * Negative values are clamped — a broken provider must not corrupt sums.
     */
    fun record(promptTokens: Long, completionTokens: Long, totalTokens: Long) {
        try {
            val key = keyFor(todayKey())
            val current = deserialize(read(key))
            write(
                key,
                serialize(
                    DayUsage(
                        requestCount = current.requestCount + 1,
                        promptTokens = current.promptTokens + promptTokens.coerceAtLeast(0),
                        completionTokens = current.completionTokens + completionTokens.coerceAtLeast(0),
                        totalTokens = current.totalTokens + totalTokens.coerceAtLeast(0),
                    )
                )
            )
        } catch (_: Exception) {
            // Broken KV callback / full storage: drop the sample, never the
            // chat flow this accounting rides on.
        }
    }

    /** Today's numbers; all zeros when nothing was recorded or storage failed. */
    fun todayUsage(): DayUsage = try {
        deserialize(read(keyFor(todayKey())))
    } catch (_: Exception) {
        DayUsage(0, 0, 0, 0)
    }

    /**
     * Human-readable summary of today's usage, e.g.
     * 「今日 12 次请求,输入 1.2k,输出 3.4k,合计 4.6k tokens」
     */
    fun todaySummary(): String = summaryOf(todayUsage())

    companion object {
        // Namespaced prefix so the keys can share a SharedPreferences file
        // with SecurePrefs' settings keys without colliding.
        private const val KEY_PREFIX = "usage_tokens_"
        private val DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        private fun keyFor(day: String) = "$KEY_PREFIX$day"

        internal fun serialize(usage: DayUsage): String = JSONObject().apply {
            put("requests", usage.requestCount)
            put("prompt_tokens", usage.promptTokens)
            put("completion_tokens", usage.completionTokens)
            put("total_tokens", usage.totalTokens)
        }.toString()

        /**
         * Missing or corrupt payload degrades to an empty day rather than
         * crashing or silently skipping the day's accounting.
         */
        internal fun deserialize(text: String?): DayUsage {
            if (text.isNullOrEmpty()) return DayUsage(0, 0, 0, 0)
            return try {
                val json = JSONObject(text)
                DayUsage(
                    json.optLong("requests", 0),
                    json.optLong("prompt_tokens", 0),
                    json.optLong("completion_tokens", 0),
                    json.optLong("total_tokens", 0),
                )
            } catch (_: Exception) {
                DayUsage(0, 0, 0, 0)
            }
        }

        internal fun summaryOf(usage: DayUsage): String =
            "今日 ${usage.requestCount} 次请求,输入 ${formatTokens(usage.promptTokens)}," +
                "输出 ${formatTokens(usage.completionTokens)},合计 ${formatTokens(usage.totalTokens)} tokens"

        /**
         * Compact human-readable token count: plain number below 1k, one-decimal
         * "k" above that, "m" past a million. Locale.US pins the decimal point —
         * some locales would render "1,2k", which reads wrong in this summary.
         */
        internal fun formatTokens(tokens: Long): String = when {
            tokens < 1_000L -> tokens.toString()
            tokens < 1_000_000L -> abbreviate(tokens / 1_000.0) + "k"
            tokens < 1_000_000_000L -> abbreviate(tokens / 1_000_000.0) + "m"
            else -> abbreviate(tokens / 1_000_000_000.0) + "b"
        }

        private fun abbreviate(value: Double): String {
            // "1000.0" → "1000": keep the unit boundary honest without ever
            // showing a dangling ".0" ("1000k" is fine, "1000.0k" is not).
            return String.format(Locale.US, "%.1f", value).removeSuffix(".0")
        }
    }
}
