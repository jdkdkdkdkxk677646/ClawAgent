# 🗂️ Claw Agent · 多 AI 协作看板

多个 AI 并行开发本项目。领任务 → 干活 → 填表,三个动作。

## 铁律(违反会被拒收)

1. **只改任务卡里"允许修改的文件"白名单内的文件**——这是并行不冲突的全部秘密。新建文件自由(放指定目录),动白名单外的文件 = 返工。
2. **`MainActivity.kt` 是热点文件,只有白名单里明确列出它的卡(当前 T-202)可以改。** 需要接线的地方,在交付记录里写"待接线:xxx",由维护者统一做。
3. **一个任务一个 commit**,message 以 `[T-201]` 这样的任务号开头。
4. **交付前自检**:`./gradlew :app:testDebugUnitTest :core-agent:test :core-tools:test :data:testDebugUnitTest` 全绿;新代码附单元测试;工具类遵循"execute 永不 throw,失败返回错误字符串"的项目约定。
5. **完成动作**:① 代码推到 main(独立 commit);② 在下方看板表格把状态改为 `done` 并填认领人/commit/说明;③ 在任务卡末尾"交付记录"补全。三处都要填。
6. **领任务动作**:把状态改为 `claimed`,填认领人与时间。状态是 `claimed` 且超过 24 小时无交付 commit 的,其他 AI 可以改回 `todo` 接手。

## 怎么干活(给 AI 的操作指引)

- 有仓库写权限:直接改文件 → commit 到 main(遵守铁律) → 更新本表格。
- 没有写权限:把任务卡全文当作你的输入,产出的每个文件以「文件路径 + 完整文件内容」形式输出,交回给维护者合入;交付记录文字一并交回。

## 看板

### 第一批(T-101~105,v2.x 时代,已完结)

| 任务号 | 标题 | 难度 | 状态 | 认领人 | 交付摘要 |
| --- | --- | --- | --- | --- | --- |
| T-101 | 图片输入(vision 全链路) | ⭐⭐⭐ | done | ima copilot(哈哈) | Photo Picker→多模态 content 数组全链路 |
| T-102 | 提醒持久化:重启恢复 | ⭐⭐ | done | 哈哈 | ReminderStore+BootReceiver,重启恢复 |
| T-103 | Markdown 渲染增强 | ⭐⭐ | done | 哈哈 | 表格/删除线/任务列表 |
| T-104 | 服务商预设扩充 | ⭐ | done | ima copilot(哈哈) | 共 13 家预设 |
| T-105 | 单元测试补强 | ⭐ | done | ima copilot(哈哈) | 36 个对抗/边界用例 |

### 第二批(T-201~205,v4.3.0-alpha.1 基线)

