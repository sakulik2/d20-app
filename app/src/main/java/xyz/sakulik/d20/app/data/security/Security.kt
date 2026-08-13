package xyz.sakulik.d20.app.data.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import xyz.sakulik.d20.app.data.model.ConversationMemoryPolicy

enum class ApiProtocol {
    DEFAULT,
    ANTHROPIC,
    RESPONSES,
    CHAT_COMPLETIONS
}

enum class ReasoningEffort {
    AUTO,
    LOW,
    MEDIUM,
    HIGH;

    fun promptGuidance(): String = when (this) {
        AUTO -> ""
        LOW -> "优先快速响应；只做完成当前回合所需的最少推理，不展开无关分析。"
        MEDIUM -> "在响应速度与规则可靠性之间保持平衡；检查关键约束后直接作答。"
        HIGH -> "优先完整性；仔细核对叙事连续性、规则约束和事件字段，但不要输出思考过程。"
    }

    companion object {
        fun fromStored(value: String?): ReasoningEffort = entries.firstOrNull {
            it.name == value
        } ?: AUTO
    }
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
    fun saveReasoningEffort(effort: String)
    fun getReasoningEffort(): String
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
        private const val KEY_REASONING_EFFORT = "reasoning_effort"
        private const val DEFAULT_BASE_URL = "https://api.openai.com"
        private const val DEFAULT_MODEL = "gpt-5.5"
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
            .putInt(
                KEY_MAX_HISTORY_TURNS,
                ConversationMemoryPolicy.sanitizeRecentTurns(turns)
            )
            .apply()
    }

    override fun getMaxHistoryTurns(): Int {
        val saved = sharedPreferences.getInt(
            KEY_MAX_HISTORY_TURNS,
            ConversationMemoryPolicy.DEFAULT_RECENT_TURNS
        )
        return ConversationMemoryPolicy.sanitizeRecentTurns(saved)
    }

    override fun saveReasoningEffort(effort: String) {
        sharedPreferences.edit()
            .putString(KEY_REASONING_EFFORT, ReasoningEffort.fromStored(effort).name)
            .apply()
    }

    override fun getReasoningEffort(): String {
        return ReasoningEffort.fromStored(
            sharedPreferences.getString(KEY_REASONING_EFFORT, ReasoningEffort.AUTO.name)
        ).name
    }
}
