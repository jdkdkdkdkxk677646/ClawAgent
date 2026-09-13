package com.openclaw.clawagent.agent

/**
 * Pluggable "browser engine" for [HttpRequestTool]'s opt-in `render_js` mode.
 *
 * The interface lives in `:core-tools` (which must stay free of any `android.*`
 * import); the platform WebView implementation lives in `:app`
 * (`AndroidWebRenderer`). Keeping the contract here lets the fetch tool stay
 * framework-free and lets tests drive the render branch with a trivial fake.
 *
 * Contract:
 *  - [render] loads [url], lets its JavaScript run to completion, and returns
 *    the *settled* HTML (`document.documentElement.outerHTML`).
 *  - [timeoutMs] is the caller's wall-clock budget (page load + settle); an
 *    implementation should give up on its own within that budget.
 *  - On any failure throw an exception whose [Throwable.message] is
 *    human-readable — it is surfaced verbatim to the model.
 */
interface WebRenderer {

    @Throws(Exception::class)
    fun render(url: String, timeoutMs: Long): String
}