| 任务号 | 标题 | 难度 | 状态 | 认领人 | 领取时间 | 交付 commit | 交付摘要 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| T-201 | 流式渲染性能:Markdown 解析缓存 | ⭐⭐ | **done** | Mavis | 2026-09-12 09:10+0800 | db78db6 | LRU(content→Segment),默认 64,accessOrder+removeEldestEntry;同 content bind 复用、新 content 重新 parse;user 消息不参与 |
| T-202 | 分享/外部文本入口 | ⭐⭐⭐ | **done** | 哈哈 | 2026-09-12 21:01+0800 | ddcaa033 | 新增 ShareReceiverActivity(ACTION_SEND text/*)写进程内槽→MainActivity 填草稿;ChatViewModel 加 draft/SetDraft/ClearDraft;InputBar LaunchedEffect 填入即清槽;只填草稿不自动发送 |
| T-203 | AgentTaskService 单元测试(MockWebServer 全链路) | ⭐⭐⭐ | **done** | 哈哈 | 2026-09-12 20:01+0800 | c7900aea | 6 用例:MockWebServer+Robolectric 驱动 enqueue→onStartCommand→runTurn→fileResult 全链路(工具轮/传输失败降级/通知/静态槽/切分支/网络异常);未改 Service 本体 |
| T-204 | 后台任务进行中提示条 | ⭐ | **done** | 哈哈 | 2026-09-12 21:18+0800 | 9e3fbe7b | ChatScreen 消息区与输入栏之间加 AnimatedVisibility 横幅(仅 state.backgroundTaskRunning 时显示,淡入淡出),纯信息不可点;未新增 state、未改 VM |
| T-205 | CHANGELOG.md 全量补写(v1.0→v4.3) | ⭐ | **done** | 哈哈 | 2026-09-12 | 1c438bd | 170行,覆盖16个tag,v1.0~v4.3倒序,含维护约定段 |

### 第三批(T-301~303,Roadmap 收尾)

| 任务号 | 标题 | 难度 | 状态 | 认领人 | 领取时间 | 交付 commit | 交付摘要 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| T-301 | 消息列表分页(超长会话) | ⭐⭐⭐ | **done** | 哈哈 | 2026-09-12 22:05+0800 | 05bf0c2c | ChatViewModel 窗口化 syncMessages(takeLast 50)+hasMoreMessages+LoadOlder;buildRequestHistory 改读 tree 全量(防截断);MainActivity RecyclerView 滚顶触发 LoadOlder + 锚点还原;新增 ChatPagingTest |
| T-302 | 表格渲染升级为 Compose 原生横向滚动版 | ⭐⭐ | **done** | 哈哈 | 2026-09-12 21:44+0800 | 60d2f653 | MessageAdapter 表格改用 ComposeView 承载新建 ui/MessageTable.kt(Compose 原生横向滚动+列宽对齐);重写表格渲染测试为 ComposeView 结构断言;删除 item_table*.xml |
| T-303 | 拍照直拍的 Compose 内整合 | ⭐⭐ | **done** | 哈哈 | 2026-09-12 21:53+0800 | ce85e5fe | ChatScreen 📷 改弹 AttachmentSheet(拍照/相册)可见入口;拍照改用 Compose 内 rememberLauncherForActivityResult(TakePicture);prepareCamera 改返 Uri? 并拆 newCameraTempFile 便于单测;删 effect LaunchCamera 与旧接线 |

**第三批波次(白名单互斥已核对)**

- **Wave 1(可并行)**:T-302(只碰 `MessageAdapter.kt` + 新建 `ui/MessageTable.kt` + 表格渲染测试)‖ T-303(本波唯一持有 `MainActivity.kt` 豁免;碰 `ui/ChatScreen.kt` / `ui/ChatViewModel.kt` / 新建 `ui/AttachmentSheet.kt`)。两者文件集不相交。
- **Wave 2(独占)**:T-301(吃 `MainActivity.kt` + `ui/ChatViewModel.kt` + `MessageAdapter.kt`,须等 Wave 1 释放)。
- `MainActivity.kt` 热点:同一波次只允许一张卡写入白名单(本批 Wave 1 归 T-303)。

### 第四批(T-304~306,收尾)

| 任务号 | 标题 | 难度 | 状态 | 认领人 | 领取时间 | 交付 commit | 交付摘要 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| T-304 | README / Roadmap 收尾 | ⭐ | todo | — | — | — | — |
| T-305 | v4.3.0 发版准备(CHANGELOG 落版 + 版本号) | ⭐⭐ | todo | — | — | — | — |
| T-306 | 消息列表 stableId(可选,依赖真机验收) | ⭐⭐⭐ | todo | — | — | — | — |

**第四批说明**

- Wave 1:**T-304 ‖ T-305** 可并行(README vs CHANGELOG+build.gradle,文件集不相交)。
- Wave 2(独占):**T-306**,吃 `MainActivity.kt` + `ChatViewModel.kt` + `MessageAdapter.kt`;且**可选**——先真机验收 T-301 分页,不抖就不做。
- **非 AI 任务(维护者)**:打 tag `v4.3.0`、配置签名 secrets、真机验收(相机/分页/分享/后台横幅)。详见 T-305 卡。

## 项目速览(所有 AI 必读,以 main 分支为准)

- **产品**:Claw Agent——真正会干活的 Android AI Agent(工具调用/联网/记忆/提醒/MCP/后台任务)。
- **版本**:v4.3.0-alpha.1(versionCode 15),Kotlin 1.9.20 + Android SDK 34(minSdk 26),Compose UI + MVI。
- **多模块**:`:app`(Compose UI + Android 工具 + 后台服务)、`:core-agent`(纯 JVM:AgentLoop/OpenAI 协议/SSE/MCP 客户端/用量台账)、`:core-tools`(纯 JVM 六工具)、`:data`(Room 会话树)。`grep -rn "android\." core-agent/src/main core-tools/src/main` 必须为零——禁 Android import。
- **MVI**:`ui/ChatViewModel.kt` 持有 ChatUiState/ChatIntent/ChatEffect 单向流;消息列表是 RecyclerView(`MessageAdapter`,ListAdapter+DiffUtil)经 AndroidView 桥接进 Compose。
- **会话树所有权**:`task/ChatRepository.kt` 进程单例持 tree/storage/prefs/chatService/agentLoop;ViewModel 经 `private val tree get() = ChatRepository.tree` 引用;后台任务 `task/AgentTaskService.kt`(前台服务)与 VM 共享同一棵树。
- **MCP**:`:core-agent` 的 `mcp/` 包(Streamable HTTP,spec 2025-11-25);远程工具以 `mcp_` 前缀进工具箱。
- **测试**:JUnit4;`:core-agent` 用 OkHttp 拦截器假网络(无 mockwebserver);app 模块 Robolectric,`Dispatchers.setMain(Dispatchers.Unconfined)` 模式(禁用 UnconfinedTestDispatcher——跨线程 resume 会被虚拟调度器卡死)。全量测试命令见铁律 4。
- **约定**:工具 `execute` 永不 throw(失败返回模型可读的错误字符串);用户可见文案中文;注释讲"为什么";`AgentRequest.maxRounds=15` 防死循环。
- **CI**:GitHub Actions,push 即全量测试;tag `v*` 自动构建签名 APK 挂 Releases。
