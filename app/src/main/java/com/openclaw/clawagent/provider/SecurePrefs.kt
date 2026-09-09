package com.openclaw.clawagent.provider

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the API key in [EncryptedSharedPreferences] (AES-256, key wrapped by
 * Android Keystore) and non-secret settings (provider, model, endpoint, flags)
 * in a plain SharedPreferences file.
 *
 * Why two files?
 *   - The Android Keystore / EncryptedSharedPreferences path is heavier and
 *     can fail on devices with a broken keystore. Mixing non-secret values in
 *     would mean losing all settings when keystore access breaks.
 *   - Keeping the API key separate also means we can nuke it independently
 *     (e.g. on logout) without touching the user's chosen provider.
 */
class SecurePrefs(context: Context) {

    private val plain: SharedPreferences =
        context.getSharedPreferences(PLAIN_NAME, Context.MODE_PRIVATE)

    private val encrypted: SharedPreferences? = try {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_NAME,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        // If the keystore is unavailable (corrupt profile, weird OEM),
        // fall back to no encrypted storage. API key simply won't persist.
        null
    }

    // ----- Provider / model / endpoint (plain) -----

    var providerId: String
        get() = plain.getString(KEY_PROVIDER_ID, ProviderCatalog.DEFAULT_ID)
            ?: ProviderCatalog.DEFAULT_ID
        set(value) = plain.edit().putString(KEY_PROVIDER_ID, value).apply()

    var model: String
        get() = plain.getString(KEY_MODEL, null) ?: ProviderCatalog.findById(providerId).defaultModel
        set(value) = plain.edit().putString(KEY_MODEL, value).apply()

    var endpoint: String
        get() = plain.getString(KEY_ENDPOINT, null) ?: ProviderCatalog.findById(providerId).defaultEndpoint
        set(value) = plain.edit().putString(KEY_ENDPOINT, value).apply()

    var keepContext: Boolean
        get() = plain.getBoolean(KEY_KEEP_CONTEXT, true)
        set(value) = plain.edit().putBoolean(KEY_KEEP_CONTEXT, value).apply()

    var streamOutput: Boolean
        get() = plain.getBoolean(KEY_STREAM, true)
        set(value) = plain.edit().putBoolean(KEY_STREAM, value).apply()

    /**
     * How many trailing history messages to send with each request.
     * 0 = unlimited. Capped so long conversations don't blow up token
     * usage and request size.
     */
    var contextLimit: Int
        get() = plain.getInt(KEY_CONTEXT_LIMIT, DEFAULT_CONTEXT_LIMIT)
        set(value) = plain.edit().putInt(KEY_CONTEXT_LIMIT, value).apply()

    // ----- API key (encrypted, when available) -----

    /** Returns the persisted API key, or "" if unset or encrypted storage failed. */
    fun getApiKey(): String =
        encrypted?.getString(KEY_API_KEY, null)
            ?: plain.getString(KEY_API_KEY_FALLBACK, null)
            ?: ""

    /**
     * Persist the API key. Clears any plain-text fallback copy when storing to
     * the encrypted store, so we don't keep a cleartext duplicate on disk.
     */
    fun setApiKey(value: String) {
        if (value.isEmpty()) {
            encrypted?.edit()?.remove(KEY_API_KEY)?.apply()
            plain.edit().remove(KEY_API_KEY_FALLBACK).apply()
            return
        }
        val target = encrypted
        if (target != null) {
            target.edit().putString(KEY_API_KEY, value).apply()
            // Wipe the legacy cleartext copy if it ever existed.
            plain.edit().remove(KEY_API_KEY_FALLBACK).apply()
        } else {
            // Keystore broken: best effort, plain storage. Logged on the caller
            // side so the user knows their key isn't protected.
            plain.edit().putString(KEY_API_KEY_FALLBACK, value).apply()
        }
    }

    fun isApiKeyStored(): Boolean = getApiKey().isNotEmpty()

    companion object {
        private const val PLAIN_NAME = "claw_settings"
        private const val SECURE_NAME = "claw_secure"

        private const val KEY_PROVIDER_ID = "provider_id"
        private const val KEY_MODEL = "model"
        private const val KEY_ENDPOINT = "api_endpoint"
        private const val KEY_KEEP_CONTEXT = "keep_context"
        private const val KEY_STREAM = "stream"
        private const val KEY_CONTEXT_LIMIT = "context_limit"

        const val DEFAULT_CONTEXT_LIMIT = 20

        private const val KEY_API_KEY = "api_key"
        // Legacy key from the original release. Read once for migration, then
        // removed on next setApiKey().
        private const val KEY_API_KEY_FALLBACK = "api_key_legacy"
    }
}
