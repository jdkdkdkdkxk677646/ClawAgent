# 🤖 Claw Agent

一个轻量 AI 聊天助手 Android 应用：自带 10 家服务商预设、流式输出、API Key 加密存储，开源免费、无广告。

## 功能

- 🦾 **Agent 工具调用**（v1.2.0 新增）：开启后模型可自动调用内置工具——**计算器**（精确四则/幂运算）与**实时时钟**（回答"现在几点"不再靠猜），并支持多轮连续调用，调用过程实时显示
- 🎭 **系统提示词**（v1.2.0 新增）：为 Agent 自定义人设与行为规则，每次请求自动携带
- 💬 多轮对话，上下文记忆（条数可在设置中限制，防止 token 消耗无限增长）
- 🌊 流式输出，打字机效果，**随时点停止**中断生成
- 📋 长按气泡复制消息原文
- 🧩 Markdown 渲染：代码块等宽字体 + 深色面板、行内代码、粗体、URL 自动识别
- ⚙️ 10 家服务商预设（OpenAI / DeepSeek / 智谱 / 阶跃星辰 / 月之暗面 / Groq / OpenRouter / Pollinations / Ollama…），也支持任意 OpenAI 兼容端点
- 🔐 API Key 存储在 EncryptedSharedPreferences（AES-256，Android Keystore 加密）
- 🛰️ 无 Key 也能聊：内置演示模式 + Pollinations 免费通道
- 🎨 深色主题 UI
- 💾 对话历史本地存储（上限 300 条）

## 获取 APK

### 方式一：GitHub Actions（推荐）

1. Fork 本仓库（或直接 Fork 后手动触发 workflow）
2. 等 Actions 构建完成
3. 在 **Actions → Build APK → Artifacts** 下载 `claw-agent-apk`

主仓库每次 push 也会自动构建，Artifacts 保留 30 天。

### 方式二：本地构建

```bash
# 需要 Android SDK + JDK 17
./gradlew assembleDebug
# 跑单元测试
./gradlew testDebugUnitTest
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

## 快速上手

打开应用 → 右上角设置 → 选服务商 → 填 API Key（去对应平台的控制台申请）→ 选模型 → 开始聊。

不想申请 Key？直接发送消息会用内置演示模式；或在服务商里选 **Pollinations**（免费，无需 Key）。

## 配置说明

| 设置项 | 说明 |
| --- | --- |
| 服务商 | 预设列表，切换后自动填入端点与推荐模型 |
| API Key | 仅存在本机加密存储，不会上传到任何第三方 |
| 端点 | 任意 OpenAI 兼容 `/v1/chat/completions` 地址 |
| 模型 | 自由填写，下拉提示常用型号 |
| 保留上下文 | 关掉则每次只发当前这条消息 |
| 上下文长度 | 随请求发送的最近历史条数（默认 20 条，可设 10 / 20 / 50 / 不限制） |
| 流式输出 | 逐字返回；关闭则整段返回 |
| 系统提示词 | 每次请求自动附加的 system 消息，用于设定 Agent 人设与规则 |
| Agent 工具调用 | 允许模型调用内置工具（计算器 / 实时时钟），最多连续 5 轮；需所选模型支持 Function Calling |

## 安全性说明

- API Key 使用 Android Keystore 加密存储，恢复出厂 / 换机后不迁移
- 应用申请了明文网络权限（`usesCleartextTraffic`）——这是为了支持本地 Ollama 等 `http://` 端点，HTTPS 服务商不受影响

## 技术栈

- Kotlin + Android SDK 34（minSdk 26）
- OkHttp 4 + 自研 SSE 流解析器（跨 chunk 边界安全）
- Material 3 + ViewBinding
- JUnit 单元测试（SSE 解析 / Markdown 解析 / 工具调用累积 / 计算器）+ GitHub Actions CI

## Roadmap

- [ ] 多会话管理（历史列表 / 删除 / 导出）
- [x] 系统提示词（System Prompt）自定义（v1.2.0）
- [x] Agent 工具调用（计算器 / 时钟，Function Calling，v1.2.0）
- [ ] 图片输入支持
- [ ] 签名 Release 构建 + tag 自动发 Release

## License

MIT（见 [LICENSE](LICENSE)）
