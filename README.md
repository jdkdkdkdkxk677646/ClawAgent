# 🤖 AI Claw Agent

一个轻量 AI 聊天助手 Android 应用，支持流式输出、上下文记忆、自定义 API 端点。

## 功能

- 💬 多轮对话，支持上下文记忆
- 🌊 流式输出，打字机效果
- ⚙️ 自定义 API 端点和模型
- 🎨 深色主题 UI
- 💾 对话历史本地存储
- 🔓 开源免费，无广告

## 构建

### 方式一：GitHub Actions（推荐）

1. Fork 本仓库
2. 等待 GitHub Actions 自动构建
3. 在 Actions 页面下载编译好的 APK

### 方式二：本地构建

```bash
# 需要 Android SDK + JDK 17
./gradlew assembleDebug
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

## 配置

打开应用 → 设置 → 填入你的 API Key 和端点地址即可开始使用。

默认端点：`https://api.stepfun.com/v1/chat/completions`

## 技术栈

- Kotlin + Android SDK 34
- OkHttp 4（网络请求）
- Material 3 设计
- ViewBinding
- GitHub Actions CI/CD

## License

MIT
