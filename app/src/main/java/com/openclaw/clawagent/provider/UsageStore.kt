package com.openclaw.clawagent.provider

import android.content.SharedPreferences

/**
 * Minimal string-keyed KV abstraction used by [UsageTracker].
 *
 * Why an interface instead of SharedPreferences directly? UsageTracker must
 * stay pure-JVM testable (the ledger math, day rollover and formatting are
 * exactly the logic worth unit-testing). Tests inject in-memory lambdas;
 * production wires [SharedPrefsUsageStore]. The surface is deliberately the
 * same two operations the tracker needs — nothing else.
 */
interface UsageStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

/**
 * SharedPreferences-backed [UsageStore] for production wiring. SharedPreferences
 * is thread-safe, which matters because [UsageTracker.record] is called from
 * OkHttp's dispatcher threads inside [ChatService].
 */
class SharedPrefsUsageStore(private val prefs: SharedPreferences) : UsageStore {
    override fun read(key: String): String? = prefs.getString(key, null)
    override fun write(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}
