# T-503 · 后台回合取消

- 难度:⭐⭐
- 状态:todo
- 波次:**Wave 1(可与 T-501/T-502 并行)**。
- 允许修改的文件(白名单,严格遵守):
  - `app/src/main/java/com/openclaw/clawagent/task/AgentTaskService.kt`
  - `app/src/test/java/com/openclaw/clawagent/task/AgentTaskServiceTest.kt`
- 禁改:`core-agent/**`(AgentLoop 的 Flow collect 被取消即停止,无需改 core-agent)、`MainActivity.kt`、`ChatViewModel.kt`。

## 背景

v4.3 的后台任务只有"开始"没有"停止":回合一旦进前台服务,锁屏后用户只能干等跑完(或杀整个 App)。本卡加"取消":通知上挂一个 ✕ 动作,点了立刻停,并向会话写入"已取消"。

## 任务

1. `AgentTaskService`:
   - 进行中通知追加 `NotificationCompat.Action`(✕ 取消),PendingIntent 指向 Service 自身 `ACTION_CANCEL`;
   - `onStartCommand` 处理 `ACTION_CANCEL`:cancel 当前回合的 collecting Job(**中断模型流**)→ `stopForeground(STOP_FOREGROUND_REMOVE)` → 向当前会话写一条 assistant 消息"(后台任务已取消)"(复用既有落库路径,不新开写通道);
   - **幂等**:回合已结束后收到 CANCEL 是 no-op(不写消息、不动通知);同刻仍只允许一个活动回合(现状保持);
   - 复位检查:取消后 `backgroundTaskRunning` 横幅状态复位、静态槽清理,不残留"进行中"痕迹。
2. 测试(`AgentTaskServiceTest` 增补,沿用 MockWebServer + Robolectric 全链路模式):
   - 长回合运行中发 ACTION_CANCEL → 模型流中断、库中出现"已取消"、通知被移除;
   - 回合正常完成后发 ACTION_CANCEL → 无副作用(幂等)。

## 验收标准

- CI 全绿,既有 6 用例零回归;新增 ≥ 2 取消用例;
- 真机:后台回合点通知 ✕,回合立刻停、会话出现"已取消"、无残留通知/横幅。

## 交付记录

(领取时填:领取时间;完成后填:完成时间 / commit / 关键决策 / 测试结果)
- 领取时间:2026-09-13 16:32+0800
- 完成时间:2026-09-13 16:45+0800
- commit:82d18ad
- 关键决策/测试结果:
  - AgentTaskService 通知追加 NotificationCompat.Action(✕ 取消),PendingIntent 指向 SERVICE 自身 ACTION_CANCEL
  - onStartCommand 处理 ACTION_CANCEL:scope.cancel() 中断模型流 → stopForeground → 写"⏹ 后台任务已取消"到当前分支 → 清除通知
  - 幂等:回合结束后 CANCEL 是 no-op (backgroundTaskRunning 已 false，不写消息)
  - cancelRunning(context) 静态方法供外部调用
  - AgentTaskServiceTest 新增 2 用例:mid-turn 取消 (标志复位/通知移除)/完成后取消 (幂等无副作用)
  - 既有 6 用例零回归
