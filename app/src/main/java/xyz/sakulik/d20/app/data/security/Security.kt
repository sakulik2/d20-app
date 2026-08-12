package xyz.sakulik.d20.app.data.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class ApiProtocol {
    DEFAULT,
    ANTHROPIC,
    RESPONSES,
    CHAT_COMPLETIONS
}

/**
 * LLM 密钥管理器接口
 */
interface LlmKeyManager {
    fun saveKey(key: String)
    fun getKey(): String?
    fun hasKey(): Boolean
    fun clearKey()
    fun saveBaseUrl(url: String)
    fun getBaseUrl(): String
    fun saveModel(model: String)
    fun getModel(): String
    fun saveThemeStyle(style: String)
    fun getThemeStyle(): String
    fun saveApiProtocol(protocol: String)
    fun getApiProtocol(): String
    fun saveMaxHistoryTurns(turns: Int)
    fun getMaxHistoryTurns(): Int
}

/**
 * 基于 EncryptedSharedPreferences 的安全密钥管理实现
 * 即使在 Root 设备上，加密存储也能显著提高数据安全性
 */
class EncryptedLlmKeyManager(context: Context) : LlmKeyManager {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "llm_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val KEY_API_KEY = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_API_PROTOCOL = "api_protocol"
        private const val KEY_MAX_HISTORY_TURNS = "max_history_turns"
        private const val DEFAULT_BASE_URL = "https://api.openai.com"
        private const val DEFAULT_MODEL = "gpt-3.5-turbo"
        private const val DEFAULT_MAX_HISTORY_TURNS = 8
    }

    override fun saveKey(key: String) {
        sharedPreferences.edit()
            .putString(KEY_API_KEY, key)
            .apply()
    }

    override fun getKey(): String? {
        return sharedPreferences.getString(KEY_API_KEY, null)
    }

    override fun hasKey(): Boolean {
        return sharedPreferences.contains(KEY_API_KEY) && !sharedPreferences.getString(KEY_API_KEY, null).isNullOrBlank()
    }

    override fun clearKey() {
        sharedPreferences.edit()
            .remove(KEY_API_KEY)
            .apply()
    }

    override fun saveBaseUrl(url: String) {
        sharedPreferences.edit()
            .putString(KEY_BASE_URL, url)
            .apply()
    }

    override fun getBaseUrl(): String {
        return sharedPreferences.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
    }

    override fun saveModel(model: String) {
        sharedPreferences.edit()
            .putString(KEY_MODEL, model)
            .apply()
    }

    override fun getModel(): String {
        return sharedPreferences.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    }

    override fun saveThemeStyle(style: String) {
        sharedPreferences.edit()
            .putString("theme_style", style)
            .apply()
    }

    override fun getThemeStyle(): String {
        return sharedPreferences.getString("theme_style", "AUTO") ?: "AUTO"
    }

    override fun saveApiProtocol(protocol: String) {
        sharedPreferences.edit()
            .putString(KEY_API_PROTOCOL, protocol)
            .apply()
    }

    override fun getApiProtocol(): String {
        return sharedPreferences.getString(KEY_API_PROTOCOL, ApiProtocol.DEFAULT.name) ?: ApiProtocol.DEFAULT.name
    }

    override fun saveMaxHistoryTurns(turns: Int) {
        sharedPreferences.edit()
            .putInt(KEY_MAX_HISTORY_TURNS, turns.coerceIn(4, 30))
            .apply()
    }

    override fun getMaxHistoryTurns(): Int {
        return sharedPreferences.getInt(KEY_MAX_HISTORY_TURNS, DEFAULT_MAX_HISTORY_TURNS)
    }
}
