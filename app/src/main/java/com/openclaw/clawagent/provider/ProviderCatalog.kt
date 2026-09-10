package com.openclaw.clawagent.provider

/**
 * Catalog of pre-configured OpenAI-compatible API providers.
 *
 * To add a new provider (e.g. 智谱 BigModel, 字节豆包, 腾讯混元 ...), just
 * append a [Provider] entry below. Nothing else in the app needs to change.
 */
object ProviderCatalog {

    val DEFAULT_ID = "openai"

    val PROVIDERS: List<Provider> = listOf(
        Provider(
            id = "openai",
            displayName = "OpenAI",
            defaultEndpoint = "https://api.openai.com/v1/chat/completions",
            defaultModel = "gpt-4o-mini",
            modelSuggestions = listOf("gpt-4o-mini", "gpt-4o", "gpt-4-turbo", "o1-mini", "o1-preview"),
            apiKeyHelpUrl = "https://platform.openai.com/api-keys",
        ),
        Provider(
            id = "deepseek",
            displayName = "DeepSeek",
            defaultEndpoint = "https://api.deepseek.com/v1/chat/completions",
            defaultModel = "deepseek-chat",
            modelSuggestions = listOf("deepseek-chat", "deepseek-reasoner"),
            apiKeyHelpUrl = "https://platform.deepseek.com/api_keys",
        ),
        Provider(
            id = "dashscope",
            displayName = "通义千问 (DashScope)",
            defaultEndpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            defaultModel = "qwen-plus",
            modelSuggestions = listOf("qwen-turbo", "qwen-plus", "qwen-max", "qwen-long"),
            apiKeyHelpUrl = "https://dashscope.console.aliyun.com/apiKey",
        ),
        Provider(
            id = "moonshot",
            displayName = "Moonshot (Kimi)",
            defaultEndpoint = "https://api.moonshot.cn/v1/chat/completions",
            defaultModel = "moonshot-v1-8k",
            modelSuggestions = listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"),
            apiKeyHelpUrl = "https://platform.moonshot.cn/console/api-keys",
        ),
        Provider(
            id = "zhipu",
            displayName = "智谱 BigModel",
            defaultEndpoint = "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            defaultModel = "glm-4-flash",
            modelSuggestions = listOf("glm-4-flash", "glm-4-air", "glm-4-airx", "glm-4-plus", "glm-4-long"),
            apiKeyHelpUrl = "https://bigmodel.cn/usercenter/apikeys",
        ),
        Provider(
            id = "stepfun",
            displayName = "Stepfun (阶跃星辰)",
            defaultEndpoint = "https://api.stepfun.com/v1/chat/completions",
            defaultModel = "step-3.7-flash",
            modelSuggestions = listOf("step-3.7-flash", "step-1-8k", "step-1-32k", "step-1-128k"),
            apiKeyHelpUrl = "https://platform.stepfun.com/keys",
        ),
        Provider(
            id = "agnes",
            displayName = "Agnes AI",
            defaultEndpoint = "https://api.agnes-ai.cn/v1/chat/completions",
            defaultModel = "",
            modelSuggestions = emptyList(),
            apiKeyHelpUrl = null,
        ),
        Provider(
            id = "openrouter",
            displayName = "OpenRouter",
            defaultEndpoint = "https://openrouter.ai/api/v1/chat/completions",
            defaultModel = "openai/gpt-4o-mini",
            // OpenRouter is a router across many providers; you usually pick
            // "vendor/model" here. These are common cheap defaults.
            modelSuggestions = listOf(
                "openai/gpt-4o-mini",
                "anthropic/claude-3.5-haiku",
                "google/gemini-2.0-flash-exp:free",
                "meta-llama/llama-3.3-70b-instruct:free",
                "deepseek/deepseek-chat:free",
            ),
            apiKeyHelpUrl = "https://openrouter.ai/settings/keys",
        ),
        Provider(
            id = "pollinations",
            displayName = "Pollinations",
            defaultEndpoint = "https://gen.pollinations.ai/v1/chat/completions",
            defaultModel = "openai-fast",
            // Pollinations has a generous free tier; auth is optional but a
            // token raises the rate limit.
            modelSuggestions = listOf("openai-fast", "openai", "openai-large", "mistral", "llama"),
            requiresApiKey = false,
            apiKeyHelpUrl = "https://auth.pollinations.ai/",
        ),
        Provider(
            id = "ollama",
            displayName = "Ollama (本地)",
            // 10.0.2.2 is the Android emulator's alias for the host machine.
            // Physical device users should change this to their host LAN IP.
            defaultEndpoint = "http://10.0.2.2:11434/v1/chat/completions",
            defaultModel = "llama3.2",
            modelSuggestions = listOf("llama3.2", "qwen2.5", "mistral", "gemma2", "phi3"),
            requiresApiKey = false,
            // Ollama exposes its model list at /api/tags, not /v1/models.
            healthEndpoint = "http://10.0.2.2:11434/api/tags",
        ),
        // ========== 新增预设 (T-104) ==========
        // SiliconFlow - 硅基流动（OpenAI 兼容）
        Provider(
            id = "siliconflow",
            displayName = "硅基流动 (SiliconFlow)",
            defaultEndpoint = "https://api.siliconflow.cn/v1/chat/completions",
            defaultModel = "Qwen/Qwen2.5-7B-Instruct",
            modelSuggestions = listOf(
                "Qwen/Qwen2.5-7B-Instruct",
                "Qwen/Qwen2.5-72B-Instruct",
                "deepseek-ai/DeepSeek-V3",
                "THUDM/GLM-4-9B-Chat",
            ),
            apiKeyHelpUrl = "https://cloud.siliconflow.cn/account/ak",
        ),
        // Kimi 直连（月之暗面，api.moonshot.ai 推理域名，区别于现有 moonshot 代理域名）
        Provider(
            id = "kimi",
            displayName = "Kimi (月之暗面直连)",
            defaultEndpoint = "https://api.moonshot.ai/v1/chat/completions",
            defaultModel = "kimi-k2.6",
            modelSuggestions = listOf(
                "kimi-k2.6",
                "moonshot-v1-8k",
                "moonshot-v1-32k",
                "moonshot-v1-128k",
            ),
            apiKeyHelpUrl = "https://platform.moonshot.cn/console/api-keys",
        ),
        // Groq - OpenAI 兼容推理服务
        Provider(
            id = "groq",
            displayName = "Groq",
            defaultEndpoint = "https://api.groq.com/openai/v1/chat/completions",
            defaultModel = "llama-3.1-8b-instant",
            modelSuggestions = listOf(
                "llama-3.1-8b-instant",
                "llama-3.1-70b-versatile",
                "mixtral-8x7b-32768",
                "gemma2-9b-it",
            ),
            apiKeyHelpUrl = "https://console.groq.com/keys",
        ),
    )

    fun findById(id: String?): Provider =
        PROVIDERS.firstOrNull { it.id == id } ?: findById(DEFAULT_ID)!!

    fun findByName(name: String?): Provider? =
        PROVIDERS.firstOrNull { it.displayName == name }
}
