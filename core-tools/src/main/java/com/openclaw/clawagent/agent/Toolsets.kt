package com.openclaw.clawagent.agent

import java.io.File

/**
 * Framework-free toolset factory, living in `:core-tools` so it sits next to
 * the tools it assembles. (The old `AgentToolbox.core()` moved here during
 * the v4.0 module split: the registry must not know concrete tools, and this
 * module must not know Android.)
 */
object Toolsets {

    /**
     * Pure-JVM claw: math, clock, notes (+search), web search, http fetch,
     * planning and the JavaScript sandbox ([JsTool]). [notesDir] must be
     * provided (tests pass a temp dir).
     */
    fun core(notesDir: File): AgentToolbox = AgentToolbox(
        listOf(
            CalculatorTool(),
            CurrentTimeTool(),
            NoteTool(notesDir),
            HttpRequestTool(),
            WebSearchTool(),
            PlanTool(),
            JsTool(),
        )
    )
}
