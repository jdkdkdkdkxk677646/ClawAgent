# T-302 · 表格渲染升级为 Compose 原生横向滚动版

- 难度:⭐⭐
- 状态:todo
- 波次:**Wave 1(可与 T-303 并行)**——本卡文件集与 T-303 完全不相交。
- 允许修改的文件(白名单,严格遵守):
  - `app/src/main/java/com/openclaw/clawagent/MessageAdapter.kt`
  - `app/src/main/java/com/openclaw/clawagent/ui/MessageTable.kt`(**新建**,Compose 表格 Composable)
  - `app/src/test/java/com/openclaw/clawagent/MessageAdapterTableRenderTest.kt`(重写渲染断言)
  - `app/build.gradle.kts`(**仅**为深度 Compose 断言追加 `testImplementation("androidx.compose.ui:ui-test-junit4")`;不做深度断言可不改)
  - 删除已无引用的 `app/src/main/res/layout/item_table.xml`、`item_table_cell.xml`、`item_table_header_cell.xml`(提交 tree entry 时以 `sha: null` 删除)
- 禁改:`MainActivity.kt`(热点归 T-303)、`ui/ChatViewModel.kt`、`ui/ChatScreen.kt`、`markdown/MarkdownParser.kt`(解析层不动)、`data/**`、`core-*/**`。

## 背景

Markdown 表格目前用传统 View 渲染:`MessageAdapter.appendTableWidget()` 里 `HorizontalScrollView`(`item_table.xml`)包 `TableLayout`,单元格来自 `item_table_cell.xml`。README Roadmap 要求升级为**横向滚动视图的 Compose 原生版**。解析层 `MarkdownParser.Segment.Table` 已是结构化数据,**不需要改解析**。

## 现状速览(动手前必读)

- 消息列表整体是 View 体系(`AndroidView` 桥 `RecyclerView`),所以"Compose 原生表格"= 在气泡容器里插入一个 `ComposeView` 承载新 Composable(Compose → AndroidView(RecyclerView) → ComposeView 嵌套)。
- 现有测试 `MessageAdapterTableRenderTest` 强依赖 View 结构(`tableOf()` 强转 `HorizontalScrollView` 取 `R.id.tableLayout`、`childCount` 断言),改造后**必须重写**为结构级断言。
- 长按复制现在挂在 View 上;改用 `ComposeView` 后需处理长按(可给 `ComposeView` 挂 `setOnLongClickListener`)。

## 任务

1. 新建 `ui/MessageTable.kt`:Composable 渲染 `MarkdownParser.Segment.Table`(表头底色 / divider 与旧版视觉对齐),整体可横向滚动(`Modifier.horizontalScroll(rememberScrollState())`)。
2. `MessageAdapter.appendTableWidget()` 改为创建 `ComposeView`(`setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)`),`setContent { MessageTable(table) }`;保留长按复制行为。
3. 重写 `MessageAdapterTableRenderTest`:断言"含表格的助手消息 → 气泡内出现 1 个 `ComposeView`;纯文本 / 代码块 → 无 `ComposeView`;多条表格 → 多个",沿用 `submitList + shadowOf(Looper).idle()` 模式。
4. 删除死资源 `item_table*.xml` 及 `MessageAdapter` 里对 `R.id.tableLayout` 的残留引用。

## 验收标准

- `./gradlew :app:testDebugUnitTest` 全绿,既有用例零回归(表格断言按上述重写)。
- 解析语义不变由既有 `MarkdownParserTest` 兜底(解析层未动)。
- 手工验收路径写进交付记录:含表格的 Markdown 回复 → 表格以 Compose 渲染、可横向滑动、视觉与旧版一致。

## 交付记录

- **认领人**:哈哈
- **交付 commit**:`60d2f653`
- **状态**:done;CI 全绿(GitHub Actions run `34697455374`,零回归)

**关键决策 / 修改点**
- 新建 `app/src/main/java/com/openclaw/clawagent/ui/MessageTable.kt`:Compose 原生表格。列宽用 `TextMeasurer` 按"该列最长单元格"测量后固定(复刻旧版 `TableLayout` 的列对齐),整体 `horizontalScroll` 横向滚动;表头底色 `#22263a` + 加粗,单元格 `#e2e8f0` 14sp / padding 12×10,行间 1dp `#2a2f3a` 分隔线——与旧 `item_table*.xml` 视觉对齐。
- `MessageAdapter.appendTableWidget()`:`HorizontalScrollView` + `TableLayout` 改为一个 `ComposeView`(`setViewCompositionStrategy(DisposeOnViewTreeLifecycleDestroyed)`),`setContent { MessageTable(table) }`;长按复制仍挂 View 的 `OnLongClickListener`。删除已无用的 `buildTableRow()`。
- 删除死资源 `item_table.xml` / `item_table_cell.xml` / `item_table_header_cell.xml`(提交以 `sha: null` 删除)。
- 重写 `MessageAdapterTableRenderTest`:断言下调为"气泡面板里出现 `ComposeView`"这一结构级契约(含表格→1 个;多条表格→多个;纯文本/代码块/用户消息→0 个;rebind 幂等;长按复制),保留 T-201 parse-cache 的 4 个用例。未新增 compose-ui-test 依赖(不做像素级断言)。
- 解析层 `MarkdownParser` 零改动,语义由 `MarkdownParserTest` 兜底。

**手工验收路径(请维护者真机确认)**:让模型输出一段 Markdown 表格 → 表格以 Compose 渲染、可横向滑动、表头底色+加粗、行间分隔线,视觉与旧版一致。

**测试结果**:`gradle :core-agent:test :core-tools:test :data:testDebugUnitTest :app:testDebugUnitTest` 全绿(CI)。

**环境说明**:执行沙盒无法本地运行 `:app` 单测,故以 CI 验证。
