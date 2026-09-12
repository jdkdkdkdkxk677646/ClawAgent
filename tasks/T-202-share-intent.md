# T-202 · 分享/外部文本入口

- 难度:⭐⭐⭐
- 状态:todo
- 允许修改的文件(白名单,严格遵守):
  - `app/src/main/AndroidManifest.xml`
  - `app/src/main/java/com/openclaw/clawagent/ShareReceiverActivity.kt`(**新建**)
  - `app/src/main/java/com/openclaw/clawagent/MainActivity.kt`(本卡获 MainActivity 热点豁免)
  - `app/src/main/java/com/openclaw/clawagent/task/ChatRepository.kt`
  - `app/src/main/java/com/openclaw/clawagent/ui/ChatViewModel.kt`
  - `app/src/main/java/com/openclaw/clawagent/ui/ChatScreen.kt`(仅 InputBar 草稿填充)
  - `README.md`(其他功能清单加一条)
- 注意:与本卡并行的 T-201/T-203/T-205 白名单不含以上任何文件,放心提交。T-204(提示条)也动 ChatScreen.kt,被标记 blocked 排队等你。

## 背景

用户在浏览器/微信/任何 App 里选中文本,想丢给 Claw Agent 处理("总结这段""翻译这段"),目前只能手动复制→切到 Claw→粘贴。本卡打通系统分享:任何 App 的 `ACTION_SEND`(text)都能选「Claw Agent」,跳转后文本已填入输入框——**只填草稿,不自动发送**(用户要确认/补充,这是安全边界)。

## 现状速览(动手前必读)

- `ChatRepository.kt`(task/ 包,进程单例)持有会话树与全部依赖;`AgentTaskService` 的进程内移交用的就是它 companion 里的静态槽手法(`@Volatile private var pending`)——本卡的分享文本用同款手法。
- `MainActivity` 是 Compose 宿主:`onCreate` 里 `setContent { ChatScreen(...) }`,并已注册 `onStart(){ vm.onIntent(ChatIntent.ReloadFromRepository) }`。
- `ChatViewModel` 是 MVI:`ChatUiState`(数据类)+ `ChatIntent`(sealed)+ `ChatEffect`(一次性副作用),入口统一 `onIntent`。
- `InputBar`(ChatScreen.kt 内)的输入文本是本地 `remember { mutableStateOf("") }`——外部草稿需要一个桥。

## 任务

1. **ChatRepository**:加 `@Volatile var pendingShare: String?`(companion 或顶层均可,进程内一次性槽)。
2. **ShareReceiverActivity**(新建):
   - `exported="true"`、theme 用 `@android:style/Theme.NoDisplay`(不闪 UI);
   - intent-filter:`android.intent.action.SEND` + `android.mimeType="text/*"`;
   - 读 `intent.getStringExtra(Intent.EXTRA_TEXT)`,非空则写入 `ChatRepository.pendingShare`,然后 `startActivity(Intent(this, MainActivity::class.java))` + `finish()`;
   - 单 share 动作 + 多 share(EXTRA_TEXT 不在)的防御:取不到就静默 finish。
3. **ChatViewModel**:
   - `ChatUiState` 加 `draft: String? = null`;
   - 新 intent:`SetDraft(val text: String)`、`ClearDraft`;
   - `SetDraft` 写 state.draft;`ClearDraft` 置 null。
4. **MainActivity**:`onStart` 里(在 ReloadFromRepository 之后)检查 `ChatRepository.pendingShare` → 非空则 `vm.onIntent(ChatIntent.SetDraft(it))` + 清槽。注意 `onCreate`/`onStart` 都要兜(冷启动与热启动两条路)。
5. **InputBar**:`LaunchedEffect(state.draft)`:`state.draft?.let { text = it; onIntent(ChatIntent.ClearDraft) }`——填入即清除,防重复触发。`onIntent` 参数需要从调用点传进 InputBar(现在是 onSend/onStop 等细粒度回调,加一个 `onConsumeDraft: (String) -> Unit` 即可,保持风格)。
6. **README** 的「其他功能」清单加一条:`- 📤 系统分享入口:任意 App 选中文本分享给 Claw,直接进输入框`。

## 验收标准

- 新增 Robolectric 测试(放 `app/src/test/java/com/openclaw/clawagent/`):
  - ShareReceiverActivity 收到 EXTRA_TEXT 后 pendingShare 有值、无 EXTRA_TEXT 时为 null;
  - ChatViewModel:SetDraft→state.draft 有值,ClearDraft→null,且 canSend 等既有语义不受影响。
- `./gradlew :app:testDebugUnitTest` 全绿,既有 111+ 用例零回归。
- 手工路径描述写进交付记录(维护者真机验收:浏览器分享→Claw→输入框有内容)。

## 交付记录

(完成后填写:认领人 / commit / 关键决策 / 测试结果)
