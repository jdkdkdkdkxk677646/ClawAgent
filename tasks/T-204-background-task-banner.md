# T-204 · 后台任务进行中提示条

- 难度:⭐
- 状态:**blocked——等 T-202 完成后再领**(本卡与 T-202 白名单都含 ChatScreen.kt,并行会冲突;T-202 done 后本卡解锁)
- 允许修改的文件(白名单,严格遵守):
  - `app/src/main/java/com/openclaw/clawagent/ui/ChatScreen.kt`
  - `app/src/test/java/com/openclaw/clawagent/ui/`(新增 Compose 不可直测,本卡以交付记录里的手工验收路径代替测试;若想加纯逻辑测试可自建文件)
- 禁改:`ChatViewModel.kt`(state 里已有需要的全部字段)。

## 背景

v4.3 后台任务:发送栏 ☕→🚀 后发送,回合移交给前台服务跑。当前的用户反馈只有一条一闪而过的 Toast("🦀 已移交后台,完成后通知你")——**界面上没有任何持续提示**,用户切回来分不清"是发失败了我没看到,还是任务真的在后台跑"。

`ChatUiState` 已有现成字段:`backgroundTaskRunning: Boolean`(任务期间为 true,结束自动回 false——注意:VM 在回前台 `ReloadFromRepository` 时会同步该标志,所以 UI 只需响应 state,**不需要任何 Service 感知**)。

## 任务

1. 在 `ChatScreen` 的 Column 布局中,**消息列表(或欢迎块)与 InputBar 之间**插入一条横幅,仅当 `state.backgroundTaskRunning` 时显示:
   - 文案:`🦀 后台任务进行中——锁屏/切走都不中断,完成后会通知你`
   - 样式:与深色主题一致(背景 `Color(0xFF1a1d27)` 圆角条、文字 `ClawColors.Accent` 小号);建议 `AnimatedVisibility` 淡入淡出。
   - 可点击:点击后发 `ChatEffect` 不现实(UI 层拿不到 effects 通道)——简化:不可点,纯信息展示。
2. 顶部欢迎块(`WelcomeBlock`)不需要动。
3. 注意 MVI 边界:本卡**只消费 state,不新增 state**;若发现现有字段不够表达,写进交付记录"待接线"而不是自行加字段。

## 验收标准

- `./gradlew :app:testDebugUnitTest` 全绿(零回归即可,Compose UI 无新测试)。
- 交付记录写明手工验收路径:发送栏切 🚀 → 发消息 → 看到横幅;任务完成通知到达后回前台 → 横幅消失。

## 交付记录

(完成后填写:认领人 / commit / 手工验收截图或描述)
