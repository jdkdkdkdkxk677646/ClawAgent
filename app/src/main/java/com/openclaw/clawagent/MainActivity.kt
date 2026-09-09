package com.openclaw.clawagent

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.openclaw.clawagent.databinding.ActivityMainBinding
import com.openclaw.clawagent.databinding.DialogSettingsBinding
import com.openclaw.clawagent.provider.ChatService
import com.openclaw.clawagent.provider.Provider
import com.openclaw.clawagent.provider.ProviderCatalog
import com.openclaw.clawagent.provider.SecurePrefs
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: MessageAdapter
    private val messages = mutableListOf<ChatMessage>()
    private var isSending = false

    private lateinit var prefs: SecurePrefs
    private val chatService = ChatService()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SecurePrefs(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        loadSettings()
        loadHistory()
        binding.inputField.requestFocus()
    }

    private fun setupRecyclerView() {
        adapter = MessageAdapter(messages)
        binding.recyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.recyclerView.adapter = adapter
    }

    private fun setupListeners() {
        binding.sendBtn.setOnClickListener { sendMessage() }
        binding.inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }
        binding.settingsBtn.setOnClickListener { showSettings() }
        binding.newChatBtn.setOnClickListener { startNewChat() }
    }

    private fun updateChatVisibility() {
        if (messages.isEmpty()) {
            binding.welcomeLayout.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        } else {
            binding.welcomeLayout.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }
    }

    private fun sendMessage() {
        if (isSending) return
        val text = binding.inputField.text.toString().trim()
        if (text.isEmpty()) return

        isSending = true
        binding.sendBtn.isEnabled = false
        binding.inputField.setText("")

        messages.add(ChatMessage("user", text))
        adapter.notifyItemInserted(messages.size - 1)
        updateChatVisibility()
        scrollToBottom()

        // Empty placeholder that the streaming response will fill in.
        val assistantMsg = ChatMessage("assistant", "")
        messages.add(assistantMsg)
        val assistantIndex = messages.size - 1
        adapter.notifyItemInserted(assistantIndex)
        scrollToBottom()

        lifecycleScope.launch {
            try {
                val provider = ProviderCatalog.findById(prefs.providerId)
                val apiKey = prefs.getApiKey()

                if (provider.requiresApiKey && apiKey.isEmpty()) {
                    simulateResponse(text, assistantMsg)
                    return@launch
                }

                val history = if (prefs.keepContext) {
                    messages.dropLast(1).map {
                        ChatService.Message(it.role, it.content)
                    }
                } else {
                    listOf(ChatService.Message("user", text))
                }

                chatService.streamChat(
                    endpoint = prefs.endpoint,
                    apiKey = apiKey,
                    model = prefs.model,
                    history = history,
                    stream = prefs.streamOutput,
                ).collect { event ->
                    when (event) {
                        is ChatService.StreamEvent.Delta -> {
                            assistantMsg.content += event.text
                            adapter.notifyItemChanged(assistantIndex)
                            scrollToBottom()
                        }
                        is ChatService.StreamEvent.Error -> {
                            assistantMsg.content = "⚠️ ${event.message}"
                            adapter.notifyItemChanged(assistantIndex)
                        }
                        ChatService.StreamEvent.Done -> {
                            // Final scroll once the stream is fully consumed.
                            scrollToBottom()
                        }
                    }
                }
            } catch (e: Exception) {
                assistantMsg.content = "⚠️ 出错了:${e.message}\n\n请检查网络和服务商配置。"
                adapter.notifyItemChanged(assistantIndex)
            } finally {
                saveHistory()
                isSending = false
                binding.sendBtn.isEnabled = true
                binding.inputField.requestFocus()
                scrollToBottom()
            }
        }
    }

    private suspend fun simulateResponse(text: String, msg: ChatMessage) {
        val reply = when {
            text.contains("你好") || text.lowercase().contains("hello") || text.lowercase().contains("hi") ->
                "你好呀!👋\n\n我是 **Claw Agent** 🦀\n\n我目前运行在演示模式。配置 API Key 后就能使用完整 AI 功能了!"

            text.contains("天气") ->
                "🌤️ 天气查询\n\n演示模式下无法获取实时天气。配置 API Key 后,我就能帮你查询全球任意城市的天气啦!"

            text.contains("笑话") || text.contains("搞笑") ->
                "😄 来一个:\n\n为什么程序员总是分不清万圣节和圣诞节?\n\n因为 **Oct 31 == Dec 25** 🎃🎄\n\n(八进制的 31 = 十进制的 25)"

            text.contains("代码") || text.lowercase().contains("python") || text.contains("编程") ->
                "🐍 快速排序示例:\n\n```python\ndef quick_sort(arr):\n    if len(arr) <= 1: return arr\n    pivot = arr[len(arr) // 2]\n    left = [x for x in arr if x < pivot]\n    middle = [x for x in arr if x == pivot]\n    right = [x for x in arr if x > pivot]\n    return quick_sort(left) + middle + quick_sort(right)\n```"

            text.contains("量子") ->
                "⚛️ **量子计算** 简介\n\n• **量子比特** — 可同时处于 0 和 1 的叠加态\n• **叠加** — 同时表示多种状态\n• **纠缠** — 两个比特的神秘关联\n• **量子干涉** — 放大正确答案概率\n\n配置 API Key 后可获取更详细解释!"

            else ->
                "你好!我是 **Claw Agent** 🦀\n\n我目前运行在演示模式。请在设置中配置 API Key 来解锁完整 AI 能力。\n\n支持的服务商:OpenAI / DeepSeek / 通义千问 / Moonshot / 智谱 / Stepfun / OpenRouter / Pollinations / Ollama 等。"
        }

        // Typewriter effect.
        for (i in 1..reply.length) {
            msg.content = reply.substring(0, i)
            adapter.notifyItemChanged(messages.size - 1)
            scrollToBottom()
            kotlinx.coroutines.delay(15)
        }
    }

    private fun scrollToBottom() {
        if (messages.isEmpty()) return
        binding.recyclerView.post {
            binding.recyclerView.smoothScrollToPosition(messages.size - 1)
        }
    }

    private fun showSettings() {
        val dialogBinding = DialogSettingsBinding.inflate(layoutInflater)
        val current = ProviderCatalog.findById(prefs.providerId)
        val providerNames = ProviderCatalog.PROVIDERS.map { it.displayName }

        // Provider dropdown
        val adapterSpinner = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            providerNames,
        )
        dialogBinding.providerDropdown.setAdapter(adapterSpinner)
        dialogBinding.providerDropdown.setText(current.displayName, false)

        fun applyProvider(p: Provider) {
            dialogBinding.modelEdit.setText(p.defaultModel)
            dialogBinding.endpointEdit.setText(p.defaultEndpoint)
            dialogBinding.providerHelp.text = p.apiKeyHelpUrl?.let { "获取 API Key:$it" } ?: ""
            // For providers that don't require auth, hide the Key field.
            dialogBinding.apiKeyLayout.visibility =
                if (p.requiresApiKey) View.VISIBLE else View.GONE
        }
        applyProvider(current)

        dialogBinding.providerDropdown.setOnItemClickListener { _, _, position, _ ->
            val picked = ProviderCatalog.PROVIDERS[position]
            applyProvider(picked)
        }

        dialogBinding.apiKeyEdit.setText(prefs.getApiKey())
        dialogBinding.contextCheckbox.isChecked = prefs.keepContext
        dialogBinding.streamCheckbox.isChecked = prefs.streamOutput

        AlertDialog.Builder(this)
            .setTitle("⚙️ 设置")
            .setView(dialogBinding.root)
            .setPositiveButton("保存") { _, _ ->
                val pickedName = dialogBinding.providerDropdown.text.toString()
                val picked = ProviderCatalog.findByName(pickedName) ?: current
                prefs.providerId = picked.id
                prefs.model = dialogBinding.modelEdit.text.toString().trim()
                    .ifEmpty { picked.defaultModel }
                prefs.endpoint = dialogBinding.endpointEdit.text.toString().trim()
                    .ifEmpty { picked.defaultEndpoint }
                prefs.setApiKey(dialogBinding.apiKeyEdit.text.toString().trim())
                prefs.keepContext = dialogBinding.contextCheckbox.isChecked
                prefs.streamOutput = dialogBinding.streamCheckbox.isChecked
                Toast.makeText(this, "已保存:${picked.displayName}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startNewChat() {
        messages.clear()
        adapter.notifyDataSetChanged()
        saveHistory()
        updateChatVisibility()
        binding.inputField.requestFocus()
    }

    private fun loadSettings() {
        // All settings are read on demand via [prefs]. The user might have
        // changed defaults; we don't need to copy them into fields here.
    }

    private fun saveHistory() {
        val json = org.json.JSONArray()
        messages.forEach {
            json.put(org.json.JSONObject().apply {
                put("role", it.role)
                put("content", it.content)
            })
        }
        getSharedPreferences("claw_history", MODE_PRIVATE).edit()
            .putString("history", json.toString())
            .apply()
    }

    private fun loadHistory() {
        val jsonStr = getSharedPreferences("claw_history", MODE_PRIVATE)
            .getString("history", null) ?: return
        try {
            val json = org.json.JSONArray(jsonStr)
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                messages.add(ChatMessage(obj.getString("role"), obj.getString("content")))
            }
            adapter.notifyDataSetChanged()
            updateChatVisibility()
            if (messages.isNotEmpty()) scrollToBottom()
        } catch (_: Exception) {}
    }

    @Suppress("UNUSED")
    fun sendQuick(view: View) {
        val text = when (view.id) {
            R.id.btnQuick1 -> "今天天气怎么样?"
            R.id.btnQuick2 -> "给我讲个笑话"
            R.id.btnQuick3 -> "帮我写一段Python代码"
            R.id.btnQuick4 -> "解释一下量子计算"
            else -> ""
        }
        if (text.isNotEmpty()) {
            binding.inputField.setText(text)
            sendMessage()
        }
    }
}
