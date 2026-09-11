package com.openclaw.clawagent.agent

/**
 * A single claw of the agent: one callable capability the model may invoke
 * via OpenAI-compatible function calling (`tools` request parameter).
 *
 * Keeping tool implementations free of Android imports wherever possible
 * means every tool is unit-testable on the plain JVM; tools that must touch
 * the framework (notifications, clipboard, ...) take a [android.content.Context]
 * in their constructor and are only instantiated in the app runtime.
 *
 * Wire contract: the model answers with `tool_calls` naming one of the tools
 * plus a JSON `arguments` string; the app executes the tool locally and sends
 * the result back as a `role:"tool"` message. See MainActivity's agent loop.
 */
interface AgentTool {
    /** Function name the model sees, e.g. "calculator". */
    val name: String

    /** Human/model-readable description of when to use this tool. */
    val description: String

    /** JSON Schema (as a string) describing the `arguments` object. */
    val parametersJson: String

    /**
     * Execute the tool. Must never throw — return a model-readable error
     * string instead, so a bad tool call degrades into an answer rather
     * than crashing the agent loop.
     */
    fun execute(arguments: String): String
}
