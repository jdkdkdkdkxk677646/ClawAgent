package com.openclaw.clawagent

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineExceptionHandler
import timber.log.Timber

/**
 * Global error handler for the Claw Agent app.
 *
 * Provides:
 * - [handle] – synchronous entry point for caught exceptions
 * - [coroutineExceptionHandler] – for use as a CoroutineExceptionHandler
 * - [showErrorDialog] – user-facing alert for critical errors
 * - [showToast] – lightweight transient feedback
 *
 * Error categories:
 * | Category | Example | User action |
 * |----------|---------|-------------|
 * | Network | No internet, timeout, 5xx | Check connection, retry |
 * | Auth | Invalid API key, 401 | Check settings |
 * | Parsing | Malformed API response | Report bug |
 * | Cancellation | User stopped generation | Silent (no-op) |
 * | Unknown | Anything else | Report bug |
 */
object ErrorHandler {

    private const val TAG = "ErrorHandler"

    // ── Public API ───────────────────────────────────────────────

    /**
     * Handle a caught exception. Logs it, then routes to the appropriate
     * user-facing channel based on the error type.
     *
     * @param context Android context for toasts / dialogs
     * @param throwable the caught exception
     * @param showDialog whether to show an alert dialog for critical errors
     */
    fun handle(
        context: Context,
        throwable: Throwable,
        showDialog: Boolean = false,
    ) {
        val error = classify(throwable)
        log(error)

        val userMessage = userMessage(error)
        if (showDialog) {
            showErrorDialog(context, userMessage, error.cause)
        } else {
            showToast(context, userMessage)
        }
    }

    /**
     * A [CoroutineExceptionHandler] that forwards uncaught coroutine
     * exceptions to [handle]. Usage:
     *
     * ```
     * val handler = CoroutineExceptionHandler { _, exception ->
     *     ErrorHandler.handle(this, exception, showDialog = true)
     * }
     * ```
     */
    fun coroutineExceptionHandler(
        context: Context,
        showDialog: Boolean = false,
    ): CoroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        handle(context, throwable, showDialog)
    }

    // ── Error classification ─────────────────────────────────────

    private data class ClassifiedError(
        val message: String,
        val category: Category,
        val cause: Throwable?,
    )

    private enum class Category {
        NETWORK,
        AUTH,
        PARSING,
        CANCELLED,
        UNKNOWN,
    }

    private fun classify(throwable: Throwable): ClassifiedError {
        // CancellationException from coroutines – user hit stop, silent.
        if (throwable is kotlinx.coroutines.CancellationException) {
            return ClassifiedError(
                message = "操作已取消",
                category = Category.CANCELLED,
                cause = throwable,
            )
        }

        // NetworkError from our own interceptor / utility.
        if (throwable is NetworkUtils.NetworkError) {
            val msg = when (throwable.type) {
                NetworkUtils.NetworkError.Type.NO_INTERNET -> "网络未连接，请检查网络设置"
                NetworkUtils.NetworkError.Type.TIMEOUT -> "请求超时，请重试"
                NetworkUtils.NetworkError.Type.IO_ERROR -> "网络连接异常: ${throwable.message}"
                NetworkUtils.NetworkError.Type.HTTP_AUTH -> "API Key 无效或已过期，请在设置中更新"
                NetworkUtils.NetworkError.Type.HTTP_RATE_LIMIT -> "请求过于频繁，请稍后再试"
                NetworkUtils.NetworkError.Type.HTTP_SERVER -> "服务器繁忙，请稍后重试"
                else -> "网络错误: ${throwable.message}"
            }
            return ClassifiedError(msg, Category.NETWORK, throwable.cause)
        }

        // Standard IO / networking exceptions.
        if (throwable is UnknownHostException ||
            throwable is java.net.ConnectException ||
            throwable is java.net.SocketException
        ) {
            return ClassifiedError(
                message = "网络连接失败，请检查网络",
                category = Category.NETWORK,
                cause = throwable,
            )
        }
        if (throwable is SocketTimeoutException) {
            return ClassifiedError(
                message = "请求超时，请检查网络后重试",
                category = Category.NETWORK,
                cause = throwable,
            )
        }
        if (throwable is IOException) {
            return ClassifiedError(
                message = "IO 错误: ${throwable.message}",
                category = Category.NETWORK,
                cause = throwable,
            )
        }

        // JSON / parsing errors.
        if (throwable is org.json.JSONException ||
            throwable is javax.xml.transform.TransformerException
        ) {
            return ClassifiedError(
                message = "数据解析失败，请稍后重试",
                category = Category.PARSING,
                cause = throwable,
            )
        }

        // Security / crypto errors.
        if (throwable is SecurityException) {
            return ClassifiedError(
                message = "安全错误: ${throwable.message}",
                category = Category.AUTH,
                cause = throwable,
            )
        }

        // Fallback.
        return ClassifiedError(
            message = throwable.message ?: "未知错误",
            category = Category.UNKNOWN,
            cause = throwable,
        )
    }

    private fun userMessage(error: ClassifiedError): String = error.message

    // ── Logging ──────────────────────────────────────────────────

    private fun log(error: ClassifiedError) {
        when (error.category) {
            Category.CANCELLED -> Timber.d("$TAG: ${error.message}")
            Category.NETWORK -> Timber.w("$TAG: Network error – ${error.message}", error.cause)
            Category.AUTH -> Timber.w("$TAG: Auth error – ${error.message}", error.cause)
            Category.PARSING -> Timber.e("$TAG: Parse error – ${error.message}", error.cause)
            Category.UNKNOWN -> Timber.e(error.cause, "$TAG: Unknown error – ${error.message}")
        }
    }

    // ── User feedback ────────────────────────────────────────────

    /**
     * Show a modal alert dialog for critical errors that block the user.
     */
    fun showErrorDialog(
        context: Context,
        message: String,
        cause: Throwable? = null,
    ) {
        val detail = cause?.let { "\n\n原因: ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        AlertDialog.Builder(context)
            .setTitle("出错了")
            .setMessage(message + detail)
            .setPositiveButton("确定", null)
            .show()
    }

    /**
     * Show a transient toast for non-critical errors (e.g. network hiccup
     * during streaming).
     */
    fun showToast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    // ── Convenience: check network before making a request ────────

    /**
     * Guard helper: returns true when the network is available. If not,
     * shows a toast and returns false so the caller can skip the request.
     */
    fun requireNetwork(context: Context): Boolean {
        return if (NetworkUtils.isNetworkAvailable(context)) {
            true
        } else {
            showToast(context, "网络未连接，请检查网络设置")
            false
        }
    }
}
