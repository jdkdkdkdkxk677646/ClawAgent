package com.openclaw.clawagent.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * 附件选择(T-303):点输入栏的 📷 后弹出,提供「📸 拍照」与「🖼 相册」两条
 * **可见入口**——替代过去隐藏的长按手势。纯 UI,不含任何业务逻辑:拍照的
 * 发起由 [ChatScreen] 内的 `rememberLauncherForActivityResult` 负责。
 */
@Composable
fun AttachmentSheet(
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加图片") },
        text = { Text("📸 拍照直拍,或从相册选择。") },
        confirmButton = {
            TextButton(onClick = onCamera) { Text("📸 拍照") }
        },
        dismissButton = {
            TextButton(onClick = onGallery) { Text("🖼 相册") }
        },
    )
}
