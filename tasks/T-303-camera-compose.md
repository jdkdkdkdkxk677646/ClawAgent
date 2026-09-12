# T-303 · 拍照直拍的 Compose 内整合

- 难度:⭐⭐
- 状态:todo
- 波次:**Wave 1**——本波唯一持有 `MainActivity.kt` 豁免的卡(与 T-302 并行,文件集不相交)。
- 允许修改的文件(白名单,严格遵守):
  - `app/src/main/java/com/openclaw/clawagent/ui/ChatScreen.kt`
  - `app/src/main/java/com/openclaw/clawagent/MainActivity.kt`(热点文件,本卡独占豁免)
  - `app/src/main/java/com/openclaw/clawagent/ui/ChatViewModel.kt`(**仅**调整 `prepareCamera()` 的返回形态,勿动其他逻辑)
  - `app/src/main/java/com/openclaw/clawagent/ui/AttachmentSheet.kt`(**新建**,拍照 / 相册选择 UI)
  - `app/src/test/java/com/openclaw/clawagent/ui/`(可新建测试)
- 禁改:`ImageAttachments.kt`(纯 helper,已够用)、`data/**`、`core-*/**`、`MessageAdapter.kt`(T-302 在动)、`AndroidManifest.xml`(FileProvider 已就绪,通常零改动)。

## 背景

拍照输入的**管道已存在**:`MainActivity` 注册了 `ActivityResultContracts.TakePicture`;`ChatViewModel.prepareCamera()` / `onCameraResult()` 已完成"临时文件 + FileProvider URI + 读字节 + 4MB 上限 + stage(dataUrl)";`AndroidManifest` 的 `FileProvider` 与 `res/xml/file_paths.xml` 均已就位。**缺口是"Compose 内整合"**:当前拍照触发是隐藏的**长按附件**手势、launcher 注册在 Activity 层,无可见入口。README Roadmap 剩此项。

## 现状速览(动手前必读)

- `ChatViewModel.prepareCamera()` 目前返回 `ChatEffect.LaunchCamera(uri, file)`;`onCameraResult(success)` 完成落盘 / 裁剪 / stage。
- 上限于 `MAX_IMAGE_BYTES`(4MB)与单条 3 张(`pendingImages` / `stage`),**保持不变**。
- `ImageAttachments` 用 `java.util.Base64`(纯 JVM 可测)——**新代码别改用 `android.util.Base64`**。

## 任务

1. 在 `InputBar` 的 📷 上提供**可见入口**:点按弹出(Composable)选择——「📸 拍照」/「🖼 从相册选择」(可放 `ui/AttachmentSheet.kt`),替代或补充现有隐藏长按。
2. 拍照**在 Compose 内发起**:用 `rememberLauncherForActivityResult(ActivityResultContracts.TakePicture())`,由 `prepareCamera()` 拿到 URI → `launcher.launch(uri)` → 回调 `onCameraResult(success)`。ActivityResultLauncher 必须留在 composition 内,不进 VM。
3. 必要时清理 `MainActivity` 里被取代的旧接线(保留 / 改造 `onAttachLongClick`),保持相册路径不变。
4. MVI 边界:VM 只负责"URI 生成 / 字节处理 / stage";UI 只负责"发起与选择"。

## 验收标准

- `./gradlew :app:testDebugUnitTest` 全绿,既有用例零回归。
- Robolectric 新增测试:`prepareCamera()` 产出 `cacheDir/photos/IMG_*.jpg` 且 URI authority = `${packageName}.fileprovider`;`onCameraResult(false)` 不 stage;`onCameraResult` 超 4MB → Toast effect 且不 stage;成功 → `stagedImageCount` +1。
- 手工验收路径写进交付记录:点 📷 → 选「拍照」→ 授权 → 拍照回填 → 发送请求体含 `image_url`(vision 全链路)。

## 交付记录

(完成后填写:认领人 / commit / 关键决策 / 测试结果)
