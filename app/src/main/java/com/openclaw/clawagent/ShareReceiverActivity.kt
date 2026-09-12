package com.openclaw.clawagent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.openclaw.clawagent.task.ChatRepository

/**
 * 系统分享入口(T-202):任意 App 选中文本 → 分享 → Claw Agent。
 *
 * 只做一件事:把 [Intent.EXTRA_TEXT] 塞进进程内一次性槽
 * [ChatRepository.pendingShare],再转交 [MainActivity] 把它填成输入框草稿——
 * **不自动发送**(用户要确认/补充,这是安全边界)。
 *
 * `Theme.NoDisplay` 让它不闪任何 UI;取不到文本就静默结束。
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (stashShare(intent)) {
            startActivity(Intent(this, MainActivity::class.java))
        }
        // NoDisplay activity 必须在 onCreate 内立即 finish(否则系统报错);
        // 没拿到文本时也只 finish,静默丢弃(比如多选分享不带 EXTRA_TEXT)。
        finish()
    }

    companion object {
        /**
         * 取分享文本写进进程内槽 [ChatRepository.pendingShare];返回是否拿到了
         * 文本(据此决定要不要唤起 [MainActivity])。非 `ACTION_SEND` 或文本为空
         * 时返回 false 且不动槽。
         */
        internal fun stashShare(intent: Intent?): Boolean {
            val text = intent
                ?.takeIf { it.action == Intent.ACTION_SEND }
                ?.getStringExtra(Intent.EXTRA_TEXT)
            if (text.isNullOrBlank()) return false
            ChatRepository.pendingShare = text
            return true
        }
    }
}
