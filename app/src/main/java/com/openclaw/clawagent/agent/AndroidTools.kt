package com.openclaw.clawagent.agent

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.app.ActivityManager
import com.openclaw.clawagent.MainActivity
import org.json.JSONObject

/**
 * The claws that reach out of the app and into the device: device state,
 * clipboard, notifications, browser and timed reminders. All of them need
 * the Android framework, so they live apart from the pure-JVM tools and are
 * only instantiated via [AgentToolbox.forAndroid].
 *
 * Shared rule with every tool: `execute` never throws — any failure becomes
 * a model-readable error string so the agent loop keeps running.
 */

// ─── DeviceInfoTool ─────────────────────────────────────────────────────────

/**
 * A snapshot of the device the agent runs on: model, OS version, battery,
 * memory, storage, network transport and screen. Lets the agent answer
 * "我手机还剩多少电 / 内存还够吗" without guessing.
 */
class DeviceInfoTool(private val context: Context) : AgentTool {

    override val name = "device_info"
    override val description =
        "获取设备当前状态:型号、系统版本、电量、充电状态、内存、存储空间、网络类型(Wi-Fi/蜂窝)、屏幕。"
    override val parametersJson = """
        {
          "type": "object",
          "properties": {},
          "required": []
        }
    """.trimIndent()

    override fun execute(arguments: String): String = buildString {
        appendLine("设备型号:${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("系统:Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        battery()?.let { appendLine(it) }
        memory()?.let { appendLine(it) }
        storage()?.let { appendLine(it) }
        network()?.let { appendLine(it) }
        screen()?.let { appendLine(it) }
        append("语言:${java.util.Locale.getDefault().toLanguageTag()}")
    }.trim()

    private fun battery(): String? {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                ?: return null
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct <= 0) null else {
                val charging = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) ==
                    BatteryManager.BATTERY_STATUS_CHARGING
                "电量:$pct%${if (charging) "(充电中)" else ""}"
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun memory(): String? {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return null
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            "内存:可用 ${formatBytes(mi.availMem)} / 共 ${formatBytes(mi.totalMem)}" +
                if (mi.lowMemory) "(系统提示内存不足)" else ""
        } catch (_: Exception) {
            null
        }
    }

    private fun storage(): String? {
        return try {
            val stat = StatFs(context.filesDir.path)
            val avail = stat.availableBytes
            val total = stat.totalBytes
            "应用存储:可用 ${formatBytes(avail)} / 共 ${formatBytes(total)}"
        } catch (_: Exception) {
            null
        }
    }

    private fun network(): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return null
            val network = cm.activeNetwork ?: return "网络:当前无活动连接"
            val caps = cm.getNetworkCapabilities(network) ?: return "网络:当前无活动连接"
            val kind = when {
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝数据"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
                else -> "其他"
            }
            "网络:$kind(${caps.linkDownstreamBandwidthKbps} Kbps 下行)"
        } catch (_: Exception) {
            null
        }
    }

    private fun screen(): String? {
        return try {
            val metrics = context.resources.displayMetrics
            "屏幕:${metrics.widthPixels}×${metrics.heightPixels} px,密度 ${metrics.densityDpi} dpi"
        } catch (_: Exception) {
            null
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
        else -> "$bytes B"
    }
}

// ─── ClipboardTool ──────────────────────────────────────────────────────────

/**
 * Lets the agent read the clipboard ("把剪贴板里的东西总结一下") and write to
 * it ("帮我生成一段文案并复制"). Reading requires the app to be in the
 * foreground on API 29+ — which is always the case while the agent loop runs.
 */
class ClipboardTool(private val context: Context) : AgentTool {

    override val name = "clipboard"
    override val description =
        "读写系统剪贴板。action=read 读取当前剪贴板文本;action=write 写入 text 内容(相当于帮用户复制)。"
    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["read", "write"],
              "description": "read 读取剪贴板,write 写入剪贴板"
            },
            "text": {
              "type": "string",
              "description": "要写入的文本(action=write 时必填)"
            }
          },
          "required": ["action"]
        }
    """.trimIndent()

    override fun execute(arguments: String): String {
        val args = try {
            JSONObject(arguments)
        } catch (_: Exception) {
            return "错误:参数不是合法 JSON。"
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return "错误:剪贴板服务不可用。"
        return when (args.optString("action", "").trim().lowercase()) {
            "read" -> try {
                val clip = cm.primaryClip
                if (clip == null || clip.itemCount == 0) {
                    "剪贴板当前为空。"
                } else {
                    val text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
                    if (text.isEmpty()) "剪贴板当前为空。" else "剪贴板内容:\n$text"
                }
            } catch (e: Exception) {
                "读取剪贴板失败:${e.message}(部分系统限制后台读取,需应用在前台)"
            }

            "write" -> {
                val text = args.optString("text", "")
                if (text.isEmpty()) return "错误:write 需要 text 参数。"
                cm.setPrimaryClip(ClipData.newPlainText("Claw Agent", text))
                "已把 ${text.length} 个字符写入剪贴板。"
            }

            else -> "错误:未知 action,支持 read / write。"
        }
    }
}

// ─── OpenUrlTool ────────────────────────────────────────────────────────────

/**
 * Opens a URL in the user's browser (or any matching handler). Pairs well
 * with http_get: fetch facts with one, show the source page with the other.
 */
class OpenUrlTool(private val context: Context) : AgentTool {

    override val name = "open_url"
    override val description =
        "在用户的浏览器中打开一个网址(跳转页面)。当用户要求打开某个网页、查看来源链接时使用。" +
            "参数:url(必填,http/https)。"
    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "url": {
              "type": "string",
              "description": "要打开的完整 URL,以 http:// 或 https:// 开头"
            }
          },
          "required": ["url"]
        }
    """.trimIndent()

    override fun execute(arguments: String): String {
        val raw = try {
            JSONObject(arguments).optString("url", "")
        } catch (_: Exception) {
            return "错误:参数不是合法 JSON。"
        }
        val url = HttpToolLogic.validateUrl(raw)
            ?: return "错误:URL 不合法(仅支持 http/https)。收到:\"$raw\""
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "已在浏览器打开:$url"
        } catch (e: Exception) {
            "打开失败:${e.message}(设备上可能没有可用的浏览器)"
        }
    }
}

