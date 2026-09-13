# T-405 · 第五批收尾:AgentWiring 接线 + README/CHANGELOG

- 难度:⭐
- 状态:todo
- 波次:**Wave 2(独占)**——**前置:T-401、T-402 已合入 main**(有编译依赖:JsTool 类、AndroidWebRenderer 类必须先存在)。T-403/T-404 的交付是否合入不影响本卡,但 README 里 notes/http_get 的描述以其状态为准,不合入就不写对应特性。
- 允许修改的文件(白名单,严格遵守):
  - `app/src/main/java/com/openclaw/clawagent/agent/AgentWiring.kt`(本批唯一持有者)
  - `README.md`
  - `CHANGELOG.md`
- 禁改:其他一切文件。测试代码本卡不新增(文档与接线卡);接线本身由既有 `AgentToolboxTest` 与 CI 全量构建兜底。

## 背景

第五批 Wave 1 的 T-401(T-402)按铁律 2 都留了"待接线"条目——`AgentWiring.forAndroid` 是热点文件,由本卡统一执行接线,并随文档更新一起收口第五批。接线错误会直接在 CI 暴露(编译失败/工具数断言),所以本卡是全批的合入闸门。

## 任务

1. `AgentWiring.forAndroid` 完成两处接线:
   - 工具清单增加 `JsTool()`(建议放 `CurrentTimeTool()` 之后,与 `Toolsets.core()` 顺序一致);
   - `HttpRequestTool()` → `HttpRequestTool(AndroidWebRenderer(context.applicationContext))`。
2. README:
   - 「11 只爪子」全部改为 **12 只**(含工具总数统计处);
   - 工具能力表新增一行:🧩 `run_js`——受限沙箱执行 JavaScript(禁 Java 访问、防死循环),精确计算/JSON 加工/日期推算不再靠口算;
   - `http_get` 行补一句"SPA/动态页面用 render_js=true 走内置引擎渲染";
   - `notes` 行的检索描述改为"分词评分检索(CJK 二元组,支持近义召回)";
   - 配置说明里"Agent 模式"行的工具数与「工具配置」文案同步核对。
3. CHANGELOG:新增第五批对应条目(跟随文件内既有版本段格式;若 v4.3.0 未发布则并入其 Unreleased/下一段,不擅自新开版本号)。
4. 交付时在**本卡**交付记录里登记:本批各卡"待接线"条目已全部完成的核对清单。

## 验收标准

- CI 全绿(接线后 12 只爪子在 `AgentToolboxTest`/编译期可见)。
- README 无"11 只爪子"残留(全文检索确认);CHANGELOG 条目与实际交付一致,不虚构未合入特性。
- `run_js` 与 `render_js=true` 在设置里的「工具配置」开关中可独立启停(代码路径天然支持,验证描述与行为一致即可)。

## 交付记录

- 领取时间:2026-09-13 13:20+0800
- 完成时间:2026-09-13 13:40+0800
- commit:30e3484
- 接线核对清单:
  - [x] **T-401 待接线** → `AgentWiring.forAndroid` 增加 `JsTool()`(置于 `PlanTool()` 之后,与 `Toolsets.core()` 顺序一致)
  - [x] **T-402 待接线** → `HttpRequestTool()` 改为 `HttpRequestTool(AndroidWebRenderer(context.applicationContext))`
  - [x] T-403 / T-404 无"待接线"条目,无需接线
- 关键决策/测试结果:README「11 只爪子」→「12 只」、工具表新增 `🧩 run_js` 行、`http_get` 行补 `render_js=true`、`notes` 行改"分词评分检索(CJK 二元组,支持近义召回)"、配置说明「9 个内置工具」→「12 个」、架构图 `:core-tools`「六只」→「七只」;CHANGELOG 新增 `## [Unreleased]` 段记录第五批四项(未新开版本号)。core 全量 **213 用例零回归**
- 说明:`AgentWiring` 接线仅在 `:app`(本地沙盒无 Android SDK 无法编译),由 CI 全量构建兜底——接线错误会在 CI 编译期直接暴露;`run_js` 与 `render_js=true` 均走既有「工具配置」开关路径,可独立启停
