package com.openclaw.clawagent.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 手机重启后恢复提醒：AlarmManager 的 alarm 在重启时会被系统清空,
 * 这里读取 [ReminderStore] 的持久化记录,把"重启时还没到点"的提醒
 * 重新挂回 AlarmManager;已过期的直接补发一条「错过的提醒」通知并从
 * Store 删除。
 *
 * goAsync():重排要读写 SharedPreferences 并重建多个 PendingIntent,
 * 超出 onReceive 的 10 秒窗口时广播进程可能被杀,所以用异步延命。
 * 用 [AlarmManager.setAndAllowWhileIdle] 非精确即可——提醒本就是
 * 分钟级语义,无需 setExactAndAllowWhileIdle 的特权要求。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        try {
            val app = context.applicationContext
            val store = ReminderStore.forContext(app)
            val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            val now = System.currentTimeMillis()

            if (am != null) {
                for (entry in store.listPending(now)) {
                    runCatching {
                        val pi = PendingIntent.getBroadcast(
                            app,
                            entry.requestCode,
                            Intent(app, ReminderReceiver::class.java).apply {
                                putExtra(ReminderReceiver.EXTRA_MESSAGE, entry.message)
                                putExtra(ReminderReceiver.EXTRA_REQUEST_CODE, entry.requestCode)
                                putExtra(ReminderReceiver.EXTRA_TRIGGER_AT, entry.triggerAtMillis)
                            },
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, entry.triggerAtMillis, pi)
                    }
                }
            }

            for (entry in store.listExpired(now)) {
                runCatching {
                    AgentNotifications.post(app, "⏰ 错过的提醒", entry.message)
                }
                store.remove(entry.requestCode)
            }
        } finally {
            pendingResult.finish()
        }
    }
}
