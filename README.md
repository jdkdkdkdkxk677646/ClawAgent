# 🦀 Claw Agent

**一个真正会干活的 Android AI Agent**——不是聊天机器人。名字里的 Claw（爪子）是认真的:开启 Agent 模式后,它能自主规划、多步调用工具、把事情办成;现在还带**联网搜索、任务进度清单、跨会话记忆、重启不丢的提醒**和**图片理解**。开源免费、无广告。

## 它凭什么不是"单纯的聊天"?

开启设置里的 **🦾 Agent 模式**,Claw Agent 就长出了 11 只爪子:

| 工具 | 能力 | 典型场景 |
| --- | --- | --- |
| 🔍 `web_search` | 联网搜索(DuckDuckGo,免 key) | "查一下 DeepSeek API 现在多少钱" |
| 🌐 `http_get` | 抓取任意 HTTP(S) 网页 / API,HTML 自动转纯文本 | 读文档、看公告、调公开 API |
| 📓 `notes` | Agent 自己的持久笔记本,跨会话保存 + 关键词检索 | "记住我喝拿铁不加糖"、"我之前记过什么?" |
| 📋 `task_plan` | 任务规划与进度追踪(☐/☑ 实时清单) | 复杂任务先列计划,每完成一步自动汇报 |
| 🔋 `device_info` | 型号、系统、电量、内存、存储、网络、屏幕 | "我手机内存还够吗?" |
| 📋 `clipboard` | 读写系统剪贴板 | "把剪贴板这段总结一下" / "生成文案并复制" |
| 🔔 `notify` | 发 Android 系统通知 | 耗时任务完成后主动叫你 |
| ⏰ `remind` | 延时提醒(AlarmManager,进程被杀/重启都能恢复) | "10 分钟后提醒我关火" |
| 🌍 `open_url` | 在浏览器打开网页 | "给我看这篇的原文" |
| 🧮 `calculator` / 🕐 `current_time` | 精确四则幂运算 / 设备当前时间 | 不靠模型口算、不靠模型猜日期 |

配合 15 轮工具循环与内置的行为指令(先规划→逐步执行→失败重试→汇报总结),它处理的是**多步任务**:"查一下 DeepSeek 现在 API 多少钱一台,记录到笔记里,晚上 8 点提醒我看"——这一句话,Agent 自己拆步骤、自己调工具、自己交付。

## 其他功能

- 🎭 系统提示词 / 角色切换(通用助手、代码专家、翻译官)
- 📚 多会话分支管理:分支树、长按删除、一键导出
- 💬 多轮对话,上下文记忆(条数可限制,防 token 失控)
- 🌊 流式输出 + 打字机效果,随时点停止
- 🧩 Markdown 渲染:代码块、行内代码、粗体、URL、**表格、删除线、任务列表**
- 📷 图片输入:相册选图(vision 模型),单条消息最多 3 张
- ⚙️ **13 家**服务商预设(OpenAI / DeepSeek / 智谱 / 阶跃星辰 / 月之暗面 / Groq / OpenRouter / Pollinations / Ollama / 硅基流动 / Kimi 直连…),支持任意 OpenAI 兼容端点
- 🩺 服务商连通性检测(延迟 / 鉴权 / 离线状态)
- 🔐 API Key 存储在 EncryptedSharedPreferences(AES-256,Android Keystore)
- 🛰️ 无 Key 也能玩:内置演示模式 + Pollinations 免费通道
- 🎨 深色主题 UI,长按气泡复制原文

## 获取 APK

### 方式一:Releases 页(正式签名,推荐)

