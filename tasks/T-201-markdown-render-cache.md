# T-201 · 流式渲染性能:Markdown 解析缓存

- 难度:⭐⭐
- 状态:todo
- 允许修改的文件(白名单,严格遵守):
  - `app/src/main/java/com/openclaw/clawagent/MessageAdapter.kt`
  - `app/src/test/java/com/openclaw/clawagent/MessageAdapterTableRenderTest.kt`(追加用例)
  - 新建测试文件放 `app/src/test/java/com/openclaw/clawagent/`(自由)

## 背景

Claw Agent 的消息气泡由 `MessageAdapter` 渲染:assistant 内容经 `MarkdownParser.parse(content)` 解析成 Segment 列表,再逐段构建 Spannable/表格 widget。当前**每次 bind 都重新 parse**:

1. 流式输出时,agent 循环每来一个 Delta 就重绑尾条气泡——一个 500 字的回复要 parse 几百次,每次都是全量正则+分段,气泡越长越卡;
2. 切换分支/刷新时,全部可见气泡重新 parse。

DiffUtil(v4.1)已经保证只有内容变化的条目会重绑,但**重绑的那一次依然是全量 parse**——这就是本卡的优化目标。

## 任务

在 `MessageAdapter` 内部加一个 **content → 解析结果** 的缓存,让同一内容的气泡只 parse 一次:

1. 构造函数新增可注入解析器(默认指向 `MarkdownParser::parse`):
   ```kotlin
   class MessageAdapter(
       private val onCopy: (String) -> Unit = {},
       private val onMessageLongClick: ((position: Int, ChatMessage) -> Unit)? = null,
       private val parser: (String) -> List<Segment> = MarkdownParser::parse,
   )
   ```
2. Adapter 内部维护 LRU 缓存(`LinkedHashMap(accessOrder=true)` + `removeEldestEntry` 上限 64 条即可,不必引 LruCache):key = 完整 content 字符串,value = `List<Segment>`。
3. `bindContent` 改为从缓存取解析结果;未命中才调 `parser`。
4. **正确性天然成立**:key 是完整 content,content 变了 key 就变——不需要手动失效。注意流式时 content 不断变化,缓存会为每个中间版本留一条,这正是 LRU 上限存在的意义(先到先逐出)。
5. 缓存线程约定:`bindContent` 只在主线程调用,LinkedHashMap 非线程安全但无碍;在类注释里写明这一点。

## 验收标准

- `MessageAdapterTableRenderTest` 现有全部用例零回归(注意:该测试已改为 ListAdapter + `submitList` + `shadowOf(Looper.getMainLooper()).idle()` 模式,保持)。
- 新增测试:注入计数 parser,同 content 连续 bind 两次,parse 只调 1 次;content 变化后再次 bind,parse 再调 1 次且结果为新 content 的。
- 缓存上限测试:塞 70 条不同消息 bind,缓存不超 64(可通过构造参数把上限调小到 2 来测逐出)。
- `./gradlew :app:testDebugUnitTest` 全绿。

## 交付记录

(完成后填写:认领人 / commit / 关键决策 / 测试结果)
