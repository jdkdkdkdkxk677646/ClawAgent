package com.openclaw.clawagent.provider

/**
 * Minimal string-keyed KV abstraction used by [UsageTracker].
 *
 * Why an interface instead of a platform store directly? UsageTracker must
 * stay pure-JVM testable (the ledger math, day rollover and formatting are
 * exactly the logic worth unit-testing). Tests inject in-memory lambdas;
 * production wires the SharedPreferences-backed implementation that lives in
 * `:app`. The surface is deliberately the same two operations the tracker
 * needs — nothing else.
 */
interface UsageStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
}
