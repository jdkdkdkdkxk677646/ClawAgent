package com.openclaw.clawagent.provider

/**
 * OpenAI-compatible API provider configuration.
 *
 * All providers in this list speak the OpenAI Chat Completions protocol, so
 * the same request/response code path handles them all. To add a new one,
 * just append a [ProviderCatalog.PROVIDERS] entry — no code change needed.
 */
data class Provider(
    /** Stable id, used in SharedPreferences. e.g. "openai", "zhipu" */
    val id: String,
    /** Display name shown in the settings spinner. */
    val displayName: String,
    /** Default chat completions endpoint. */
    val defaultEndpoint: String,
    /** Recommended model, pre-filled when the user picks this provider. */
    val defaultModel: String,
    /** Common alternative models, shown as hints in the model field. */
    val modelSuggestions: List<String> = emptyList(),
    /** URL to a "how to get an API key" page. Optional. */
    val apiKeyHelpUrl: String? = null,
    /** Whether this provider requires a paid plan / sign-up. */
    val requiresApiKey: Boolean = true,
)
