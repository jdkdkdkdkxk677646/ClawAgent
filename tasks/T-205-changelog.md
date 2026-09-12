# T-205 · CHANGELOG.md 全量补写(v1.0 → v4.3)

- 难度:⭐
- 状态:todo
- 允许修改的文件(白名单,严格遵守):
  - `CHANGELOG.md`(**新建**,仓库根目录)
- 禁改其他一切文件。本卡与所有并行卡零冲突,随时可做。

## 背景

项目从 v1.0.0 一路迭代到 v4.3.0-alpha.1,历史上从没写过变更日志。现在 GitHub 的 Releases 页有零散的 tag 说明,README 的 Roadmap 有里程碑勾选,但缺一份统一、按版本倒序的 CHANGELOG。本卡补齐它,以后每次发版追加。

## 信息来源(按可信度排序)

1. `git tag` 的全部标签与各 tag 的 annotated message(`git tag -n` / `git log --oneline`)——版本号与要点以此为准;
2. `README.md` 的「Roadmap」清单(已勾选项的版本号标注);
3. `tasks/BOARD.md` 第一批任务表(T-101~105 的交付摘要,对应 v2.x/v3.x 的特性);
4. Releases 页(https://github.com/jdkdkdkdkxk677646/ClawAgent/releases)的 tag 说明。

## 格式要求

- [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 风格,倒序;
- 每个版本一节:`## [x.y.z] - YYYY-MM-DD`(日期可从 git tag 的 commit 时间取,取不到就留空);
- 分组用 `### Added / ### Changed / ### Fixed`,中文条目,每条一行说清用户可感知的变化——**不写工程内部重构细节**(v4.0 的模块化可以概括成一句"架构:领域层下沉纯 JVM 模块,agent 循环可 100% 单测");
- 版本范围:v1.0.0 起到 v4.3.0-alpha.1(含),之后留 `## [Unreleased]` 空节;
- 顶部加维护约定一段:发版时把 Unreleased 内容落成新版本节。
- 版本号必须与 git tag 一一对应,不得杜撰中间版本。

## 验收标准

- 覆盖 `git tag` 列出的**每一个** tag,无遗漏;
- 条目与 tag annotated message / README Roadmap 无矛盾;
- Markdown 渲染整洁(表格不用于条目,用列表)。

## 交付记录

(完成后填写:认领人 / commit / 覆盖的版本数)
