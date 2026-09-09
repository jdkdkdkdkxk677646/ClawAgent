package com.openclaw.clawagent

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.openclaw.clawagent.databinding.ActivityMainBinding
import com.openclaw.clawagent.databinding.DialogSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: MessageAdapter
    private val messages = mutableListOf<ChatMessage>()
    private var apiKey = ""
    private var apiEndpoint = "https://api.stepfun.com/v1/chat/completions"
    private var selectedModel = "step-3.7-flash"
    private var keepContext = true
    private var streamOutput = true
    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSettings()
        setupRecyclerView()
        setupListeners()
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

    private fun sendMessage() {
        val text = binding.inputField.text.toString().trim()
        if (text.isEmpty()) return

        isSending = true
        binding.sendBtn.isEnabled = false
        binding.inputField.setText("")

        // Add user message
        messages.add(ChatMessage("user", text))
        adapter.notifyItemInserted(messages.size - 1)
        scrollToBottom()

        // Create assistant placeholder
        val assistantMsg = ChatMessage("assistant", "")
        messages.add(assistantMsg)
        adapter.notifyItemInserted(messages.size - 1)
        scrollToBottom()

        lifecycleScope.launch {
            try {
                val key = apiKey.trim()
                if (key.isEmpty()) {
                    simulateResponse(text, assistantMsg)
                    return@launch
                }

                val chatMessages = if (keepContext) {
                    messages.dropLast(1).map { JSONObject().apply {
                        put("role", it.role)
                        put("content", it.content)
                    }}
                } else {
                    listOf(JSONObject().apply {
                        put("role", "user")
                        put("content", text)
                    })
                }

                val bodyJson = JSONObject().apply {
                    put("model", selectedModel)
                    put("messages", JSONArray(chatMessages))
                    put("stream", streamOutput)
                }

                val body = bodyJson.toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(apiEndpoint)
                    .addHeader("Authorization", "Bearer $key")
                    .post(body)
                    .build()

                val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }

                if (!response.isSuccessful) {
                    assistantMsg.content = "⚠️ 请求失败：${response.code} ${response.message}\n请检查 API 配置。"
                    adapter.notifyItemChanged(messages.size - 1)
                    return@launch
                }

                if (streamOutput) {
                    streamResponse(response, assistantMsg)
                } else {
                    val respBody = withContext(Dispatchers.IO) { response.body?.string() ?: "" }
                    val json = JSONObject(respBody)
                    val reply = json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                    assistantMsg.content = reply
                    adapter.notifyItemChanged(messages.size - 1)
                }
            } catch (e: Exception) {
                assistantMsg.content = "⚠️ 出错了：${e.message}\n\n请检查网络连接和 API 配置。"
                adapter.notifyItemChanged(messages.size - 1)
            } finally {
                saveHistory()
                isSending = false
                binding.sendBtn.isEnabled = true
                binding.inputField.requestFocus()
                scrollToBottom()
            }
        }
    }

    private fun streamResponse(response: Response, msg: ChatMessage) {
        val source = response.body?.source() ?: return
        val reader = okio.Buffer()

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    while (!source.exhausted()) {
                        reader.clear()
                        source.read(reader, 8192)
                        val chunk = reader.readString(Charsets.UTF_8)
                        val lines = chunk.split("\n")

                        for (line in lines) {
                            if (line.startsWith("data: ") && line != "data: [DONE]") {
                                try {
                                    val json = JSONObject(line.removePrefix("data: ").trim())
                                    val delta = json.getJSONArray("choices")
                                        .getJSONObject(0)
                                        .getJSONObject("delta")
                                        .optString("content", "")

                                    if (delta.isNotEmpty()) {
                                        msg.content += delta
                                        withContext(Dispatchers.Main) {
                                            adapter.notifyItemChanged(messages.size - 1)
                                            scrollToBottom()
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun simulateResponse(text: String, msg: ChatMessage) {
        val reply = withContext(Dispatchers.IO) {
            val lower = text.lowercase()
            when {
                lower.contains("你好") || lower.contains("hello") || lower.contains("hi") ->
                    "你好呀！👋\n\n我是 **Claw Agent** 🦀\n\n我目前运行在演示模式。配置 API Key 后就能使用完整 AI 功能了！"

                lower.contains("天气") ->
                    "🌤️ 天气查询\n\n演示模式下无法获取实时天气。配置 API Key 后，我就能帮你查询全球任意城市的天气啦！"

                lower.contains("笑话") || lower.contains("搞笑") ->
                    "😄 来一个：\n\n为什么程序员总是分不清万圣节和圣诞节？\n\n因为 **Oct 31 == Dec 25** 🎃🎄\n\n（八进制的 31 = 十进制的 25）"

                lower.contains("代码") || lower.contains("python") || lower.contains("编程") ->
                    "🐍 快速排序示例：\n\n```python\ndef quick_sort(arr):\n    if len(arr) <= 1: return arr\n    pivot = arr[len(arr) // 2]\n    left = [x for x in arr if x < pivot]\n    middle = [x for x in arr if x == pivot]\n    right = [x for x in arr if x > pivot]\n    return quick_sort(left) + middle + quick_sort(right)\n```"

                lower.contains("量子") ->
                    "⚛️ **量子计算** 简介\n\n• **量子比特** — 可同时处于 0 和 1 的叠加态\n• **叠加** — 同时表示多种状态\n• **纠缠** — 两个比特的神秘关联\n• **量子干涉** — 放大正确答案概率\n\n配置 API Key 后可获取更详细解释！"

                else ->
                    "你好！我是 **Claw Agent** 🦀\n\n我目前运行在演示模式。请在设置中配置 API Key 来解锁完整 AI 能力。\n\n不过我还是可以陪你聊天！有什么想说的吗？"
            }
        }

        // Typewriter effect
        for (i in 1..reply.length) {
            msg.content = reply.substring(0, i)
            withContext(Dispatchers.Main) {
                adapter.notifyItemChanged(messages.size - 1)
                scrollToBottom()
            }
            kotlinx.coroutines.delay(15)
        }
    }

    private fun scrollToBottom() {
        binding.recyclerView.post {
            binding.recyclerView.smoothScrollToPosition(messages.size - 1)
        }
    }

    private fun showSettings() {
        val dialogBinding = DialogSettingsBinding.inflate(layoutInflater)
        dialogBinding.apply {
            modelEdit.setText(selectedModel)
            endpointEdit.setText(apiEndpoint)
            apiKeyEdit.setText(apiKey)
            contextCheckbox.isChecked = keepContext
            streamCheckbox.isChecked = streamOutput
        }

        AlertDialog.Builder(this)
            .setTitle("⚙️ 设置")
            .setView(dialogBinding.root)
            .setPositiveButton("保存") { _, _ ->
                selectedModel = dialogBinding.modelEdit.text.toString().trim()
                apiEndpoint = dialogBinding.endpointEdit.text.toString().trim()
                apiKey = dialogBinding.apiKeyEdit.text.toString().trim()
                keepContext = dialogBinding.contextCheckbox.isChecked
                streamOutput = dialogBinding.streamCheckbox.isChecked
                saveSettings()
                Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startNewChat() {
        messages.clear()
        adapter.notifyDataSetChanged()
        saveHistory()
        binding.inputField.requestFocus()
    }

    private fun saveSettings() {
        getSharedPreferences("claw_settings", MODE_PRIVATE).edit().apply {
            putString("api_key", apiKey)
            putString("api_endpoint", apiEndpoint)
            putString("model", selectedModel)
            putBoolean("keep_context", keepContext)
            putBoolean("stream", streamOutput)
            apply()
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("claw_settings", MODE_PRIVATE)
        apiKey = prefs.getString("api_key", "") ?: ""
        apiEndpoint = prefs.getString("api_endpoint", "https://api.stepfun.com/v1/chat/completions") ?: apiEndpoint
        selectedModel = prefs.getString("model", "step-3.7-flash") ?: selectedModel
        keepContext = prefs.getBoolean("keep_context", true)
        streamOutput = prefs.getBoolean("stream", true)
    }

    private fun saveHistory() {
        val json = JSONArray()
        messages.forEach { json.put(JSONObject().apply {
            put("role", it.role)
            put("content", it.content)
        })}
        getSharedPreferences("claw_history", MODE_PRIVATE).edit()
            .putString("history", json.toString())
            .apply()
    }

    private fun loadHistory() {
        val jsonStr = getSharedPreferences("claw_history", MODE_PRIVATE)
            .getString("history", null) ?: return
        try {
            val json = JSONArray(jsonStr)
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                messages.add(ChatMessage(obj.getString("role"), obj.getString("content")))
            }
            adapter.notifyDataSetChanged()
            if (messages.isNotEmpty()) scrollToBottom()
        } catch (_: Exception) {}
    }

    @Suppress("UNUSED")
    fun sendQuick(view: View) {
        val text = when (view.id) {
            R.id.btnQuick1 -> "今天天气怎么样？"
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

    companion object {
        private var isSending = false
    }
}
