# T-502 · 会话跨库检索(FTS)

- 难度:⭐⭐⭐
- 状态:todo
- 波次:**Wave 1(可与 T-501/T-503 并行)**。
- 允许修改的文件(白名单,严格遵守):
  - `data/src/main/java/com/openclaw/clawagent/conversation/ConversationEntities.kt`(新增 FTS4 实体)
  - `data/src/main/java/com/openclaw/clawagent/conversation/ConversationDao.kt`(新增搜索查询)
  - `data/src/main/java/com/openclaw/clawagent/conversation/ClawDatabase.kt`(version+1 与 Migration)
  - `data/src/test/java/com/openclaw/clawagent/conversation/`(新建/修改测试)
  - `app/src/main/java/com/openclaw/clawagent/agent/HistoryTool.kt`(新建)
  - `app/src/test/java/com/openclaw/clawagent/agent/HistoryToolTest.kt`(新建)
- 禁改:`ConversationTree.kt`/`ConversationStorage.kt` 既有读写路径、`core-agent/**`、`core-tools/**`、`AgentWiring.kt`(归 T-504)。

## 背景

Agent 的记忆目前只有 `notes`(跨会话笔记),但**聊过的历史会话本身搜不到**——"上次我问过你 XX"只能靠用户手动翻分支。本卡给消息历史加 SQLite FTS 检索,并做成新爪子 `search_history`,让 Agent 自己翻历史。

## 任务

1. data 层(**动手前先读 `ClawDatabase.kt` 确认现有迁移策略**):
   - `ConversationEntities.kt` 新增 `@Fts4(contentEntity = <消息实体>)` 的 `MessageFtsEntity(content)`;FTS 外部内容表**必须建触发器同步**(AFTER INSERT/UPDATE/DELETE),放在 Migration SQL 里;
   - `ClawDatabase`:version +1,新增 `Migration`:建 FTS 表 + triggers + **一次性回填**现有消息;
   - `ConversationDao`:新增 `searchMessages(query, limit)`——`JOIN ... MATCH` 返回 (branchId, messageId, role, 内容摘录, 时间),按相关度/时间排序。
2. 迁移测试:建旧版库→写入→升级→**旧数据可被搜到**;新增消息经 trigger 同步进 FTS(用现有 `ConversationStorageTest` 的 in-memory/Robolectric 模式)。
3. `HistoryTool.kt`(app 层,构造注入 **同步** `queryFn: (String, Int) -> List<HistoryHit>` 作为测试缝):
   - name `search_history`;参数 `query`(必填)、`limit`(可选,默认 5,上限 20);
   - 输出:每条带 会话/分支名、角色、时间、内容摘录(前后窗 60 字);无命中输出"没有找到相关的历史消息";空 query 报错;execute 永不 throw;
   - **线程约束**:execute 只会被 AgentLoop 在 `Dispatchers.Default` 调用;生产侧 queryFn 用 `runBlocking` 包 suspend DAO(本卡测试全走 fake,**不碰 Room**);严禁主线程调用。
4. `HistoryToolTest`:命中输出格式/空结果/空 query 报错/limit 收口/fake queryFn 全链路 ≥ 5 用例。

## 验收标准

- CI 全绿,`:data` 迁移测试通过,FTS **中文词条可命中**;
- 既有会话读写用例零回归;
- 真机(可选):在旧会话里搜一个词,能出结果。

## 交付记录

(领取时填:领取时间;完成后填:完成时间 / commit / 关键决策 / 测试结果)
- 领取时间:—
- 完成时间:—
- commit:—
- 关键决策/测试结果:—
