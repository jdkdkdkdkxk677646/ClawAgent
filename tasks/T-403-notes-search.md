# T-403 · notes 检索升级(CJK 分词评分)

- 难度:⭐⭐
- 状态:todo
- 波次:**Wave 1(可并行)**——文件集与 T-401/T-402/T-404 不相交。
- 允许修改的文件(白名单,严格遵守):
  - `core-tools/src/main/java/com/openclaw/clawagent/agent/NoteSearchLogic.kt`(新建,纯逻辑 object)
  - `core-tools/src/main/java/com/openclaw/clawagent/agent/NoteTool.kt`(仅 `search` 函数与其 KDoc)
  - `core-tools/src/test/java/com/openclaw/clawagent/agent/NoteSearchLogicTest.kt`(新建)
  - `core-tools/src/test/java/com/openclaw/clawagent/agent/NoteToolTest.kt`(适配断言)
  - `core-tools/src/test/java/com/openclaw/clawagent/agent/NoteToolEdgeTest.kt`(适配断言)
- 禁改:`NoteTool` 的 save/read/list/delete 路径与 slug 逻辑、`app/**`、`core-agent/**`。

## 背景

Agent 的长期记忆 = `notes` 工具,但 search 是**单次裸 `contains`**:用户问「咖啡偏好」,笔记里写的是「拿铁」「美式」就永远搜不到——语义记忆召回全靠逐字命中,这是记忆系统最明显的能力缺口。本卡用**纯 JVM 分词评分**补上(不引 embedding 服务,零新增依赖,零隐私外泄)。

## 任务

1. 新建 `NoteSearchLogic`(纯 object,无 I/O、无 org.json):
   - **分词** `tokenize(query)`:拉丁/数字连续串按词切(小写化);CJK 连续串滑窗取**二元组**(单字 CJK 保留一元);去重;
   - **评分** `score(query, title, body)`:整句 query 忽略大小写命中标题 +40 / 正文 +25(精确命中一票定音);否则逐词元命中记分,标题 ×3、正文 ×1;**全部词元命中**再 +10(召回完整加成);
   - **摘要** `snippet(body, query, maxLen=60)`:定位第一个词元命中位置,取前后窗口;无命中退回开头;换行压空格。
2. `NoteTool.search` 改为:扫描全部笔记 → 逐条评分 → 过滤 >0 → 按分降序 **Top 5** → 输出格式 `找到 N 条(按相关度,共扫描 M 条):`,每条带「精确」或「相关度 S」标签 + snippet;无命中输出「笔记本中没有与「query」相关的笔记。」;空 query 仍返回错误字符串。
3. 既有断言适配:`NoteToolTest` 的「标题命中」→「精确」、「没有包含」→「没有与」;`NoteToolEdgeTest` 同理。**新增召回用例**:标题「拿铁口味」/正文「早上常去楼下买咖啡,大杯少冰」,query「咖啡偏好」应能召回(词元「咖啡」命中正文)。

## 验收标准

- CI 全绿,notes 的 save/read/list/delete 用例零回归。
- `NoteSearchLogicTest` 覆盖:中英混合分词、精确命中 > 词元命中的排序、标题权重高于正文、全命中加成、snippet 窗口与省略号、空串/纯标点 query 返回 0 分。
- 新召回用例(上述「咖啡偏好」)在 NoteToolTest 通过。

## 交付记录

- 领取时间:2026-09-13 12:25+0800
- 完成时间:2026-09-13 12:45+0800
- commit:7fcc75b
- 关键决策:新增纯 object `NoteSearchLogic`(无 I/O、无 org.json):分词=拉丁/数字整词小写化 + CJK 连续串二元组滑窗(单字保留一元、去重);评分=整句精确命中标题 40 / 正文 25(一票定音),否则词元命中标题 ×3 / 正文 ×1,**全部词元命中 +10**;snippet=首个命中位置前后窗口 + 省略号 + 换行压空格。`NoteTool.search` 重写为:扫描全部 → 逐条评分 → 过滤 >0 → 按分降序 **Top 5** → 输出 `找到 N 条(按相关度,共扫描 M 条):`,每条带「精确」/「相关度 S」标签 + snippet。零新增依赖、零隐私外泄
- 测试结果:新增 `NoteSearchLogicTest` **17 用例全绿**(中英混合分词/单字 CJK/去重/纯标点无词元/精确>词元/标题精确>正文精确/标题权重>正文/全命中加成/无命中 0 分/空与纯标点 0 分/大小写/snippet 窗口与省略号/回退开头/压换行/词元命中);`NoteToolTest` 新增「咖啡偏好」召回用例(标题「拿铁口味」/正文含「咖啡」);既有断言适配「标题命中」→「精确」、「没有包含」→「没有与」;core 全量 **199 用例零回归**
- 说明:实现中一处类型推断修正——`sortedBy` 结果兜底用 `emptyList()`(非 `emptyArray()`),已修复