打 tag(`v*`)会自动构建并把签名 APK 挂到 [Releases](https://github.com/jdkdkdkdkxk677646/ClawAgent/releases)。仓库维护者需先在 **Settings → Secrets and variables → Actions** 配置 4 个 secret:

| Secret | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | 签名 keystore(.jks)文件的 base64(`base64 -w0 my.jks`) |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | key 别名 |
| `KEY_PASSWORD` | key 密码 |

> 未配置 secrets 时 Release 仍会构建,但 APK 是 debug 签名(Actions 运行日志会有警告)。

### 方式二:GitHub Actions artifacts

1. Fork 本仓库(或直接手动触发 workflow)
2. 等 Actions 构建完成
3. 在 **Actions → Build APK → Artifacts** 下载 `claw-agent-apk`

主仓库每次 push 也会自动构建,Artifacts 保留 30 天。

### 方式三:本地构建

```bash
# 需要 Android SDK + JDK 17
./gradlew assembleDebug
# 跑单元测试
./gradlew testDebugUnitTest
```

APK 输出路径:`app/build/outputs/apk/debug/app-debug.apk`

## 快速上手

1. 打开应用 → 右上角设置 → 选服务商 → 填 API Key → 选模型
2. **勾选「🦾 Agent 模式」**(关键一步,默认关闭)
3. 开始对话,观察气泡里的 🔧 工具调用轨迹

不想申请 Key?直接发送消息会用内置演示模式;或选 **Pollinations**(免费,无需 Key)。

> Agent 模式要求所选模型支持 Function Calling(tools 参数)。DeepSeek、GPT-4o、GLM、Kimi 等主流模型均已支持。

## 配置说明

| 设置项 | 说明 |
| --- | --- |
| 服务商 | 预设列表,切换后自动填入端点与推荐模型 |
| API Key | 仅存在本机加密存储,不会上传到任何第三方 |
| 端点 | 任意 OpenAI 兼容 `/v1/chat/completions` 地址 |
| 模型 | 自由填写,下拉提示常用型号 |
| 保留上下文 | 关掉则每次只发当前这条消息 |
| 上下文长度 | 随请求发送的最近历史条数(默认 20 条,可设 10 / 20 / 50 / 不限制) |
| 流式输出 | 逐字返回;关闭则整段返回 |
| 系统提示词 | 每次请求自动附加的 system 消息,用于设定人设与规则 |
| 🦾 Agent 模式 | 允许模型自主多步调用 9 个内置工具(单次最多 15 轮),需模型支持 Function Calling |
| 工具配置 | 按工具粒度启停(Agent 模式下一行蓝色小字)。被关掉的工具不会出现在模型的工具清单里,也无法被调用——不放心"读剪贴板/发通知"就关掉对应爪子 |

## 架构:工具箱是怎么接进去的

```
用户输入 → buildRequestHistory()
             └─ Agent 模式开启时,自动追加 AgentDirective 行为指令
                (内含工具清单,模型由此知道自己是谁、有什么爪子)
         → runAgentTurn()(循环,最多 15 轮)
             ├─ ChatService.streamChat(tools = AgentToolbox.requestJson())
             ├─ 模型返回 tool_calls → AgentToolbox.execute(name, args)
             ├─ 结果作为 role:"tool" 消息回传,进入下一轮
             └─ 无工具调用 → 最终回答,结束
```

- `agent/AgentTool.kt` — 工具接口(name / description / JSON Schema / execute)
- `agent/AgentToolbox.kt` — 注册表;`core()`(纯 JVM,可单测)与 `forAndroid()`(完整爪子)两套
- `agent/CoreTools.kt` — 计算器、时钟(纯 JVM)
- `agent/NoteTool.kt` / `agent/HttpTool.kt` — 笔记本、网页抓取(纯 JVM 逻辑 + 可单测)
- `agent/AndroidTools.kt` — 设备信息、剪贴板、通知、提醒、打开网页
- `agent/AgentDirective.kt` — Agent 行为指令(ReAct 式准则)
- 每个工具 `execute` 永不抛异常:任何失败都变成模型可读的错误字符串,Agent 循环不会崩

## 安全性说明

- API Key 使用 Android Keystore 加密存储,恢复出厂 / 换机后不迁移
- 应用申请了明文网络权限(`usesCleartextTraffic`)——这是为了支持本地 Ollama 等 `http://` 端点,HTTPS 服务商不受影响
- `http_get` 仅允许 http/https、拒绝内嵌凭证;通知需要系统授权(API 33+);笔记存储在应用私有目录
- 提醒基于应用内 Handler:应用进程存活期间有效,被系统杀掉则不触发(工具会如实告知模型)

## 技术栈

- Kotlin + Android SDK 34(minSdk 26)
- OkHttp 4 + 自研 SSE 流解析器(跨 chunk 边界安全)
- Material 3 + ViewBinding
- JUnit 单元测试(SSE 解析 / Markdown / 工具调用累积 / 计算器 / 笔记本 / URL 校验 / 工具箱注册)+ GitHub Actions CI

## Roadmap

- [x] Agent 工具调用 v1(计算器 / 时钟,v1.2.0)
- [x] **真正的 Agent 化:9 工具爪子集 + 15 轮循环 + 行为指令(v2.0.0)**
- [x] 多会话管理:分支树 / 长按删除 / 导出(v1.3.0)
- [x] 分支树稳定性:visibleMessages 上溯遍历 + 流式期间分支保护(v2.0.1)
- [x] 工具配置:按工具粒度启停 + 签名 Release 自动发版(v2.1.0)
- [x] **Agent 能力对齐(v3.0.0):web_search 联网搜索、task_plan 任务规划、notes 检索与主动记忆、AlarmManager 提醒持久化(重启恢复)、图片输入(vision)、Markdown 表格/任务列表、13 家服务商预设**
- [x] 多 AI 并行协作机制(`tasks/` 看板:任务卡 + 文件白名单 + 领任务填表,v2.2.0 起)
- [ ] 表格渲染升级为横向滚动视图
- [ ] 图片输入支持拍照直拍
- [ ] Agent 回合 token 用量统计

## 多 AI 协作

本项目的部分功能由多个 AI 并行开发:`tasks/BOARD.md` 是任务看板,每张任务卡划定**文件白名单**保证互不冲突,流程是"领任务(claimed)→ 交付(done)→ 填表"。v3.0.0 的提醒持久化、Markdown 增强、服务商预设、对抗测试 36 例即由 4 个 AI 并行交付。想参与?挑一张 `todo` 卡。

## License

MIT(见 [LICENSE](LICENSE))
