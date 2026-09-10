# T-102 提醒持久化:重启恢复

难度 ⭐⭐ | 状态看 `tasks/BOARD.md`

## 背景

`remind` 工具已用 `AlarmManager.setAndAllowWhileIdle` + `ReminderReceiver` 广播发通知(见 `agent/AndroidTools.kt` 末尾),进程被杀也能响。**剩余短板:手机重启后 alarm 全部丢失**。本任务补上持久化与开机重排,让提醒彻底可靠。

## 允许修改的文件(白名单)

- `app/src/main/java/com/openclaw/clawagent/agent/AndroidTools.kt`(**仅限** ReminderTool / ReminderReceiver 及文件末尾新增代码;其他工具一行不许动)
- 新文件:`app/src/main/java/com/openclaw/clawagent/agent/ReminderStore.kt`(纯 JVM 可测,注入 SharedPreferences 或文件)
- 新文件:`app/src/main/java/com/openclaw/clawagent/agent/BootReceiver.kt`
- `app/src/main/AndroidManifest.xml`(只允许:加 `RECEIVE_BOOT_COMPLETED` 权限 + BootReceiver 声明)
- 新测试文件:`app/src/test/java/com/openclaw/clawagent/agent/**`

## 明确禁止

- 改 `MainActivity.kt` / `MessageAdapter.kt` / 其他工具类
- 改 `ReminderTool.execute` 的对外行为(参数、返回文案结构保持兼容,文案可在括号内补充)

## 需求

1. `ReminderStore`(纯 Kotlin,依赖注入 `SharedPreferences` 或 `File`):登记 `{requestCode, triggerAtMillis, message}`,标记完成/删除,列出未来提醒;序列化用 JSON 数组存 SharedPreferences 或每条一文件的 JSON,自选,写明理由。
2. `ReminderTool` 每次成功 `setAndAllowWhileIdle` 后登记;`ReminderReceiver` 触发后从 Store 删除该条。
3. `BootReceiver`:接收 `BOOT_COMPLETED`,`goAsync()` 中重排所有 `triggerAt > now` 的提醒(重建 PendingIntent + alarm),已过期的直接补发一条通知「错过的提醒:<message>」然后删除。用 `setAndAllowWhileIdle`,非精确即可。
4. Manifest:`RECEIVE_BOOT_COMPLETED` 权限;BootReceiver `exported="false"` 带 BOOT_COMPLETED intent-filter。
5. 单元测试:ReminderStore 的登记/删除/列出 round-trip(注入内存 SharedPreferences 用 Robolectric,或纯文件实现用 JUnit 临时目录);重排逻辑的纯函数部分抽出来测。

## 验收标准

- `./gradlew testDebugUnitTest` 全绿
- 设置提醒 → `adb shell am force-stop` 杀进程 → 到点仍然响
- 重启后(Robolectric 模拟 BOOT_COMPLETED 广播)未触发提醒被重排、过期提醒补发通知
- 现有其他工具行为零变化

## 交付记录(AI 完成后填)

- 认领人:
- 完成时间:
- 改动文件清单:
- 实现要点(3~5 行):
- 测试结果:
- 遗留问题/待接线:
