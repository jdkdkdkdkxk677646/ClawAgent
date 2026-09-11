package com.openclaw.clawagent.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the daily token ledger. The KV layer and the clock are
 * constructor-injected, so day rollover is driven by swapping the injected
 * [UsageTracker.todayKey] lambda instead of sleeping until midnight.
 */
class UsageTrackerTest {

    /** In-memory KV double that mimics SharedPreferences string storage. */
    private class FakeKv {
        val map = HashMap<String, String>()
        val read: (String) -> String? = { map[it] }
        val write: (String, String) -> Unit = { key, value -> map[key] = value }
    }

    private fun tracker(kv: FakeKv, day: () -> String) =
        UsageTracker(kv.read, kv.write, todayKey = day)

    @Test
    fun `record and summary round trip`() {
        val t = tracker(FakeKv()) { "2026-07-22" }
        t.record(1200, 3400, 4600)
        assertEquals(
            "今日 1 次请求,输入 1.2k,输出 3.4k,合计 4.6k tokens",
            t.todaySummary(),
        )
    }

    @Test
    fun `first record with empty KV opens a fresh ledger`() {
        val kv = FakeKv()
        val t = tracker(kv) { "2026-07-22" }
        t.record(10, 20, 30)
        // Exactly one new key was minted for today.
        assertEquals(setOf("usage_tokens_2026-07-22"), kv.map.keys)
        assertEquals(UsageTracker.DayUsage(1, 10, 20, 30), t.todayUsage())
    }

    @Test
    fun `multiple records accumulate into one day`() {
        val t = tracker(FakeKv()) { "2026-07-22" }
        t.record(100, 50, 150)
        t.record(200, 100, 300)
        t.record(23, 27, 50)
        assertEquals(
            UsageTracker.DayUsage(requestCount = 3, promptTokens = 323, completionTokens = 177, totalTokens = 500),
            t.todayUsage(),
        )
    }

    @Test
    fun `crossing midnight opens a new ledger and keeps the old day`() {
        val kv = FakeKv()
        var day = "2026-07-22"
        val t = tracker(kv) { day }
        t.record(1000, 2000, 3000)

        day = "2026-07-23"
        // New day starts from zero, not from yesterday's totals.
        t.record(10, 20, 30)
        assertEquals(UsageTracker.DayUsage(1, 10, 20, 30), t.todayUsage())

        // Yesterday's bucket survived under its own key — per-day keys, not
        // a single rolling counter.
        assertEquals(2, kv.map.size)
        assertTrue(kv.map.containsKey("usage_tokens_2026-07-22"))
        assertTrue(kv.map.containsKey("usage_tokens_2026-07-23"))
    }

    @Test
    fun `empty day summary shows zeros`() {
        val t = tracker(FakeKv()) { "2026-07-22" }
        assertEquals(
            "今日 0 次请求,输入 0,输出 0,合计 0 tokens",
            t.todaySummary(),
        )
    }

    @Test
    fun `token counts abbreviate to human k units`() {
        assertEquals("0", UsageTracker.formatTokens(0))
        assertEquals("999", UsageTracker.formatTokens(999))
        assertEquals("1k", UsageTracker.formatTokens(1000))
        assertEquals("1.2k", UsageTracker.formatTokens(1234))
        assertEquals("12.3k", UsageTracker.formatTokens(12_345))
        assertEquals("999.9k", UsageTracker.formatTokens(999_900))
        assertEquals("1.2m", UsageTracker.formatTokens(1_234_567))
    }

    @Test
    fun `corrupt stored payload degrades to a zero day`() {
        val kv = FakeKv()
        kv.map["usage_tokens_2026-07-22"] = "not-json{"
        val t = tracker(kv) { "2026-07-22" }
        assertEquals(UsageTracker.DayUsage(0, 0, 0, 0), t.todayUsage())
        // Recording still works on top of the corrupt bucket.
        t.record(5, 5, 10)
        assertEquals(UsageTracker.DayUsage(1, 5, 5, 10), t.todayUsage())
    }

    @Test
    fun `negative provider numbers are clamped instead of corrupting sums`() {
        val t = tracker(FakeKv()) { "2026-07-22" }
        t.record(-100, 50, 30)
        assertEquals(UsageTracker.DayUsage(1, 0, 50, 30), t.todayUsage())
    }

    @Test
    fun `throwing KV callbacks never propagate out of the tracker`() {
        val t = UsageTracker(
            read = { error("boom") },
            write = { _, _ -> error("boom") },
            todayKey = { "2026-07-22" },
        )
        // Neither entry point may throw — accounting must never break the
        // chat request it rides on.
        t.record(1, 1, 2)
        assertEquals("今日 0 次请求,输入 0,输出 0,合计 0 tokens", t.todaySummary())
    }

    @Test
    fun `store constructor delegates to the same ledger`() {
        val kv = FakeKv()
        val t = UsageTracker(object : UsageStore {
            override fun read(key: String): String? = kv.read(key)
            override fun write(key: String, value: String) = kv.write(key, value)
        }, todayKey = { "2026-07-22" })
        t.record(1500, 500, 2000)
        assertEquals(
            "今日 1 次请求,输入 1.5k,输出 500,合计 2k tokens",
            t.todaySummary(),
        )
    }
}
