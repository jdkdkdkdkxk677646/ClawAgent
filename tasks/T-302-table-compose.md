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

(完成后填写:认领人 / commit / 关键决策 / 测试结果)
