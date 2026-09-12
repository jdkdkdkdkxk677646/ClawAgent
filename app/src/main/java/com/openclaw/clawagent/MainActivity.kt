package com.openclaw.clawagent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.openclaw.clawagent.agent.AgentWiring
import com.openclaw.clawagent.provider.ProviderHealthCache
import com.openclaw.clawagent.provider.ProviderHealthChecker
import com.openclaw.clawagent.provider.SecurePrefs
import com.openclaw.clawagent.task.ChatRepository
import com.openclaw.clawagent.ui.ClawColors
import com.openclaw.clawagent.ui.ChatEffect
import com.openclaw.clawagent.ui.ChatIntent
import com.openclaw.clawagent.ui.ChatScreen
import com.openclaw.clawagent.ui.ChatViewModel
import com.openclaw.clawagent.ui.SettingsDialog
import kotlinx.coroutines.launch

/**
 * v4.1:全 Compose 宿主。
 *
 * 业务逻辑全部在 [ChatViewModel](MVI:Intent → State/Effect);消息列表通过
 * AndroidView 复用 [MessageAdapter] 的 Markdown/表格渲染管线(DiffUtil 增量
 * 刷新);设置对话框为 Compose 版([SettingsDialog],v4.1 迁完)。
 */
class MainActivity : ComponentActivity() {

    private lateinit var prefs: SecurePrefs
    private val healthChecker = ProviderHealthChecker()
    private val healthCache = ProviderHealthCache()

    // 设置对话框需要工具清单;与 VM 内的实例各自独立(工具无共享可变状态,
    // NoteTool 共享同一磁盘目录,行为一致)。
    private val toolbox by lazy { AgentWiring.forAndroid(this) }

    private val vm: ChatViewModel by viewModels { ChatViewModel.factory(this) }

    // v4.1:设置对话框改为 Compose 状态驱动(原 ViewBinding 版已迁)。
    private var showSettingsDialog by mutableStateOf(false)

    // 消息列表:RecyclerView + MessageAdapter(ListAdapter/DiffUtil 增量刷新)
    private lateinit var adapter: MessageAdapter
    private lateinit var recyclerView: RecyclerView

    private val galleryPicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> vm.onGalleryResult(uri) }

    private val takePicture = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok -> vm.onCameraResult(ok) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SecurePrefs(this)

        adapter = MessageAdapter(
            onCopy = { text ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Claw Agent", text))
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            },
            onMessageLongClick = { position, _ -> showForkMenu(position) },
        )

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = ClawColors.Bg) {
                    ChatScreen(
                        state = vm.state.collectAsStateWithLifecycle().value,
                        onIntent = vm::onIntent,
                        onOpenSettings = { showSettingsDialog = true },
                        onAttachClick = {
                            galleryPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onAttachLongClick = {
                            vm.prepareCamera()?.let { effect ->
                                runCatching { takePicture.launch(effect.uri) }
                                    .onFailure { vm.onCameraResult(false) }
                            }
                        },
                        messagesList = { MessagesList() },
                    )

                    if (showSettingsDialog) {
                        SettingsDialog(
                            prefs = prefs,
                            toolboxNames = toolbox.names,
                            healthChecker = healthChecker,
                            healthCache = healthCache,
                            todayUsage = vm.state.value.todayUsage,
                            onDismiss = { showSettingsDialog = false },
                            onSaved = {
                                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                                vm.refreshFromPrefs()
                            },
                        )
                    }
                }
            }
        }

        // 副作用:Toast / 选择器 / 剪贴板 / 滚动
        lifecycleScope.launch {
            vm.effects.collect { effect ->
                when (effect) {
                    is ChatEffect.Toast ->
                        Toast.makeText(this@MainActivity, effect.message, Toast.LENGTH_SHORT).show()
                    is ChatEffect.LaunchGalleryPicker ->
                        galleryPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    is ChatEffect.LaunchCamera -> {
                        runCatching { takePicture.launch(effect.uri) }
                            .onFailure { vm.onCameraResult(false) }
                    }
                    is ChatEffect.CopyToClipboard -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Claw Agent", effect.text))
                        Toast.makeText(this@MainActivity, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }
                    is ChatEffect.ScrollToBottom ->
                        recyclerView.post { recyclerView.scrollToPosition(adapter.itemCount - 1) }
                    is ChatEffect.OpenSettings -> showSettingsDialog = true
                }
            }
        }

        // state → adapter(DiffUtil 增量刷新;流式打字只重绑最后一条)
        lifecycleScope.launch {
            vm.state.collect { st ->
                adapter.submitList(st.messages)
            }
        }

        // T-202:冷启动直接来自分享时,onCreate 先兜一次(紧随的 onStart 也会兜,幂等)。
        consumePendingShare()
    }

    @androidx.compose.runtime.Composable
    private fun MessagesList() {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                RecyclerView(ctx).apply {
                    layoutManager = LinearLayoutManager(ctx).apply { stackFromEnd = true }
                    adapter = this@MainActivity.adapter
                    recyclerView = this
                }
            },
            update = { rv -> rv.post { rv.scrollToPosition(adapter.itemCount - 1) } },
        )
    }

    override fun onStart() {
        super.onStart()
        // 回前台:后台任务(Service)可能已写入新消息,重读共享会话树。
        vm.onIntent(ChatIntent.ReloadFromRepository)
        // T-202:冷/热启动都把系统分享进来的文本填成草稿。
        consumePendingShare()
    }

    /**
     * T-202:消费系统分享槽——把文本交给 ViewModel 填成输入框草稿,然后清槽。
     * 只填草稿、不自动发送(用户需确认/补充)。槽为空时无副作用。
     */
    private fun consumePendingShare() {
        ChatRepository.pendingShare?.let { text ->
            ChatRepository.pendingShare = null
            vm.onIntent(ChatIntent.SetDraft(text))
        }
    }

    private fun showForkMenu(position: Int) {
        val items = arrayOf("🌿 从这里重开", "📋 复制")
        showItemMenu(items, position)
    }

    private fun showItemMenu(items: Array<String>, position: Int) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("消息操作")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> vm.onIntent(ChatIntent.ForkAt(position))
                    1 -> vm.onIntent(ChatIntent.CopyAt(position))
                }
            }
            .show()
    }
}
