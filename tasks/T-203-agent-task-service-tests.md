# T-203 · AgentTaskService 单元测试(MockWebServer 全链路)

- 难度:⭐⭐⭐
- 状态:todo
- 允许修改的文件(白名单,严格遵守):
  - `app/src/test/java/com/openclaw/clawagent/task/AgentTaskServiceTest.kt`(**新建**)
  - `app/src/main/java/com/openclaw/clawagent/task/AgentTaskService.kt`(允许为可测性做小修)
  - `app/build.gradle.kts`(仅追加 `testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")`)
- 禁改:`ChatRepository.kt`、`ChatViewModel.kt`、`ChatScreen.kt`(并行卡在动)。

## 背景

v4.3 新增的后台任务:发送栏 ☕→🚀 后,`ChatViewModel.sendMessage` 把回合装进 `AgentTaskService.PendingTask(request, userDisplay, branchId)` 经 `AgentTaskService.enqueue()` 移交;服务 `onStartCommand` 里 `startForeground` → `runTurn(task)`(真实 `ChatRepository.agentLoop` 跑完整个 agent 回合)→ `fileResult`(切到目标分支、append assistant 消息、`ChatRepository.save()`、发完成通知)。

**当前 Service 零测试**——它是后台任务的全部执行逻辑,值得钉死。

## 可测性关键(为什么 MockWebServer 能行)

`ChatRepository` 的 `prefs.endpoint` 决定真实请求去向。测试流程:

1. Robolectric 环境 + `ChatRepository.reset(context)`(注意:测试类必须每方法 reset,单例会跨测试残留——参考 `ChatViewModelTest.setUp`);
2. `ChatRepository.prefs.endpoint = mockWebServer.url("/v1/chat/completions").toString()`、`prefs.setApiKey("sk-test")`(非空 key 才走真实 loop 路径,否则 VM 层就转演示模式了——Service 路径无此分支,但保持一致性);
3. MockWebServer 按 AgentLoop 的轮次返回 OpenAI SSE 脚本(参考 `:core-agent` 的 `ChatServiceTest`,里面有现成的跨 chunk SSE 样本与 Streamable 协议无关,普通 HTTP 响应即可):
   - 第一轮返回 `tool_calls`(让 `request.toolset` 真执行一个工具,如 calculator);
   - 第二轮返回最终 Delta + `finish_reason: stop`。
4. `AgentTaskService.enqueue(context, PendingTask(...))` → `robolectric.buildService(AgentTaskService::class.java).get()` 方式驱动,或直接 startService 后 `shadowOf(application).runForegroundServiceTasks()`;若 Service 生命周期难以从测试驱动,**允许的最小 Service 修改**:把 `runTurn`/`fileResult` 的核心逻辑提为 `@VisibleForTesting` 内部方法或伴生纯函数,测试直接调用——提函数时保持 `onStartCommand` 行为不变。
5. 通知断言:`shadowOf nm.allNotifications`,前台通知 id=0xC1A3、完成通知含标题"✅ 后台任务完成"。
6. 落库断言:`ChatRepository.load 后 active 分支`包含 user 消息与 assistant 结果(`tree` 与 storage 均在 ChatRepository 单例上)。

## 测试用例清单(至少)

1. **快乐路径(含工具)**:两轮脚本 → active 分支新增 1 条 assistant 消息,内容含 `🔧 calculator`、`↳`、最终文本;后台标志 `backgroundTaskRunning` 收尾为 false。
2. **传输失败**:mockwebserver 返回 500 → 消息内容含 "⚠️ 出错了";落库与通知照常(降级契约)。
3. **完成通知**:收到两条通知(进行中 1 条 ongoing + 完成 1 条),完成通知文本含结果预览。
4. **enqueue 静态槽**:连续两次 enqueue,第二次前 pending 已被消费(不堆积)。
5. **切分支落库**:PendingTask.branchId 指定非 active 分支,断言结果写进了**那个**分支(`tree.switchTo(branchId)` 语义)。

## 验收标准

- 新测试 ≥5 个,`./gradlew :app:testDebugUnitTest` 全绿,既有用例零回归。
- `AgentTaskService.kt` 若被修改:仅限提函数/可见性,行为 diff 为零;在交付记录里逐条列出修改点。

## 交付记录

(完成后填写:认领人 / commit / 关键决策 / 测试结果)
