# T-101 图片输入(vision 全链路)

难度 ⭐⭐⭐ | 状态看 `tasks/BOARD.md`

## 背景

Claw Agent 是 OpenAI 兼容 `/v1/chat/completions` 协议的 Android 聊天端。目前 `ChatService.Message` 只有文本;vision 模型(DeepSeek-VL2 / GLM-4V / GPT-4o 等)需要 `content` 为多段数组格式。本任务打通:选图 → 请求体多模态 → 气泡标记。

## 允许修改的文件(白名单)

- `app/src/main/java/com/openclaw/clawagent/provider/ChatService.kt`
- `app/src/main/java/com/openclaw/clawagent/MainActivity.kt`(本任务**独占**此文件)
- `app/src/main/res/layout/activity_main.xml`
- 新文件自由:`app/src/main/java/com/openclaw/clawagent/ImageAttachments.kt` 或你认为合理的其他新文件
- 新测试文件:`app/src/test/java/com/openclaw/clawagent/**`

## 明确禁止

- 改 `agent/` 包任何文件
- 改 `ConversationStorage.kt` / `ConversationTree.kt`(图片**不**持久化,历史气泡只显示文本标记)
- 改 `MessageAdapter.kt`(标记走文本前缀,不需要改渲染层)

## 需求

1. `ChatService.Message` 增加 `images: List<String> = emptyList()`(元素为完整 data URL 或 http(s) URL)。
2. `serializeMessage`(internal):`images` 非空时,`content` 从字符串改为数组:
   `[{"type":"text","text":"<原content>"}, {"type":"image_url","image_url":{"url":"<每个元素>"}}]`
   `images` 为空时输出必须与现在逐字节一致(现有 wire-format 测试不许破)。
3. `activity_main.xml`:输入栏左侧新增 `ImageButton`,id=`attachBtn`,视觉与 `sendBtn` 风格一致(深色主题,图标可用系统 `android.R.drawable.ic_menu_camera` 或自带 vector),`contentDescription="添加图片"`。
4. `MainActivity`:
   - 用 `ActivityResultContracts.PickVisualMedia`(Photo Picker,免存储权限)选图;读 `Uri` → 判断 mime(image/*)→ base64 编码为 `data:<mime>;base64,<xxx>` 加入 `pendingImages`,上限 **3 张**,单张解码后 > 4MB 拒绝并 Toast「图片过大(>4MB)」。
   - 已选图片数量在输入栏 hint 或 attachBtn 角标提示(简单实现即可)。
   - 发送时:`Message(images = pendingImages.toList())`,气泡文本前缀 `[图片 xN]\n`,发送后清空 `pendingImages`。
   - 图片只进请求,不写入会话树存储(存储路径零改动)。
5. 新增单元测试:serializeMessage 对 images 空与非空两种情况的 wire 格式断言(参照现有 `ChatServiceTest` 风格,key 名要钉死:`type` / `image_url` / `url`)。

## 验收标准

- `./gradlew testDebugUnitTest` 全绿(现有测试零破坏 + 新增断言)
- 无图请求的请求体与改动前完全一致
- 选 3 张图 → 发送 → 请求体含 3 个 image_url 段;data URL 格式正确
- 气泡与历史加载不因图片路径出现任何异常

## 交付记录(AI 完成后填)

- 认领人:ima copilot(哈哈)
- 完成时间:2026-09-10 10:30
- 改动文件清单:`provider/ChatService.kt`、`MainActivity.kt`、`res/layout/activity_main.xml`、`provider/ChatServiceTest.kt`、`app/build.gradle.kts`
- 实现要点(3~5 行):
  1. `Message.images` 非空时 `serializeMessage` 输出 OpenAI 多模态数组(text 段 + 每图一个 image_url 段);为空时输出与旧版逐字节一致;tool 消息强制忽略 images。
  2. Photo Picker(`PickVisualMedia`)选图,解码后 >4MB 拒绝;mime 取自 ContentResolver,缺省 image/jpeg;`Base64.NO_WRAP` 拼 data URL;上限 3 张。
  3. 附件按钮两态着色(蓝=空/琥珀=已暂存) + contentDescription 报数。
  4. 会话树只存 `[图片 xN]` 文本标记(历史气泡可见),图片字节不落存储;`buildRequestHistory` 把 images 挂到最后一条 user 消息。
- 测试结果:CI 全绿(6271147f);新增 3 例 wire 格式断言(数组结构/key 名钉死/空图兼容),现有测试零破坏。
- 遗留问题/待接线:无。备注:维护者修订——白名单追加 `app/build.gradle.kts`(仅新增 androidx.activity 1.8.2 依赖,PickVisualMedia 需要 activity 1.7+)。
