package com.openclaw.clawagent.provider

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache of [ProviderHealth] results, keyed by provider id.
 *
 * Lives in the Activity; survives only the current session. The TTL in
 * [ProviderHealth.CACHE_TTL_MS] keeps repeated openings of the settings
 * dialog from re-probing every provider. A new check overrides any cached
 * value, so the cache is self-healing.
 */
class ProviderHealthCache {
    private val map = ConcurrentHashMap<String, ProviderHealth>()

    fun get(providerId: String): ProviderHealth =
        map[providerId] ?: ProviderHealth.unknown(providerId)

    /** Returns the cached value only if it's still fresh. */
    fun getFresh(providerId: String): ProviderHealth? =
        map[providerId]?.takeIf { ProviderHealth.isFresh(it) }

    fun put(health: ProviderHealth) {
        map[health.providerId] = health
    }

    fun invalidate(providerId: String) {
        map.remove(providerId)
    }

    fun clear() {
        map.clear()
    }
}
