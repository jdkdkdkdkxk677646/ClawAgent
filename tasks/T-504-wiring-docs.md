# T-504 · 第六批收尾:接线 + README/CHANGELOG

- 难度:⭐
- 状态:todo
- 波次:**Wave 2(独占)**——**前置:T-502 已合入 main**(`HistoryTool` 类必须先存在,否则 CI 编译失败)。
- 允许修改的文件(白名单,严格遵守):
  - `app/src/main/java/com/openclaw/clawagent/agent/AgentWiring.kt`(本批唯一持有者)
  - `README.md`
  - `CHANGELOG.md`
- 禁改:其他一切文件。测试代码本卡不新增(文档与接线卡)。

## 背景

第五批的模式延续:Wave 1 各卡按铁律 2 留"待接线"条目,`AgentWiring.forAndroid` 是热点文件,由本卡统一接线并随文档收口第六批。本批 T-501(ChatViewModel 内部完成)/T-503(Service 内部完成)无待接线条目,唯一接线项来自 T-502。

## 任务

1. `AgentWiring.forAndroid` 增加 `HistoryTool(...)`:生产 queryFn 用 `runBlocking` 包 `ConversationDao.searchMessages`(经 `ChatRepository`/database 实例取 DAO——先读现有代码选最短路径);工具清单放 `JsTool()` 之后(与 `Toolsets.core()` 对齐后自然顺延);
2. 接线核对清单(写入本卡交付记录):T-501(内部接线,无待接线)/T-502(**本卡接线**)/T-503(内部完成,无待接线);
3. README:工具表新增 🔎 `search_history` 行、「12 只爪子」全部改 **13 只**;MCP 段落补"多服务器";「其他功能」补"后台回合可从通知取消";
4. CHANGELOG:第六批条目收口(并入当前未发布版本节,不擅自新开版本号)。

## 验收标准

- CI 全绿(13 只爪子在编译期/`AgentToolboxTest` 可见);
- README 无"12 只爪子"残留(全文检索确认);CHANGELOG 条目与实际交付一致,不虚构未合入特性。

## 交付记录

(领取时填:领取时间;完成后填:完成时间 / commit / 关键决策 / 测试结果 / 接线核对清单)
- 领取时间:—
- 完成时间:—
- commit:—
- 接线核对清单:—
- 关键决策/测试结果:—
