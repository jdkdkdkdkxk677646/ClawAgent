# Changelog

All notable changes to this project will be documented in this file.

本文件按 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 风格维护。发版时把 `## [Unreleased]` 落为新版本节，并移除 `## [Unreleased]`。版本号与 git tag 严格对应，不杜撰中间版本。

---

## [v4.3.0] - 2026-09-13

### Added

- **Agent 后台任务**：发送栏 ☕→🚀 切换后台执行，回合跑在前台服务里，锁屏/切走不中断，完成推送通知，结果自动写回会话。`AgentTaskService` 前台服务 + 多轮 AgentLoop 循环，后台与主会话共享同一会话树。
- **消息列表分页**：超长会话窗口化渲染（`takeLast 50`），滚顶自动加载更早消息（`LoadOlder`），并做锚点还原，避免位置跳动。
- **表格渲染升级为 Compose 原生**：横向滚动 + 列宽对齐，替换旧 XML 表格。
- **拍照直拍的 Compose 内整合**：📷 入口改为 `AttachmentSheet`（拍照 / 相册）可见入口，拍照走 Compose `rememberLauncherForActivityResult(TakePicture)`。
- **系统分享入口**：`ShareReceiverActivity` 接收 `ACTION_SEND` 文本 → 填入输入框草稿（只填不自动发送）。
- **后台任务进行中提示条**：消息区与输入栏之间的淡入淡出横幅，仅后台回合运行时显示。

### Fixed

- Robolectric 测试中会话树泄漏：`reset()` 重新绑定状态，避免跨测试污染。
- 流式输出气泡不更新：`notifyChanged` 每轮从 live object 重建尾部。

---

## [v4.3.0-alpha.1] - 2026-09-12

### Added

- **Agent 后台任务**：发送栏 ☕→🚀 切换后台执行，回合跑在前台服务里，锁屏/切走不中断，完成推送通知，结果自动写回会话。`AgentTaskService` 前台服务 + 多轮 AgentLoop 循环，后台与主会话共享同一会话树。

### Fixed

- Robolectric 测试中会话树泄漏：`reset()` 重新绑定状态，避免跨测试污染。

---

## [v4.2.0] - 2026-09-12

### Added

- **MCP（Model Context Protocol）接入**：Streamable HTTP 客户端，远程 MCP 服务器的工具以 `mcp_` 前缀挂入爪子集，受工具开关约束；设置页可配端点与 Bearer Token。13 家服务商预设同步扩充。

---

## [v4.2.0-alpha.1] - 2026-09-12

### Added

- MCP 客户端原型：支持远程 MCP 服务器工具注入工具集。

### Fixed

- 去掉瞬态 `isSending` assert：inline Unconfined completion 使该断言在特定时序下不可靠，改为轮询最终快照。

---

## [v4.1.1] - 2026-09-11

### Fixed

- 流式输出气泡不更新：`notifyChanged` 必须在每轮重新从 live object 重建尾部，修复流式气泡永远停滞的 bug。

---

## [v4.1.0] - 2026-09-11

### Added

- **存储层全 suspend**：DAO 挂 Room 事务执行器，主线程零 SQLite 调用；**设置对话框迁 Compose**；**今日 token 用量 UI** 展示；消息列表 **DiffUtil 增量刷新**，避免全量重排。

---

## [v4.0.0] - 2026-09-11

### Changed

- **v4.0 绞杀式重写完成**，架构重构为四模块：`app`（Compose UI + MVI）、`core-agent`（纯 JVM AgentLoop/SSE/MCP）、`core-tools`（纯 JVM 六工具）、`data`（Room 会话树）。Agent 循环可 100% 纯 JVM 单元测试，依赖方向清晰，禁止 `core-agent`/`core-tools` 引入 Android import。

---

## [v4.0.0-alpha.3] - 2026-09-11

### Fixed

- 表情文字按钮替代 Material Icons（`AutoMirrored Stop` 无法解析）；修复 Compose 编译问题。

---

## [v4.0.0-alpha.2] - 2026-09-11

### Added

- **`:data` 模块**：Room 持久化会话树（branches/messages/meta 三表），旧 JSON 自动迁移。

---

## [v4.0.0-alpha.1] - 2026-09-11

### Added

- **Phase 1 领域层下沉**：抽取 `:core-agent` 与 `:core-tools` 纯 Kotlin 模块，AgentLoop、OpenAI 协议序列化、SSE 解析、工具注册表独立打包，dex merge 失败修复。

---

## [v3.1.0] - 2026-09-10

### Added

- **子代理批量交付**：用量台账（UsageLedger 记录每回合 token 消耗）、相机拍摄输入（图片直连 vision 模型）、可滚动表格渲染（Markdown 解析器扩展）。

### Fixed

- RFC4648 base64 向量期望修复：机器生成字面量替代手工算数，纠正连续两次算术错误。

---

## [v3.0.0] - 2026-09-10

### Added

- **Agent 能力对齐（多 AI 并行交付）**：`web_search` 联网搜索、`task_plan` 任务规划与进度追踪、`notes` 检索与主动记忆、`remind` 提醒持久化重启恢复、图片输入 vision 全链路、Markdown 渲染增强（表格/删除线/任务列表）、服务商预设扩充至 13 家、单元测试补强 36 个对抗边界用例。

---

## [v2.1.0] - 2026-09-10

### Added

- **按工具粒度启停**：Agent 模式下一行蓝色小字配置，被关掉的工具不出现在模型工具清单、也无法被调用。
- **签名 Release 自动发版**：GitHub Actions 配置 keystore secret，push tag 自动构建签名 APK 挂 Releases。

---

## [v2.0.1] - 2026-09-10

### Fixed

- 分支树稳定性：`visibleMessages` 上溯遍历路径修正；流式期间分支保护，防止 streaming 过程中意外 fork 导致的消息丢失。

---

## [v2.0.0] - 2026-09-10

### Added

- **真正的 Agent 化**：9 工具爪子集（web_search / http_get / notes / task_plan / device_info / clipboard / notify / remind / open_url）、15 轮工具循环上限、行为指令（先规划→逐步执行→失败重试→汇报总结）。

---

## [v1.3.0] - 2026-09-10

### Added

- 多会话管理：分支树、长按删除、一键导出（JSON/Markdown）、会话迁移。

---

## [v1.2.0] - 2026-09-10

### Added

- **真正的 Agent 能力**：工具调用解析器、系统提示词管理器、Claude Code 模式；计算器与时钟工具首次上线。

---

## [v1.1.0] - 2026-09-10

### Added

- MIT 许可证文件；README 更新适配 v1.1.0 功能变更。

---

## [v1.0.0] - 2026-09-10

### Added

- 初始版本：AI Claw Agent Android 应用；Provider-agnostic API 层（10 家服务商预设）；加密 Key 存储（AES-256, Android Keystore）；健壮 SSE 流解析；启动图标与深色主题。
