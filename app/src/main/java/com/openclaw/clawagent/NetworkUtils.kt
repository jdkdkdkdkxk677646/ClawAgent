package com.openclaw.clawagent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Utility class for network connectivity checks and error classification.
 *
 * Provides:
 * - Active network detection (Wi-Fi / Cellular / Ethernet)
 * - Internet reachability check (best-effort, via system APIs)
 * - OkHttp interceptor that maps low-level IO exceptions to human-readable
 *   [NetworkError] values for the UI layer.
 */
object NetworkUtils {

    // ── Connectivity checks ──────────────────────────────────────

    /**
     * Returns true if the device currently has *any* network with
     * internet capability.
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            info != null && info.isConnected
        }
    }

    /**
     * Returns the human-readable name of the active transport, e.g.
     * "Wi-Fi", "Cellular", "Ethernet", or "Unknown".
     */
    fun getActiveTransportName(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return "Unknown"
            val caps = cm.getNetworkCapabilities(network) ?: return "Unknown"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "Other"
            }
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            info?.typeName ?: "Unknown"
        }
    }

    // ── OkHttp interceptor ────────────────────────────────────────

    /**
     * An [Interceptor] that catches common network-layer exceptions and
     * re-throws them as [NetworkError] so callers can display a user-friendly
     * message without parsing stack traces.
     *
     * Attach to the OkHttpClient builder with:
     * ```
     * .addInterceptor(NetworkUtils.errorMappingInterceptor())
     * ```
     */
    fun errorMappingInterceptor(): Interceptor = Interceptor { chain ->
        try {
            chain.proceed(chain.request())
        } catch (e: UnknownHostException) {
            throw NetworkError(NetworkError.Type.NO_INTERNET, "无法解析主机地址，请检查网络连接", e)
        } catch (e: SocketTimeoutException) {
            throw NetworkError(NetworkError.Type.TIMEOUT, "请求超时，请检查网络后重试", e)
        } catch (e: IOException) {
            throw NetworkError(NetworkError.Type.IO_ERROR, "网络错误: ${e.message}", e)
        }
    }

    // ── Error classification ──────────────────────────────────────

    /**
     * Maps an HTTP status code (from an OkHttp [Response]) to a
     * [NetworkError.Type] with a Chinese user-facing message.
     */
    fun classifyHttpError(code: Int): NetworkError {
        return when (code) {
            400 -> NetworkError(NetworkError.Type.HTTP_CLIENT, "请求参数错误 (400)", null)
            401 -> NetworkError(NetworkError.Type.HTTP_AUTH, "认证失败，请检查 API Key (401)", null)
            403 -> NetworkError(NetworkError.Type.HTTP_FORBIDDEN, "访问被拒绝 (403)", null)
            404 -> NetworkError(NetworkError.Type.HTTP_NOT_FOUND, "接口地址不存在 (404)", null)
            429 -> NetworkError(NetworkError.Type.HTTP_RATE_LIMIT, "请求过于频繁，请稍后再试 (429)", null)
            in 500..599 -> NetworkError(NetworkError.Type.HTTP_SERVER, "服务器错误 ($code)，请稍后重试", null)
            else -> NetworkError(NetworkError.Type.HTTP_UNKNOWN, "HTTP 错误 ($code)", null)
        }
    }

    /**
     * Represents a network-layer failure with a typed category and a
     * user-facing message.
     */
    class NetworkError(
        val type: Type,
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause) {

        enum class Type {
            /** Device has no active internet connection. */
            NO_INTERNET,
            /** Request timed out. */
            TIMEOUT,
            /** Low-level IO problem (connection reset, etc.). */
            IO_ERROR,
            /** HTTP 4xx client error. */
            HTTP_CLIENT,
            /** HTTP 401 – bad or missing credentials. */
            HTTP_AUTH,
            /** HTTP 403 – forbidden. */
            HTTP_FORBIDDEN,
            /** HTTP 404 – endpoint not found. */
            HTTP_NOT_FOUND,
            /** HTTP 429 – rate limited. */
            HTTP_RATE_LIMIT,
            /** HTTP 5xx server error. */
            HTTP_SERVER,
            /** Unclassified HTTP or network error. */
            HTTP_UNKNOWN,
        }

        /** True when the error is likely transient and worth retrying. */
        val isRetryable: Boolean
            get() = type in setOf(
                Type.NO_INTERNET,
                Type.TIMEOUT,
                Type.IO_ERROR,
                Type.HTTP_RATE_LIMIT,
                Type.HTTP_SERVER,
            )
    }
}
