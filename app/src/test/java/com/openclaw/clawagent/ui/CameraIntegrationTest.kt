package com.openclaw.clawagent.ui

import android.content.Context
import android.net.Uri
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * T-303 拍照输入的 Compose 整合测试。
 *
 * 相机的真实往返(`ActivityResultContracts.TakePicture`)无法单测,这里覆盖 VM
 * 侧的管道契约:`prepareCamera()` 产出的 FileProvider URI 与临时文件、
 * `onCameraResult()` 的成功 / 取消 / 超限三条分支。
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

    /** The temp file backing [uri] (FileProvider root is cacheDir/photos). */
    private fun photoFile(uri: Uri): File =
        File(File(context.cacheDir, "photos"), uri.lastPathSegment!!)

    @Test
    fun `prepareCamera yields a fileprovider uri under cache photos`() {
        val vm = viewModel()
        val uri = vm.prepareCamera()

        assertNotNull(uri)
        assertEquals("${context.packageName}.fileprovider", uri!!.authority)
        assertTrue("临时文件目录应为 cacheDir/photos", photoFile(uri).parentFile!!.isDirectory)
    }

    @Test
    fun `cancelled capture does not stage an image`() {
        val vm = viewModel()
        assertNotNull(vm.prepareCamera())

        vm.onCameraResult(false)

        assertEquals(0, vm.state.value.stagedImageCount)
    }

    @Test
    fun `oversized photo is rejected without staging`() {
        val vm = viewModel()
        val uri = vm.prepareCamera()!!
        photoFile(uri).writeBytes(ByteArray(4 * 1024 * 1024 + 1))

        vm.onCameraResult(true)

        assertEquals(0, vm.state.value.stagedImageCount)
    }

    @Test
    fun `successful capture stages one image`() = runBlocking {
        val vm = viewModel()
        val uri = vm.prepareCamera()!!
        // A tiny fake JPEG payload.
        photoFile(uri).writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02))

        vm.onCameraResult(true)

        awaitUntil { vm.state.value.stagedImageCount == 1 }
        assertEquals(1, vm.state.value.stagedImageCount)
    }
}
