# T-306 · 消息列表 stableId(可选增强,依赖真机验收)

- 难度:⭐⭐⭐
- 状态:todo(可选)
- 波次:**独占**——同时吃 `MessageAdapter.kt` + `ui/ChatViewModel.kt` + `MainActivity.kt`,不能与其他卡并行。
- 前置:**先在真机验收 T-301 的分页体验**;若"向上加载更早消息"时视口稳定、无重绑抖动,本卡可不做。
- 允许修改的文件(白名单,严格遵守):
  - `app/src/main/java/com/openclaw/clawagent/MessageAdapter.kt`
  - `app/src/main/java/com/openclaw/clawagent/ui/ChatViewModel.kt`
  - `app/src/main/java/com/openclaw/clawagent/MainActivity.kt`(热点文件,本卡独占豁免)
  - `app/src/test/java/com/openclaw/clawagent/`(可新建 / 修改测试)
- 禁改:`data/**`(会话树模型不动)、`core-agent/**`、`core-tools/**`。

## 背景

T-301(消息列表分页)交付时**有意未引入 stableId**:`ChatMessage` 目前无稳定身份,`MessageAdapter` 的 DiffUtil `areItemsTheSame` 只按 `role` 比较,prepend 更早消息时可能整表重绑 + 视口抖动。T-301 用「锚点还原」(`scrollToPositionWithOffset`)缓解了这个问题。若真机上仍觉抖动明显,再上 stableId。

## 任务

1. 给消息引入稳定 id。注意约束:`:data` 的 `BranchMessage` 不在白名单,**不能改 data 模块**;id 需在 `app` 层派生(如 `BranchMessage.timestamp` + 序号,或由 `ChatViewModel` 维护单调递增 id 并随 `state.messages` 传入)。要保证**同一条消息在多次 `submitList` 之间 id 不变**(否则反而全量重绑)。
2. `ChatMessage` 增 `id`;`MessageAdapter` 配 `setHasStableIds(true)` + `getItemId`,DiffUtil `areItemsTheSame` 改按 id。
3. 检查流式尾条(`notifyChanged` 原地重建尾条)与 T-201 parse-cache 的既有假设不被破坏。

## 验收标准

- CI 全绿,既有用例零回归。
- 新增/调整测试:prepend 更早消息后,既有消息的 id 不变(可断言稳定身份);流式期间尾条仍只重绑一条。
- 手工验收:超长会话向上翻页,视口不跳、无明显重绑闪烁。

## 交付记录

(完成后填写:认领人 / commit / 关键决策 / 测试结果)
