# T-103 Markdown 渲染增强:表格 / 删除线 / 任务列表

难度 ⭐⭐ | 状态看 `tasks/BOARD.md`

## 背景

`markdown/MarkdownParser.kt` 是自研渲染器,当前支持代码块/行内代码/粗体/链接。Agent 回复里表格和 `- [ ]` 任务列表越来越常见(尤其 task_plan 的 ☑☐ 进度),需要解析层跟上。

## 允许修改的文件(白名单)

- `app/src/main/java/com/openclaw/clawagent/markdown/MarkdownParser.kt`
- 新测试文件:`app/src/test/java/com/openclaw/clawagent/markdown/**`
- `app/src/main/java/com/openclaw/clawagent/MessageAdapter.kt`(**仅当**解析层新增了段落类型需要对应渲染时;其余一行不许动)

## 明确禁止

- 改 `MainActivity.kt` / `ChatService.kt` / `agent/` 包 / 会话存储

## 需求

1. **先读** `MarkdownParser.kt` 全文,理解现有 AST/解析结构后,以最小侵入方式扩展。保持现有公开 API 兼容(现有调用点不许破)。
2. 新能力(按 GFM 语义):
   - **表格**:第一行表头、第二行 `| --- |` 分隔、后续数据行;解析为结构化表格节点。管道符转义 `\|` 要处理。
   - **删除线**:`~~text~~` → 删除线节点(行内,优先级低于代码段——代码块/行内代码里的 `~~` 不渲染)。
   - **任务列表**:`- [ ] ` 与 `- [x] ` → 带勾选状态的列表项。
3. `MessageAdapter` 若需新增视图类型:深色主题配色与现有一致(参考现有代码块面板 `#0b1220` 一类的做法);表格可横向滚动,任务列表勾选框只读(不接交互)。
4. 单元测试:每种新语法至少 3 个用例(基本/嵌套上下文/不应触发的反例),参照现有 `MarkdownParserTest` 风格。

## 验收标准

- `./gradlew testDebugUnitTest` 全绿
- 现有 Markdown 用例零回归
- task_plan 工具返回的 `☑ / ☐` 若用 `- [x]/- [ ]` 语法包裹,能在气泡中正确渲染

## 交付记录(AI 完成后填)

- 认领人:
- 完成时间:
- 改动文件清单:
- 实现要点(3~5 行):
- 测试结果:
- 遗留问题/待接线:
