# T-301 · 消息列表分页(超长会话)

- 难度:⭐⭐⭐
- 状态:todo
- 波次:**Wave 2(独占)**——本卡同时吃 `MainActivity.kt` + `ui/ChatViewModel.kt` + `MessageAdapter.kt`,必须等 Wave 1(T-302/T-303)释放后再领。
- 允许修改的文件(白名单,严格遵守):
  - `app/src/main/java/com/openclaw/clawagent/ui/ChatViewModel.kt`
  - `app/src/main/java/com/openclaw/clawagent/MainActivity.kt`(热点文件,本卡独占豁免)
  - `app/src/main/java/com/openclaw/clawagent/MessageAdapter.kt`(**仅**为稳定消息身份做小改;表格渲染归 T-302,勿碰)
  - `app/src/test/java/com/openclaw/clawagent/ui/`(可新建测试文件)
- 禁改:`data/**`(会话树模型与存储不动)、`core-agent/**`、`core-tools/**`、`ui/ChatScreen.kt`(T-303 在动)。

## 背景

超长会话(`ConversationTree.visibleMessages()` 可能上千条)目前被 `ChatViewModel.syncMessages()` 一次性全量灌进 `state.messages`,交给 `RecyclerView` + `MessageAdapter` 渲染——首屏与每次重建都全量绑定,开销随会话增长线性上升。README Roadmap 剩此项未做。

## 现状速览(动手前必读)

- **内存树是唯一真相**:`ConversationTree`(由 `ChatRepository.tree` 持有)始终保留全量有效消息;`ConversationStorage` 是**整树 replace** 语义(`replaceAllWithMeta`),DAO 无分页查询。**本卡不引 Room PagingSource**——那会破坏架构。
- `ChatViewModel.syncMessages()` 把 `visibleMessages()` 全量写入 `state.messages`;`buildRequestHistory()` **直接读 `state.messages`**——分页后这里必须改指向全量,否则模型上下文被悄悄截断(隐蔽 bug)。
- 渲染:`MessageAdapter`(ListAdapter,position-keyed,DiffUtil)+ `MainActivity.MessagesList()`(`AndroidView` 内嵌 `RecyclerView`,`LinearLayoutManager(stackFromEnd=true)`)。
- `ChatMessage` 目前**无稳定身份**,prepend 会导致整表重绑 + 滚动跳变。

## 任务

1. **UI 窗口化(不动 :data)**:VM 新增窗口状态(`windowSize` 初始值 / `hasMoreMessages` / `isLoadingOlder`),`syncMessages()` 改为 `visibleMessages().takeLast(windowSize)`;新增 `ChatIntent.LoadOlder`,每次把窗口扩大一页(上限 = 全量),同步 `hasMoreMessages`。
2. **修复历史构建**:`buildRequestHistory()` 改读 `tree.visibleMessages()`(全量),确保发给模型的上下文不被窗口截断;加回归测试钉死。
3. **滚动触发 + 锚点保真**:`MainActivity.MessagesList()` 给 `RecyclerView` 加 `addOnScrollListener`——`firstVisibleItemPosition==0 && SCROLL_STATE_IDLE && hasMoreMessages` 时发 `LoadOlder`;加载前记录首个可见项位置与 offset,加载后 `scrollToPositionWithOffset` 还原,避免 prepend 跳动。
4. **稳定身份(建议一并做)**:给消息引入稳定 id(可由 `BranchMessage.timestamp` 派生),`ChatMessage` 增 `id`,`MessageAdapter` 配 `setHasStableIds(true)` + DiffUtil `areItemsTheSame` 按 id——让 prepend 与流式尾条各自稳定。**只做身份相关改动,不要动表格渲染逻辑**(T-302 领地)。

## 验收标准

- `./gradlew :app:testDebugUnitTest` 全绿,既有用例零回归。
- 新增 Robolectric 测试(`ChatViewModel` 依赖 Room + context,只能 Robolectric):预置 > 2×window 条消息 → 首屏只暴露 window 条且 `hasMore=true`;`LoadOlder` 后窗口扩大、`hasMore` 正确翻转为 false;`buildRequestHistory` 仍返回**全量**上下文。
- 手工验收路径写进交付记录:超长会话(可临时调小 window)→ 滚到顶出现更早消息且视口不跳。

## 交付记录

(完成后填写:认领人 / commit / 关键决策 / 测试结果)
