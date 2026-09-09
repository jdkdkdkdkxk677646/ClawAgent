package com.openclaw.clawagent.provider

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Pings a provider's `/models` endpoint and classifies the result into a
 * [ProviderHealth] status. No request body, no bearer token required (most
 * providers return 401 on `/models` if the key is wrong, which is itself a
 * useful signal).
 *
 * The checker is stateless and thread-safe; create one per Activity and
 * reuse it across checks. Cancelling the underlying OkHttp call is the
 * caller's responsibility.
 */
class ProviderHealthChecker(
    private val client: OkHttpClient = defaultHealthClient(),
) {

    /**
     * Compute the URL to ping for a given provider. If the provider specifies
     * its own [Provider.healthEndpoint], use it; otherwise derive the standard
     * `/models` URL from the chat-completions endpoint.
     */
    fun healthUrlFor(provider: Provider): String? {
        provider.healthEndpoint?.let { return it }
        val base = provider.defaultEndpoint
            .removeSuffix("/chat/completions")
            .removeSuffix("/v1/chat/completions")
        return if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
    }

    /**
     * Synchronously probe [provider]. Returns a [ProviderHealth]; never throws.
     *
     * The `apiKey` argument is optional: most providers happily answer HEAD on
     * `/models` without a key (returning the model list), but some (e.g.
     * DeepSeek) require it. Pass `null` to skip auth entirely.
     */
    fun check(provider: Provider, apiKey: String? = null): ProviderHealth {
        val url = healthUrlFor(provider) ?: return ProviderHealth.skipped(provider.id)
        val started = System.currentTimeMillis()

        val request = Request.Builder()
            .url(url)
            .head() // HEAD is cheaper; some providers may not support it,
                    // so the caller can opt into GET via [checkWithGet] below.
            .apply {
                if (!apiKey.isNullOrEmpty()) {
                    addHeader("Authorization", "Bearer $apiKey")
                }
            }
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - started
                when {
                    response.isSuccessful ->
                        if (latency > ProviderHealth.SLOW_THRESHOLD_MS) {
                            ProviderHealth(
                                providerId = provider.id,
                                status = ProviderHealth.Status.Slow,
                                latencyMs = latency,
                            )
                        } else {
                            ProviderHealth(
                                providerId = provider.id,
                                status = ProviderHealth.Status.Ok,
                                latencyMs = latency,
                            )
                        }

                    response.code == 401 || response.code == 403 ->
                        ProviderHealth(
                            providerId = provider.id,
                            status = ProviderHealth.Status.Auth,
                            httpCode = response.code,
                            message = "API Key 无效或缺失",
                        )

                    else ->
                        ProviderHealth(
                            providerId = provider.id,
                            status = ProviderHealth.Status.HttpError,
                            httpCode = response.code,
                            message = "HTTP ${response.code}",
                        )
                }
            }
        } catch (e: UnknownHostException) {
            ProviderHealth(
                providerId = provider.id,
                status = ProviderHealth.Status.Offline,
                message = "域名解析失败",
            )
        } catch (e: SocketTimeoutException) {
            ProviderHealth(
                providerId = provider.id,
                status = ProviderHealth.Status.Offline,
                message = "连接超时",
            )
        } catch (e: IOException) {
            Log.w(TAG, "health check failed for ${provider.id}", e)
            ProviderHealth(
                providerId = provider.id,
                status = ProviderHealth.Status.Offline,
                message = e.message ?: "网络错误",
            )
        } catch (e: Exception) {
            Log.w(TAG, "unexpected error checking ${provider.id}", e)
            ProviderHealth(
                providerId = provider.id,
                status = ProviderHealth.Status.HttpError,
                message = e.message ?: "未知错误",
            )
        }
    }

    /**
     * Fallback for providers that don't answer to HEAD on `/models` (a few do
     * not). Same classification rules as [check], just uses GET.
     */
    fun checkWithGet(provider: Provider, apiKey: String? = null): ProviderHealth {
        val url = healthUrlFor(provider) ?: return ProviderHealth.skipped(provider.id)
        val started = System.currentTimeMillis()

        val request = Request.Builder()
            .url(url)
            .get()
            .apply {
                if (!apiKey.isNullOrEmpty()) {
                    addHeader("Authorization", "Bearer $apiKey")
                }
            }
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - started
                when {
                    response.isSuccessful ->
                        if (latency > ProviderHealth.SLOW_THRESHOLD_MS) {
                            ProviderHealth(provider.id, ProviderHealth.Status.Slow, latencyMs = latency)
                        } else {
                            ProviderHealth(provider.id, ProviderHealth.Status.Ok, latencyMs = latency)
                        }
                    response.code == 401 || response.code == 403 ->
                        ProviderHealth(provider.id, ProviderHealth.Status.Auth, httpCode = response.code, message = "API Key 无效或缺失")
                    else ->
                        ProviderHealth(provider.id, ProviderHealth.Status.HttpError, httpCode = response.code, message = "HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            ProviderHealth(provider.id, ProviderHealth.Status.Offline, message = e.message ?: "网络错误")
        }
    }

    companion object {
        private const val TAG = "ProviderHealthChecker"

        fun defaultHealthClient(): OkHttpClient = OkHttpClient.Builder()
            // Health checks should fail fast — no point in waiting 30s for a
            // /models probe when the user is staring at the settings dialog.
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
