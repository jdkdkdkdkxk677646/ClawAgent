package com.openclaw.clawagent.provider

/**
 * Per-provider health-check result. The UI in the settings dialog renders one
 * of these next to each provider name.
 *
 * The states are deliberately separate so the UI can give the user a useful
 * next step:
 *   - [Offline]   → "check your network or the endpoint URL"
 *   - [Auth]      → "your API key is wrong or missing"
 *   - [HttpError] → "the provider is having issues, try again later"
 *   - [Slow]      → "works, but painfully slow; consider a different region"
 *   - [Ok]        → "ready to chat"
 */
data class ProviderHealth(
    val providerId: String,
    val status: Status,
    val latencyMs: Long? = null,
    val httpCode: Int? = null,
    val message: String? = null,
    val checkedAt: Long = System.currentTimeMillis(),
) {
    enum class Status {
        /** Never checked yet, or cache expired. */
        Unknown,

        /** A check is currently in flight. */
        Checking,

        /** 2xx and latency under [SLOW_THRESHOLD_MS]. */
        Ok,

        /** 2xx but latency above the threshold. Still usable. */
        Slow,

        /** 401 / 403 — credentials wrong or missing. */
        Auth,

        /** Could not connect: DNS, refused, timeout, etc. */
        Offline,

        /** Other non-2xx response. */
        HttpError,

        /** Provider opted out of health checks (e.g. local mock). */
        Skipped,
    }

    companion object {
        /** Above this round-trip time, we still mark the provider as [Slow]. */
        const val SLOW_THRESHOLD_MS = 3_000L

        /** Cache results this long so opening the settings dialog doesn't hammer the API. */
        const val CACHE_TTL_MS = 5 * 60 * 1_000L

        fun unknown(providerId: String): ProviderHealth =
            ProviderHealth(providerId, Status.Unknown)

        fun checking(providerId: String): ProviderHealth =
            ProviderHealth(providerId, Status.Checking)

        fun skipped(providerId: String): ProviderHealth =
            ProviderHealth(providerId, Status.Skipped)

        fun isFresh(h: ProviderHealth): Boolean =
            h.status != Status.Unknown &&
                h.status != Status.Checking &&
                (System.currentTimeMillis() - h.checkedAt) < CACHE_TTL_MS
    }
}