// ─── NotificationTool / ReminderTool ────────────────────────────────────────

/** Shared plumbing for posting agent notifications. */
internal object AgentNotifications {
    const val CHANNEL_ID = "claw_agent_alerts"
    const val REQUEST_CODE = 0xC1A2

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Claw Agent 提醒",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Claw Agent 的通知与提醒" }
            )
        }
    }

    fun permissionGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun post(context: Context, title: String, message: String): String {
        if (!permissionGranted(context)) {
            return "发送通知失败:缺少通知权限。请用户在系统设置 → 应用 → Claw Agent 中授予通知权限(API 33+ 必需)。"
        }
        ensureChannel(context)
        val tapIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title.ifBlank { "Claw Agent" })
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(tapIntent)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
        return "已发送通知「$title」。"
    }
}

/**
 * Posts a system notification — the agent can surface an urgent finding
 * ("查到了,价格降到 X 以下了") without waiting for the user to read the chat.
 */
class NotificationTool(private val context: Context) : AgentTool {

    override val name = "notify"
    override val description =
        "向用户发送一条 Android 系统通知。适合在完成耗时任务后提醒用户,或展示重要结果。" +
            "参数:title(通知标题)、message(通知正文)。"
    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "title": {
              "type": "string",
              "description": "通知标题,一句话"
            },
            "message": {
              "type": "string",
              "description": "通知正文"
            }
          },
          "required": ["title", "message"]
        }
    """.trimIndent()

    override fun execute(arguments: String): String {
        val args = try {
            JSONObject(arguments)
        } catch (_: Exception) {
            return "错误:参数不是合法 JSON。"
        }
        val title = args.optString("title", "").trim()
        val message = args.optString("message", "").trim()
        if (title.isEmpty() || message.isEmpty()) return "错误:title 和 message 都是必填项。"
        return try {
            AgentNotifications.post(context, title, message.take(400))
        } catch (e: Exception) {
            "发送通知失败:${e.message}"
        }
    }
}

/**
 * Schedules a one-shot reminder via AlarmManager (`setAndAllowWhileIdle`:
 * fires within a few minutes even in Doze, no special permission needed).
 * Delivery is a broadcast to [ReminderReceiver], which posts the
 * notification — so the alarm fires even if the app process was killed in
 * the meantime. Two honest limits, stated in the tool's result: reboots
 * clear alarms (persisted re-scheduling needs a BootReceiver), and exact
 * timing is ± a few minutes.
 */
class ReminderTool(private val context: Context) : AgentTool {

    override val name = "remind"
    override val description =
        "设置一个延时提醒:N 秒后发送系统通知(基于系统闹钟,应用进程被杀也能触发,精确到分钟级)。" +
            "参数:delay_seconds(1~86400)、message(提醒内容)。适合\"10 分钟后提醒我\"这类请求。"
    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "delay_seconds": {
              "type": "integer",
              "description": "多少秒后提醒,范围 1~86400"
            },
            "message": {
              "type": "string",
              "description": "提醒内容"
            }
          },
          "required": ["delay_seconds", "message"]
        }
    """.trimIndent()

    override fun execute(arguments: String): String {
        val args = try {
            JSONObject(arguments)
        } catch (_: Exception) {
            return "错误:参数不是合法 JSON。"
        }
        val seconds = args.optInt("delay_seconds", -1)
        val message = args.optString("message", "").trim()
        if (seconds < 1 || seconds > MAX_SECONDS) {
            return "错误:delay_seconds 必须在 1~$MAX_SECONDS 秒之间。"
        }
        if (message.isEmpty()) return "错误:缺少 message 参数。"

        return try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return "错误:闹钟服务不可用。"
            val requestCode = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
            val pi = PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, ReminderReceiver::class.java)
                    .putExtra(ReminderReceiver.EXTRA_MESSAGE, message),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + seconds * 1000L,
                pi,
            )
            val human = if (seconds % 60 == 0) "${seconds / 60} 分钟" else "$seconds 秒"
            "已设置提醒:${human}后提醒「$message」(系统级闹钟,应用被杀也会响;" +
                "重启手机后未触发的提醒会丢失)"
        } catch (e: Exception) {
            "设置提醒失败:${e.message}"
        }
    }

    companion object {
        const val MAX_SECONDS = 86_400
    }
}

/**
 * Delivers a scheduled reminder: receives the alarm broadcast and posts the
 * notification. Runs outside any activity — must not touch UI, only
 * [AgentNotifications].
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val message = intent.getStringExtra(EXTRA_MESSAGE)?.take(400) ?: return
        runCatching {
            AgentNotifications.post(context.applicationContext, "⏰ 提醒", message)
        }
    }

    companion object {
        const val EXTRA_MESSAGE = "claw_remind_message"
    }
}
