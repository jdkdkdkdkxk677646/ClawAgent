package com.openclaw.clawagent.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openclaw.clawagent.agent.AgentLoop
import com.openclaw.clawagent.conversation.ClawDatabase
import com.openclaw.clawagent.conversation.ConversationStorage
import com.openclaw.clawagent.provider.ChatService
import com.openclaw.clawagent.provider.SecurePrefs
import com.openclaw.clawagent.provider.UsageStore
import com.openclaw.clawagent.provider.UsageTracker
import com.openclaw.clawagent.task.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * T-303 拍照输入的 Compose 整合测试。
 *
 * 相机的真实往返(`ActivityResultContracts.TakePicture`)与 FileProvider 生成的
 * content URI 都无法在 JVM 下单测(Robolectric 无 `resolveContentProvider` shadow),
 * 因此这里覆盖**可单测的管道契约**:临时文件落在 cacheDir/photos 且命名 `IMG_*.jpg`,
 * 以及 `onCameraResult()` 的成功 / 取消 / 超限三条分支。URI 的 authority 由真机验收。
 */
@RunWith(RobolectricTestRunner::class)
class CameraIntegrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context.deleteDatabase(ClawDatabase.NAME)
        context.getSharedPreferences("claw_branches", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("claw_settings", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.cacheDir, "photos").deleteRecursively()
        ChatRepository.reset(context)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

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
    fun `camera temp file lives under cache photos with an IMG name`() {
        val vm = viewModel()

        val file = vm.newCameraTempFile()

        assertEquals(
            File(context.cacheDir, "photos").canonicalPath,
            file.parentFile!!.canonicalPath,
        )
        assertTrue("文件名应为 IMG_*.jpg,实际 ${file.name}", file.name.startsWith("IMG_"))
        assertTrue(file.name.endsWith(".jpg"))
    }

    @Test
    fun `cancelled capture does not stage an image`() {
        val vm = viewModel()
        vm.newCameraTempFile()

        vm.onCameraResult(false)

        assertEquals(0, vm.state.value.stagedImageCount)
    }

    @Test
    fun `oversized photo is rejected without staging`() {
        val vm = viewModel()
        val file = vm.newCameraTempFile()
        file.writeBytes(ByteArray(4 * 1024 * 1024 + 1)) // > MAX_IMAGE_BYTES

        vm.onCameraResult(true)

        assertEquals(0, vm.state.value.stagedImageCount)
    }

    @Test
    fun `successful capture stages one image`() = runBlocking {
        val vm = viewModel()
        val file = vm.newCameraTempFile()
        // A tiny fake JPEG payload.
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02))

        vm.onCameraResult(true)

        awaitUntil { vm.state.value.stagedImageCount == 1 }
        assertEquals(1, vm.state.value.stagedImageCount)
    }
}
