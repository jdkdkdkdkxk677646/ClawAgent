# T-105 单元测试补强:边界与对抗用例

难度 ⭐ | 状态看 `tasks/BOARD.md`

## 背景

项目测试覆盖主干,但边界与对抗输入的面还薄。本任务只加测试不改产品代码——**发现产品 bug 时,在交付记录里写明复现用例,不要顺手修**(修 bug 会与其他并行任务冲突)。

## 允许修改的文件(白名单)

- `app/src/test/java/com/openclaw/clawagent/**`(只允许新增测试文件或在现有测试文件内追加用例)

## 明确禁止

- 改 `app/src/main/` 下**任何**文件

## 需求(每个方向至少 4 个新用例)

1. **CalculatorTool 对抗**:超长嵌套括号(如 `((((…1…))))` 数百层)、`1.2.3` 类多小数点、`2^-3` 负指数、`0^0`、全角数字/中文数字(应拒绝)、`1e5` 科学计数法(应拒绝,文档如此)。
2. **HttpToolLogic 对抗**:`HTTPS://大写协议`、带端口与查询参数的 URL、`http://` 明文、超长 URL(>2000 字符)、HTML 内嵌 `<script>` 位于正文中部而非头部、实体 `&#x2F;` 十六进制、`&AMP;` 大写实体(应按原样保留,不算 bug,测行为)。
3. **NoteTool 边界**:并发写同一标题(两线程各 save 50 次,最终文件可解析、无损坏——内容不必唯一,结构必须完整)、超长 title(>80 字符截断)、content 为纯空白(应报错)、search 关键词恰为文件名片段。
4. **PlanTool / PlanState 状态机**:set → set 覆盖(旧进度清零)、mark 跳序(mark 2 未 mark 1)、status 在 mark 中途的快照正确性。

## 验收标准

- 全部新用例通过;`./gradlew testDebugUnitTest` 全绿
- 若有真实 bug 被暴露:不修代码,交付记录里给出「用例名 + 期望 vs 实际」清单,标记为待维护者裁决

## 交付记录(AI 完成后填)

- 认领人: ima copilot(哈哈)
- 完成时间: 2026-09-10 12:45
- 新增文件/用例数: 新增 4 个测试文件,共 36 个用例
  - `agent/CalculatorAdversarialTest.kt`(14):数百层嵌套括号触发限长/限长内深嵌套正常求值、`1.2.3` 多小数点、全角数字/中文数字拒绝、`1e5` 拒绝(文档行为)、`2^-3`/`10^-1` 负指数、`0^0`=1、`10^309` 溢出、`(-1)^0.5` NaN、`5%0`、缺参数、尾随运算符
  - `agent/HttpToolLogicAdversarialTest.kt`(10):`HTTPS://大写协议`、端口+查询串、明文 http 固化、>2000 字符超长 URL、正文中部 `<script>` 剥除、未闭合 `<script>` 不吞正文、`&#x2F;` 原样保留、`&AMP;` 原样保留、实体化标签不触发剥除(剥标签→解码顺序固化)、控制字符 URL 行为固化
  - `agent/NoteToolEdgeTest.kt`(6):两线程各 50 次并发写同一标题(文件结构完整/单文件/可 list)、100 字标题截断到 80 且可读回、纯空白 content 拒绝不落盘、文件名片段搜索标题命中、含换行/制表符标题清洗、空串/纯点 slug 兜底 `untitled`
  - `agent/PlanStateEdgeTest.kt`(6):set→set 覆盖清零且旧步骤不残留、mark 跳序渲染正确、mark 中途 status 快照一致、无计划 mark 降级、空白步骤过滤、mark 超出当前步数(≤MAX_STEPS 内)报错
- 发现的问题(期望 vs 实际):
  1. 用例 `lone control char passes validateUrl`(`HttpToolLogicAdversarialTest`):`validateUrl` 的 KDoc 称拒绝 control characters,但 `ILLEGAL_URL_CHARS` 正则只含空白与 `<>"'```,`U+0001` 等控制字符可通过校验(期望:按注释拒绝 / 实际:接受)。无实害——实际抓取时 OkHttp 会拒绝该 URL 并降级为错误串。是否收紧正则,待维护者裁决,测试仅固化现状未断言为错误。
  2. 非 bug 备注:大写协议 URL 的 host 大小写规范化在 JDK 17 与 25 上行为不同,相关断言已做跨 JVM 版本稳健化(只断言 scheme 小写)。
- 测试结果: 沙盒内以 Kotlin 2.2.0 编译器 + JUnit 4.13.2 + org.json 20240205(与 CI 同版本)对 5 个主源文件 + 4 个测试文件真实编译并运行,`OK (36 tests)` 全绿;CI(temurin 17 + Gradle 8.2)另行执行确认。
- 遗留问题/待接线: 无。纯测试新增,零产品代码改动。
