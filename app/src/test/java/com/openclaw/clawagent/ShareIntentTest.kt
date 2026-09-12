package com.openclaw.clawagent

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.openclaw.clawagent.agent.AgentLoop
import com.openclaw.clawagent.conversation.ClawDatabase
import com.openclaw.clawagent.conversation.ConversationStorage
import com.openclaw.clawagent.provider.ChatService
import com.openclaw.clawagent.provider.SecurePrefs
import com.openclaw.clawagent.provider.UsageStore
import com.openclaw.clawagent.provider.UsageTracker
import com.openclaw.clawagent.task.ChatRepository
import com.openclaw.clawagent.ui.ChatIntent
import com.openclaw.clawagent.ui.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T-202 系统分享入口测试。
 *
 *  - [ShareReceiverActivity.stashShare]:收到 `ACTION_SEND` + `EXTRA_TEXT` 时把
 *    文本写进 [ChatRepository.pendingShare];缺文本 / 非 SEND 时静默不写。
 *  - [ChatViewModel] 的草稿通道:`SetDraft` → `state.draft` 有值,`ClearDraft`
 *    → null,且不影响 `canSend` 等既有语义。
 */
@RunWith(RobolectricTestRunner::class)
class ShareIntentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context.deleteDatabase(ClawDatabase.NAME)
        context.getSharedPreferences("claw_branches", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("claw_settings", Context.MODE_PRIVATE).edit().clear().commit()
        ChatRepository.reset(context)
        ChatRepository.pendingShare = null
    }

    @After
    fun tearDown() {
        ChatRepository.pendingShare = null
        Dispatchers.resetMain()
    }

    // ── ShareReceiverActivity.stashShare ───────────────────────────

    @Test
    fun `share intent stores the text into the pending slot`() {
        val intent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "总结这段")
        }
        assertTrue(ShareReceiverActivity.stashShare(intent))
        assertEquals("总结这段", ChatRepository.pendingShare)
    }

    @Test
    fun `share without EXTRA_TEXT leaves the slot empty`() {
        val intent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
        }
        assertFalse(ShareReceiverActivity.stashShare(intent))
        assertNull(ChatRepository.pendingShare)
    }

    @Test
    fun `non-send intent is ignored`() {
        val intent = Intent().apply {
            action = Intent.ACTION_VIEW
            putExtra(Intent.EXTRA_TEXT, "不该被接收")
        }
        assertFalse(ShareReceiverActivity.stashShare(intent))
        assertNull(ChatRepository.pendingShare)
    }

    // ── ChatViewModel draft channel ────────────────────────────────

    private class MapUsageStore : UsageStore {
        private val map = HashMap<String, String>()
        override fun read(key: String): String? = map[key]
        override fun write(key: String, value: String) { map[key] = value }
    }

    private fun viewModel(): ChatViewModel {
        val transport = ChatService()
        return ChatViewModel(
            appContext = context,
            prefs = SecurePrefs(context).apply { setApiKey("sk-test") },
            storage = ConversationStorage(context),
            chatService = transport,
            agentLoop = AgentLoop(transport),
            usageTracker = UsageTracker(MapUsageStore()),
        )
    }

    private suspend fun awaitUntil(timeoutMs: Long = 10000, condition: suspend () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(20)
        }
    }

    @Test
    fun `set draft stores text and clear draft removes it without touching canSend`() = runBlocking {
        val vm = viewModel()
        awaitUntil { !vm.state.value.isLoading }
        val canSendBefore = vm.state.value.canSend

        vm.onIntent(ChatIntent.SetDraft("你好 Claw"))
        assertEquals("你好 Claw", vm.state.value.draft)
        // 草稿只是输入框内容,不应影响发送可用性等既有语义。
        assertTrue(canSendBefore)
        assertEquals(canSendBefore, vm.state.value.canSend)

        vm.onIntent(ChatIntent.ClearDraft)
        assertNull(vm.state.value.draft)
    }
}
