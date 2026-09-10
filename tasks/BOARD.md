# 🗂️ Claw Agent · 多 AI 协作看板

多个 AI 并行开发本项目。领任务 → 干活 → 填表,三个动作。

## 铁律(违反会被拒收)

1. **只改任务卡里"允许修改的文件"白名单内的文件**——这是并行不冲突的全部秘密。新建文件自由(放指定目录),动白名单外的文件 = 返工。
2. **`MainActivity.kt` 是热点文件,T-101 之外一律禁改。** 需要接线的地方,在交付记录里写"待接线:xxx",由维护者统一做。
3. **一个任务一个 commit**,message 以 `[T-101]` 这样的任务号开头。
4. **交付前自检**:`./gradlew testDebugUnitTest` 全绿;新代码附单元测试;工具类遵循"execute 永不 throw,失败返回错误字符串"的项目约定。
5. **完成动作**:① 代码推到 main(独立 commit);② 在下方看板表格把状态改为 `done` 并填认领人/commit/说明;③ 在任务卡末尾"交付记录"补全。三处都要填。
6. **领任务动作**:把状态改为 `claimed`,填认领人与时间。状态是 `claimed` 且超过 24 小时无交付 commit 的,其他 AI 可以改回 `todo` 接手。

## 怎么干活(给 AI 的操作指引)

- 有仓库写权限:直接改文件 → commit 到 main(遵守铁律) → 更新本表格。
- 没有写权限:把任务卡全文当作你的输入,产出的每个文件以「文件路径 + 完整文件内容」形式输出,交回给维护者合入;交付记录文字一并交回。

## 看板

| 任务号 | 标题 | 难度 | 状态 | 认领人 | 领取时间 | 交付 commit | 交付摘要 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| T-101 | 图片输入(vision 全链路) | ⭐⭐⭐ | claimed | ima copilot(哈哈) | 2026-09-10 10:08 | - | - |
| T-102 | 提醒持久化:重启恢复 | ⭐⭐ | todo | | | | |
| T-103 | Markdown 渲染增强:表格/删除线/任务列表 | ⭐⭐ | todo | | | | |
| T-104 | 服务商预设核查与扩充 | ⭐ | todo | | | | |
| T-105 | 单元测试补强(边界与对抗用例) | ⭐ | todo | | | | |

## 项目速览(所有 AI 必读)

- 语言/构建:Kotlin + Android SDK 34(minSdk 26),`./gradlew testDebugUnitTest` 跑测试,`./gradlew assembleDebug` 出 APK。
- 包名:`com.openclaw.clawagent`;主源码在 `app/src/main/java/com/openclaw/clawagent/`,测试在 `app/src/test/java/com/openclaw/clawagent/`。
- Agent 工具体系:`agent/` 包。`AgentTool` 接口(name/description/parametersJson/execute),`AgentToolbox` 注册表(core() 纯 JVM 可测 / forAndroid() 完整集),行为指令在 `agent/AgentDirective.kt`。
- 代码风格:注释讲"为什么"而非"是什么";用户可见文案用中文;JSON 参数描述中文。
- 当前版本 2.2.0-dev(versionCode 7),11 个工具:calculator / current_time / notes / http_get / web_search / task_plan / device_info / clipboard / notify / remind / open_url。
