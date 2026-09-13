# T-404 · ToolCallParser 迁移加固 + AgentLoop 兜底接线

- 难度:⭐⭐
- 状态:todo
- 波次:**Wave 1(可并行)**——文件集与 T-401/T-402/T-403 不相交。
- 允许修改的文件(白名单,严格遵守):
  - `core-agent/src/main/java/com/openclaw/clawagent/agent/ToolCallParser.kt`(新建,由 app 模块迁移)
  - `core-agent/src/main/java/com/openclaw/clawagent/agent/AgentLoop.kt`(仅"无原生调用"分支)
  - `core-agent/src/test/java/com/openclaw/clawagent/agent/ToolCallParserTest.kt`(新建)
  - `app/src/main/java/com/openclaw/clawagent/ToolCallParser.kt`(**删除**——已迁移)
- 禁改:`ChatService.kt`/`SseStreamParser.kt`/`ToolCallAccumulator.kt`(原生 tool_calls 主路径不动)、`AgentToolbox.kt`(`names` 属性已存在,直接用)、`MainActivity.kt`。

## 背景

两个事实:

1. 原生 function calling(`tools` 参数 + `message.tool_calls`)是主路径,**已实现且工作正常**——本卡不动它。
2. `app/.../ToolCallParser.kt` 是 **v4.0 重写后遗留的死代码**:全仓库无任何调用点。它本来的用途——给不支持 FC 的服务商解析"文本内嵌工具调用"——被丢掉了。同时它有明显缺陷:宽松匹配正则 `\{[^{}]*"name"...` **无法匹配嵌套 JSON**(参数对象一嵌套就失配),还有一份从未使用的 `BUILTIN_TOOL_NAMES` 遗留清单(与本项目工具名都对不上)。

本卡:把解析器救活、修好、接到该接的地方,并保证"误执行"不可能发生。

## 任务

1. 迁移:包名 `com.openclaw.clawagent` → `com.openclaw.clawagent.agent`,落入 `:core-agent`(纯 JVM,仅 org.json 依赖,符合模块边界);删除 app 侧旧文件。
2. 加固解析器:
   - 保留三策略结构:```json 围栏块 → 整体 JSON → 散落对象;
   - **策略 3 重写为字符串感知的平衡括号扫描**(跟踪转义与引号),替换 `[^{}]*` 正则——嵌套 `parameters` 对象从此可解析;
   - 围栏块整体解析失败时,对块内文本再跑一次平衡扫描(围栏里有散文的情况);
   - 支持 `tool_calls` 包装对象/数组、`tool_use` 形状、裸 `{"name":...,"parameters"|"arguments":{...}}` 三种形态;
   - 按 (name + 参数) **去重**;解析全程不 throw,坏片段降级为"无调用"。
3. `extractTextResponse` 语义保持(从回复中剔除工具调用 JSON,给 UI 展示纯文本),正则同样换平衡扫描实现。
4. **AgentLoop 接线(本卡核心)**:`requestedCalls == null`(原生路径无调用)时才调 `ToolCallParser.parse(roundContent)`,且**必须按 `request.toolset.names` 过滤**——只有命名了已注册工具的调用才执行,散文里讨论 JSON 永远不会触发执行;转换为 `ChatService.ToolCall` 后走既有执行回路;结果非空则不 `Done`,继续下一轮。
5. 删除 `BUILTIN_TOOL_NAMES` 死清单。

## 验收标准

- CI 全绿;`AgentLoopTest` 既有用例零回归(fake transport 的纯文本回复不得被误解析为调用)。
- `ToolCallParserTest` 覆盖:嵌套 parameters 的三种形态、围栏+散文混合、多调用去重、坏 JSON 不抛、纯聊天文本返回空列表、`extractTextResponse` 剔除干净。
- 新增 AgentLoop 兜底用例:fake transport 返回文本内嵌调用 + 已注册工具名 → 工具被执行、结果以 `role:"tool"` 回传;返回文本内嵌**未注册**工具名 → 不执行,回合正常 Done。

## 交付记录

(领取时填:领取时间;完成后填:完成时间 / commit / 关键决策 / 测试结果)
- 领取时间:—
- 完成时间:—
- commit:—
- 关键决策/测试结果:—
