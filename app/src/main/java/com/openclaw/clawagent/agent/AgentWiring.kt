package com.openclaw.clawagent.agent

import android.content.Context
import java.io.File

/**
 * Android-side toolset factory — the only place that knows both the registry
 * and the framework-bound tools. (Moved out of `AgentToolbox` during the
 * v4.0 module split: `:core-agent` must stay framework-free.)
 */
object AgentWiring {

    /** The full claw: core tools + everything that needs the framework. */
    fun forAndroid(context: Context): AgentToolbox = AgentToolbox(
        listOf(
            CalculatorTool(),
            CurrentTimeTool(),
            NoteTool(File(context.filesDir, "agent_notes")),
            HttpRequestTool(),
            WebSearchTool(),
            PlanTool(),
            DeviceInfoTool(context.applicationContext),
            ClipboardTool(context.applicationContext),
            NotificationTool(context.applicationContext),
            OpenUrlTool(context.applicationContext),
            ReminderTool(context.applicationContext),
        )
    )
}
