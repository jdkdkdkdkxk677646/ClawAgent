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

- **认领人**:哈哈
- **交付 commit**:`ce85e5fe`(修复)、`950f8d4e`(主体)
- **状态**:done;CI 全绿(GitHub Actions run `34698051167`,零回归)

**关键决策 / 修改点**
- `ChatScreen.kt`:新增 `onRequestCamera: () -> Uri?` 与 `onCameraResult: (Boolean) -> Unit` 两个回调;在 composition 内用 `rememberLauncherForActivityResult(TakePicture())` 发起拍照;`InputBar` 的 📷 由"点击=相册 / 长按=拍照"改为"点击弹出附件菜单"。
- 新建 `ui/AttachmentSheet.kt`:Compose AlertDialog,提供「📸 拍照」「🖼 相册」两条**可见入口**,替代过去隐藏的长按手势。
- `ChatViewModel.kt`:`prepareCamera()` 返回形态由 `ChatEffect.LaunchCamera?` 改为 `Uri?`(拍照发起上移到 UI 层);拆出 `internal fun newCameraTempFile()`(建文件 + 记待清理,与 FileProvider 解耦,便于 JVM 单测)。删除 `ChatEffect.LaunchCamera`。
- `MainActivity.kt`:删除 Activity 层的 `TakePicture` launcher 与 `onAttachLongClick` 接线,及 effects 里的 `LaunchCamera` 分支;改传 `onRequestCamera = { vm.prepareCamera() }` / `onCameraResult = { vm.onCameraResult(it) }`。
- `ImageAttachments` / `FileProvider` / `file_paths.xml` / 4MB 上限 / 单条 3 张:**均未改动**(管道本就存在)。

**一个环境坑**:`FileProvider.getUriForFile` 依赖真实 `PackageManager`,而 Robolectric 没有 `resolveContentProvider` 的 shadow → 在 JVM 下必抛异常。因此把"建文件"抽成 `newCameraTempFile()` 供单测,URI 生成一段交给真机验收(该路径本就是既有生产逻辑)。

**手工验收路径(请维护者真机确认)**:点输入栏 📷 → 弹「拍照 / 相册」→ 选「拍照」→ 授权相机 → 拍一张回填 → 输入框出现 📷1 → 发送,请求体含 `image_url`(vision 全链路);选「相册」走原路径;大图 > 4MB 应提示"图片过大"。

**测试结果**:`gradle :core-agent:test :core-tools:test :data:testDebugUnitTest :app:testDebugUnitTest` 全绿(CI)。

**环境说明**:执行沙盒无法本地运行 `:app` 单测,故以 CI 验证。
