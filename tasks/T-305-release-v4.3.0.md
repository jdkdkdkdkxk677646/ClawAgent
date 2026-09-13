# T-305 · v4.3.0 发版准备(CHANGELOG 落版 + 版本号)

- 难度:⭐⭐
- 状态:todo
- 波次:可与 T-304 并行(文件集不相交)。
- 允许修改的文件(白名单,严格遵守):
  - `CHANGELOG.md`
  - `app/build.gradle.kts`(**仅** `versionCode` / `versionName` 两行)
- 禁改:其他一切。

## 背景

第二批(T-201~T-205)与第三批(T-301~T-303)已全部 done,功能已进 main。该把 `[Unreleased]` 落成正式版本了。注意:**打 tag 与配置签名 secrets 是维护者动作,不是本卡职责**(见下)。

## 任务

1. `CHANGELOG.md`:把 `## [Unreleased]` 段落的 `### Added - 无` 替换为本次要发布的条目,然后按维护约定把它落为 `## [v4.3.0] - <yyyy-mm-dd>`,并**移除** `## [Unreleased]`(或留一个空的新 Unreleased,遵循既有风格)。条目至少覆盖:
   - 表格渲染升级为 Compose 原生(横向滚动 + 列宽对齐)
   - 系统分享入口(ACTION_SEND 文本 → 输入框草稿,不自动发送)
   - 后台任务进行中提示条
   - 消息列表分页(窗口化 + 加载更早)
   - 拍照直拍的 Compose 内整合(可见入口)
   - AgentTaskService 后台回合的端到端单测;`Fixed`:若干
2. `app/build.gradle.kts`:`versionCode = 15` → `16`;`versionName = "4.3.0-alpha.1"` → `"4.3.0"`。

## 验收标准

- CI 全绿(零回归)。
- `versionName` 与将要打的 tag(`v4.3.0`)严格一致;CHANGELOG 版本节与 tag 对应。

## 维护者动作(不在本卡白名单内,完成后交回维护者)

1. 打 tag:`git tag v4.3.0 && git push origin v4.3.0`(或经 API 建 tag)——触发 Release workflow 构建 APK 挂 Releases。
2. 若要**正式签名** APK:需先在 Settings → Secrets 配 `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`;未配置则 Release 产出 debug 签名并带 warning。
3. 真机验收(相机往返、分页手势、系统分享、后台横幅)只能人工完成。

## 交付记录

- 领取时间:2026-09-13 10:40+0800
- 完成时间:2026-09-13 11:50+0800
- commit:(见看板回填)
- 关键决策:移除空的 `## [Unreleased]` 段,新增 `## [v4.3.0] - 2026-09-13` 版本节;保留 `## [v4.3.0-alpha.1]` 历史节点(与 v4.2.0 的 alpha + 正式并存的既有风格一致);条目覆盖后台任务/消息分页/表格渲染/拍照整合/系统分享/后台横幅 + Fixed 两条;`versionCode 15→16`、`versionName "4.3.0-alpha.1"→"4.3.0"`
- 维护者动作:① 打 tag `v4.3.0` 触发 Release workflow;② 配置签名 secrets(`KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`);③ 真机验收(相机往返/分页手势/系统分享/后台横幅),T-306 视情况决定是否做
