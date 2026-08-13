package xyz.sakulik.d20.app.data.repository

import kotlinx.coroutines.flow.Flow
import xyz.sakulik.d20.app.data.model.ChatMessage
import xyz.sakulik.d20.app.data.model.StreamState

/**
 * 跑团 AI 仓库接口
 */
interface LlmRepository {
    /**
     * 流式对话
     * @param baseUrl API 基础地址
     * @param messages 历史消息
     */
    fun chatStream(baseUrl: String, messages: List<ChatMessage>): Flow<StreamState>

    /**
     * 不带 JSON 协议拦截的通用文本流
     */
    fun chatRaw(baseUrl: String, messages: List<ChatMessage>): Flow<String>

    /**
     * 生成角色预览
     */
    fun generateCharacter(baseUrl: String, description: String, rulesetId: String, promptInjection: String? = null): Flow<xyz.sakulik.d20.app.data.model.CharacterGenState>
}

sealed class CharacterGenState {
    object Loading : CharacterGenState()
    data class Success(val character: xyz.sakulik.d20.app.data.model.CharacterGenResponse) : CharacterGenState()
    data class Error(val throwable: Throwable) : CharacterGenState()
}
