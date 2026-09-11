package com.openclaw.clawagent.provider

import android.content.SharedPreferences

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
