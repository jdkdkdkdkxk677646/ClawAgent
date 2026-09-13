# 🗂️ Claw Agent · 多 AI 协作看板

多个 AI 并行开发本项目。领任务 → 干活 → 填表,三个动作。

## 铁律(违反会被拒收)

1. **只改任务卡里"允许修改的文件"白名单内的文件**——这是并行不冲突的全部秘密。新建文件自由(放指定目录),动白名单外的文件 = 返工。
2. **`MainActivity.kt` 是热点文件,只有白名单里明确列出它的卡(当前 T-202)可以改。** 需要接线的地方,在交付记录里写"待接线:xxx",由维护者统一做。
3. **一个任务一个 commit**,message 以 `[T-201]` 这样的任务号开头。
4. **交付前自检**:`./gradlew :app:testDebugUnitTest :core-agent:test :core-tools:test :data:testDebugUnitTest` 全绿;新代码附单元测试;工具类遵循"execute 永不 throw,失败返回错误字符串"的项目约定。
5. **完成动作**:① 代码推到 main(独立 commit);② 在下方看板表格把状态改为 `done` 并填**完成时间**/commit/说明;③ 在任务卡末尾"交付记录"补全。三处都要填。
6. **领任务动作**:把状态改为 `claimed`,填**领取时间**。自第五批起**只记时间、不记名字**(领取时间 = 开始干活的时间;完成时间 = 交付 commit 推送的时间,格式 `2026-09-13 08:10+0800`)。状态是 `claimed` 且超过 24 小时无交付 commit 的,其他 AI 可以改回 `todo` 接手。

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
| T-304 | README / Roadmap 收尾 | ⭐ | done | — | 2026-09-13 10:40+0800 | 7d2f161 | README Roadmap 第三批三项打勾;「Agent 后台任务」版本标注改 v4.3.0;功能清单核对无过时描述 |
| T-305 | v4.3.0 发版准备(CHANGELOG 落版 + 版本号) | ⭐⭐ | done | — | 2026-09-13 10:40+0800 | 7d2f161 | CHANGELOG 新增 `[v4.3.0]` 节(保留 `v4.3.0-alpha.1` 历史);versionCode 15→16 / versionName "4.3.0" |
| T-306 | 消息列表 stableId(可选,依赖真机验收) | ⭐⭐⭐ | todo | — | — | — | — |

**第四批说明**

- Wave 1:**T-304 ‖ T-305** 可并行(README vs CHANGELOG+build.gradle,文件集不相交)。
- Wave 2(独占):**T-306**,吃 `MainActivity.kt` + `ChatViewModel.kt` + `MessageAdapter.kt`;且**可选**——先真机验收 T-301 分页,不抖就不做。
- **非 AI 任务(维护者)**:打 tag `v4.3.0`、配置签名 secrets、真机验收(相机/分页/分享/后台横幅)。详见 T-305 卡。

### 第五批(T-401~405,Agent 能力补强)

> 本批目标:把 Agent 的"能干活的爪子"补到下一代——代码执行沙箱、动态页面渲染、记忆检索升级、工具调用兜底。**自本批起看板只记时间不记名字**:领取时填 `领取时间`,交付时填 `完成时间`。

