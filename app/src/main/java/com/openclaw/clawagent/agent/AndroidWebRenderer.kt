package com.openclaw.clawagent.agent

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [WebRenderer] backed by the platform [WebView]: loads a page, lets its
 * JavaScript run, and returns the settled DOM as HTML — the agent's eyes on
 * client-rendered (SPA) pages.
 *
 * A WebView may only be created, driven and destroyed on the main thread, so
 * every step is posted to the main looper while the caller blocks on a
 * [CountDownLatch]. The caller (the fetch tool) runs on a background
 * dispatcher, never on the main thread, so blocking is safe.
 *
 * Hygiene: images are blocked (saves bandwidth), file/content access is off,
 * pop-ups are off, and the WebView is always destroyed — on success, on
 * failure and on timeout — so nothing leaks across calls.
 */
class AndroidWebRenderer(context: Context) : WebRenderer {

    private val appContext: Context = context.applicationContext

    override fun render(url: String, timeoutMs: Long): String {
        val latch = CountDownLatch(1)
        val html = arrayOfNulls<String>(1)
        val failure = arrayOfNulls<Throwable>(1)
        val viewRef = arrayOfNulls<WebView>(1)
        val main = Handler(Looper.getMainLooper())

        val teardown = Runnable {
            viewRef[0]?.let { view ->
                viewRef[0] = null
                try {
                    view.stopLoading()
                    view.destroy()
                } catch (_: Throwable) {
                    // Best effort: a WebView that refuses to die must not mask
                    // the real outcome.
                }
            }
        }

        main.post {
            try {
                val view = WebView(appContext)
                viewRef[0] = view
                configure(view.settings)
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, loadedUrl: String) {
                        // Give XHR/fetch a beat to land before snapshotting.
                        main.postDelayed({
                            try {
                                view.evaluateJavascript("document.documentElement.outerHTML") { value ->
                                    html[0] = decodeJsStringLiteral(value)
                                    latch.countDown()
                                    main.post(teardown)
                                }
                            } catch (t: Throwable) {
                                failure[0] = t
                                latch.countDown()
                                main.post(teardown)
                            }
                        }, SETTLE_DELAY_MS)
                    }
                }
                view.loadUrl(url)
            } catch (t: Throwable) {
                failure[0] = t
                latch.countDown()
                main.post(teardown)
            }
        }

        val finished = try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            main.post(teardown) // page may still be loading — kill it.
            throw IllegalStateException("页面渲染超时(超过 ${timeoutMs}ms)")
        }
        failure[0]?.let { throw IllegalStateException(it.message ?: it.javaClass.simpleName) }
        return html[0] ?: ""
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(settings: WebSettings) {
        with(settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            blockNetworkImage = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
        }
    }

    /** `evaluateJavascript` hands back a JSON string literal (quoted/escaped). */
    private fun decodeJsStringLiteral(raw: String?): String {
        if (raw.isNullOrEmpty() || raw == "null") return ""
        return try {
            val parsed = JSONTokener(raw).nextValue()
            if (parsed is String) parsed else raw
        } catch (_: Exception) {
            raw
        }
    }

    private companion object {
        const val SETTLE_DELAY_MS = 1500L
    }
}
