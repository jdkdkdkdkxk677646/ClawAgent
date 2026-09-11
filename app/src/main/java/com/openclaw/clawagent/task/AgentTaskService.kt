package com.openclaw.clawagent.task

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openclaw.clawagent.MainActivity
import com.openclaw.clawagent.agent.AgentEvent
import com.openclaw.clawagent.agent.AgentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs one agent turn in a foreground service so the work survives lock
 * screen / app switch (v4.3) — the "dispatch a task, walk away, get pinged
 * when it's done" experience.
 *
 * Handoff: [enqueue] parks a [PendingTask] in a static slot (same process —
 * no serialization, no intent-size limits) and starts the service. The turn
 * executes exactly like the in-app path (same [AgentLoop], same toolbox),
 * and the result is appended to the SAME in-memory conversation tree owned
 * by [ChatRepository] — so the UI, when reopened, simply re-reads the tree.
 *
 * Foreground type `dataSync` keeps Android happy while the turn runs; a
 * progress notification is mandatory and doubles as the task indicator.
 */
class AgentTaskService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "agent_task"
        private const val FOREGROUND_ID = 0xC1A3
        private const val RESULT_ID = 0xC1A4

        /** In-process handoff slot. One task at a time (the tree is shared). */
        @Volatile
        private var pending: PendingTask? = null

        /** Returns false when the platform refused to start the service. */
        fun enqueue(context: Context, task: PendingTask): Boolean {
            pending = task
            return try {
                ContextCompat.startForegroundService(
                    context, Intent(context, AgentTaskService::class.java)
                )
                true
            } catch (e: Exception) {
                pending = null
                false
            }
        }
    }

    /** Everything the service needs to run one turn and file the result. */
    data class PendingTask(
        val request: AgentRequest,
        /** Exact user-visible message already appended by the ViewModel. */
        val userDisplay: String,
        val branchId: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val task = pending ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        pending = null

        ChatRepository.init(this)
        ChatRepository.backgroundTaskRunning = true
        startForeground(FOREGROUND_ID, progressNotification(task.userDisplay))

        scope.launch {
            val outcome = runTurn(task)
            fileResult(task, outcome)
            ChatRepository.backgroundTaskRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** One full agent turn; never throws — every failure becomes a message. */
    private suspend fun runTurn(task: PendingTask): String {
        val content = StringBuilder()
        try {
            ChatRepository.init(this)
            ChatRepository.agentLoop
                .run(task.request)
                .collect { event ->
                    when (event) {
                        is AgentEvent.Delta -> content.append(event.text)
                        is AgentEvent.ToolCalls -> {
                            if (content.isNotEmpty() && !content.endsWith("\n")) content.append("\n\n")
                            event.calls.forEach { call ->
                                content.append("🔧 ${call.name}(${call.arguments})\n")
                            }
                        }
                        is AgentEvent.ToolResult -> content.append("↳ ").append(event.preview).append("\n")
                        is AgentEvent.Usage -> Unit
                        is AgentEvent.RoundLimitReached ->
                            content.append("\n\n⚠️ 已连续调用工具 ${event.rounds} 轮,为避免死循环已停止。")
                        is AgentEvent.Error -> {
                            if (content.isNotEmpty()) content.append("\n\n")
                            content.append("⚠️ ${event.message}")
                        }
                        AgentEvent.Done -> Unit
                    }
                }
        } catch (e: CancellationException) {
            content.append(if (content.isBlank()) "\n⏹ 已停止" else "\n\n⏹ 已停止")
        } catch (e: Exception) {
            content.append(if (content.isBlank()) "" else "\n\n")
            content.append("⚠️ 出错了:${e.message}\n\n请检查网络和服务商配置。")
        }
        return content.toString().ifBlank { "⚠️ 任务没有返回内容。" }
    }

    /** Appends the result to the shared tree, persists, and notifies. */
    private suspend fun fileResult(task: PendingTask, result: String) {
        try {
            val tree = ChatRepository.tree
            tree.switchTo(task.branchId)
            tree.appendMessage("assistant", result)
            ChatRepository.save()
        } catch (_: Exception) {
            // Tree filing is best-effort; the notification still tells the user.
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(
            RESULT_ID,
            resultNotification(result.take(180), result.length)
        )
    }

    // ── notifications ──────────────────────────────────────────────

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Claw 后台任务", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "后台执行的 Agent 任务进度与结果" }
            )
        }
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun progressNotification(userText: String): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("🦀 Claw 正在后台干活…")
            .setContentText(userText.take(80))
            .setOngoing(true)
            .setContentIntent(contentIntent())
            .build()
    }

    private fun resultNotification(preview: String, length: Int): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("✅ 后台任务完成")
            .setContentText(preview.replace('\n', ' '))
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
    }
}