| 任务号 | 标题 | 难度 | 状态 | 领取时间 | 完成时间 | 交付 commit | 交付摘要 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| T-401 | run_js 代码执行沙箱(Rhino) | ⭐⭐⭐ | done | 2026-09-13 11:30+0800 | 2026-09-13 11:55+0800 | 04d2bd5 | JsTool(Rhino 解释模式 + ClassShutter 禁 Java + 5000 万指令/墙钟双限) + Toolsets 注册 + `JsToolTest` 12 用例;待 T-405 接线 |
| T-402 | http_get 增加 JS 渲染模式 | ⭐⭐⭐ | done | 2026-09-13 12:00+0800 | 2026-09-13 12:20+0800 | — | WebRenderer 接口 + `render_js` 分支(共用净化/截断管线) + AndroidWebRenderer(WebView 主线程 + 三路径 destroy) + 7 用例;待 T-405 接线 |
| T-403 | notes 检索升级(CJK 分词评分) | ⭐⭐ | done | 2026-09-13 12:25+0800 | 2026-09-13 12:45+0800 | — | NoteSearchLogic(拉丁整词 + CJK 二元组/精确 40·25 + 词元加权 + 全命中加成/snippet) + `NoteTool.search` Top5 重写 + 17 用例 |
| T-404 | ToolCallParser 迁移加固 + AgentLoop 兜底接线 | ⭐⭐ | done | 2026-09-13 12:50+0800 | 2026-09-13 13:15+0800 | — | parser 迁入 core-agent + 平衡括号扫描(嵌套可解析)+ 去重;AgentLoop 按 `toolset.names` 过滤的文内调用兜底 + 12 用例 + 2 兜底用例 |
| T-405 | 第五批收尾:AgentWiring 接线 + README/CHANGELOG | ⭐ | todo | — | — | — | — |

**第五批波次(白名单互斥已核对)**

- **Wave 1(四卡全并行)**:T-401(只碰 `core-tools` 的 `JsTool.kt`新/`Toolsets.kt`/`build.gradle.kts` + 测试)‖ T-402(`core-tools` 的 `WebRenderer.kt`新/`HttpTool.kt` + `app` 的 `AndroidWebRenderer.kt`新)‖ T-403(`core-tools` 的 `NoteSearchLogic.kt`新/`NoteTool.kt` + notes 测试)‖ T-404(`core-agent` 的 `ToolCallParser.kt`新/`AgentLoop.kt` + 删 app 旧 parser)。四卡文件集两两不相交。
- **Wave 2(独占)**:T-405——唯一持有 `app/.../agent/AgentWiring.kt` 的卡(统一完成 T-401/T-402 的"待接线"条目),并吃 `README.md` + `CHANGELOG.md`;须等 Wave 1 全部合入。
- 热点文件提示:`AgentWiring.kt` 本批按铁律 2 处理——Wave 1 任何卡都**不改**它,需要接线的写入交付记录,T-405 统一做。

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

## Backlog(待规划,尚未立卡)

> 以下是"之后要做"的候选方向,**尚未立卡**;需要时再切成 T-4xx 任务卡(每卡一份文件白名单)。Roadmap 里的三项已由第三批完成,这里列的是更新一轮的后续。

**发布 / 交付(多为维护者动作,AI 干不了)**
- 配置签名 secrets(`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`)——正式签名 APK 的前提
- 真机回归:相机往返 / 分页手势 / 系统分享 / 后台横幅(CI 测不到的部分)

**质量 / 工程**
- 消息列表 stableId(已立 T-306,可选)
- UI 交互测试升级:引入 Compose `ui-test` 或真机 e2e(Maestro),覆盖目前只能手工的路径
- 覆盖率与边界测试补强;`:app` 单测在 CI 之外的可复现性(本地无 Android SDK 的替代方案)
- 可观测性:崩溃 / 日志(需先评估隐私与依赖体积)

**产品 / 能力**
- 更多 Agent 爪子(日历 / 联系人 / 文件等,受权限与安全约束)——**代码执行、抓取 JS 渲染已立卡:第五批 T-401/T-402**
- MCP 增强:多服务器、更多鉴权方式、SSE 传输
- 会话搜索 / 跨会话检索(笔记检索升级已立卡:第五批 T-403;会话检索尚未立卡)
- 语音输入 / TTS 输出
- 后台任务增强:取消、队列、多任务并行
- 服务商与模型:更多预设 / 本地模型打磨(Ollama 已支持)
- i18n 与无障碍

**文档 / 体验**
- 首次使用引导(权限申请、Agent 模式的解释)
- README / 截图 / 发布说明完善
