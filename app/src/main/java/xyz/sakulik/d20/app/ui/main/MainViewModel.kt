package xyz.sakulik.d20.app.ui.main

import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import xyz.sakulik.d20.app.data.local.CharacterDao
import xyz.sakulik.d20.app.data.local.CharacterEntity
import xyz.sakulik.d20.app.data.local.CombatantDao
import xyz.sakulik.d20.app.data.local.CombatantEntity
import xyz.sakulik.d20.app.data.local.CombatSessionEntity
import xyz.sakulik.d20.app.data.local.LoreEntryDao
import xyz.sakulik.d20.app.data.local.GameStateDao
import xyz.sakulik.d20.app.data.local.MessageDao
import xyz.sakulik.d20.app.data.local.MessageEntity
import xyz.sakulik.d20.app.data.model.GameEvent
import xyz.sakulik.d20.app.data.model.StreamState
import xyz.sakulik.d20.app.data.repository.ContextAssembler
import xyz.sakulik.d20.app.data.repository.InventoryRepository
import xyz.sakulik.d20.app.data.repository.LlmRepository
import xyz.sakulik.d20.app.data.security.LlmKeyManager
import xyz.sakulik.d20.app.domain.rules.dynamic.CheckIntent
import xyz.sakulik.d20.app.domain.rules.dynamic.DiceSubmission
import xyz.sakulik.d20.app.domain.combat.CombatState
import xyz.sakulik.d20.app.domain.combat.CombatStateManager
import xyz.sakulik.d20.app.domain.combat.EffectOperation
import xyz.sakulik.d20.app.domain.combat.withEffect
import xyz.sakulik.d20.app.domain.combat.RulesetCombatPolicy
import xyz.sakulik.d20.app.domain.rules.RulesetRegistry
import xyz.sakulik.d20.app.domain.rules.RulesetCheckPolicy
import xyz.sakulik.d20.app.domain.rules.action.ActionResolution
import xyz.sakulik.d20.app.domain.rules.action.BatchRuleOutcome
import xyz.sakulik.d20.app.domain.rules.action.BatchOutcomePlanner
import xyz.sakulik.d20.app.domain.rules.action.BatchPlanningResult
import xyz.sakulik.d20.app.domain.rules.action.DndActionResolver
import xyz.sakulik.d20.app.domain.rules.action.DndLifeState
import xyz.sakulik.d20.app.domain.rules.action.DndLifeStateRules
import xyz.sakulik.d20.app.domain.rules.action.ResourceLedger
import xyz.sakulik.d20.app.domain.rules.action.RuleOutcome
import xyz.sakulik.d20.app.domain.rules.action.SpellProfile
import xyz.sakulik.d20.app.domain.rules.action.SpellResolutionType
import xyz.sakulik.d20.app.domain.rules.action.WeaponProfile
import xyz.sakulik.d20.app.domain.rules.action.toSpellProfileOrNull
import xyz.sakulik.d20.app.domain.rules.action.toWeaponProfileOrNull
import xyz.sakulik.d20.app.ui.base.BaseViewModel
import xyz.sakulik.d20.app.ui.base.UiEvent
import xyz.sakulik.d20.app.ui.base.UiState
import xyz.sakulik.d20.app.ui.common.SensoryController
import xyz.sakulik.d20.app.ui.main.models.ChatMessageUiModel
import xyz.sakulik.d20.app.ui.main.models.MessageType
import xyz.sakulik.d20.app.ui.theme.TRPGThemeStyle
import xyz.sakulik.d20.app.util.unescapeJson
import java.util.UUID

private const val DEATH_SAVE_ACTION_ID = "dnd_death_save"
private const val DND_ATTACK_ACTION_ID = "dnd_attack"
private const val DND_ATTACK_EFFECT_ACTION_ID = "dnd_attack_effect"
private const val DND_CAST_ACTION_ID = "dnd_cast"
private const val DND_SPELL_EFFECT_ACTION_ID = "dnd_spell_effect"
private const val COMBAT_INITIATIVE_ACTION_ID = "combat_initiative"
/**
 * 聚合 UI 状态 (SSOT)
 */
data class MainUiState(
    val messages: List<ChatMessageUiModel> = emptyList(),
    val streamingNarrative: String = "",
    val character: CharacterEntity? = null,
    val isLoading: Boolean = false,
    val activeRulesetId: String = "coc_7e",
    val isDicePanelVisible: Boolean = false,
    val currentDiceIntent: CheckIntent? = null,
    val combatState: xyz.sakulik.d20.app.domain.combat.CombatState? = null,
    val turnResourceLabels: Map<String, String> = emptyMap(),
    val availableSpellSlotLevels: List<Int> = emptyList(),
    val isDying: Boolean = false,
    val isStable: Boolean = false,
    val isDead: Boolean = false,
    val deathSaveSuccesses: Int = 0,
    val deathSaveFailures: Int = 0,
    val isDeathSaveRollInProgress: Boolean = false,
    val inventory: List<xyz.sakulik.d20.app.data.local.ItemEntity> = emptyList(),
    val availableWeapons: List<WeaponProfile> = emptyList(),
    val preparedSpells: List<SpellProfile> = emptyList(),
    val isInventoryVisible: Boolean = false,
    val themeStyle: TRPGThemeStyle = TRPGThemeStyle.AUTO
) : UiState

/**
 * UI 事件
 */
sealed class MainUiEvent : UiEvent {
    data class ShowDicePanel(val intent: CheckIntent) : MainUiEvent()
    data class Error(val message: String) : MainUiEvent()
}

class MainViewModel(
    private val context: android.content.Context,
    private val campaignId: String,
    private val repository: LlmRepository,
    private val contextAssembler: ContextAssembler,
    private val characterDao: CharacterDao,
    private val messageDao: MessageDao,
    private val inventoryRepository: InventoryRepository,
    private val keyManager: LlmKeyManager,
    private val loreEntryDao: LoreEntryDao? = null,
    private val combatantDao: CombatantDao,
    private val gameStateDao: GameStateDao,
    private val sensoryController: SensoryController? = null
) : BaseViewModel<MainUiState, MainUiEvent>(MainUiState()) {

    private val combatStateManager = CombatStateManager()
    private val dndActionResolver = DndActionResolver()
    private val batchOutcomePlanner = BatchOutcomePlanner()
    private var isAutoStarting = false 
    private var persistedCombatants: List<CombatantEntity> = emptyList()
    private var persistedCombatSession: CombatSessionEntity? = null
    private var pendingCombatants: List<xyz.sakulik.d20.app.domain.combat.CombatantDefinition>? = null

    init {
        // 加载初始主题
        val savedStyle = keyManager.getThemeStyle()
        updateState { it.copy(themeStyle = TRPGThemeStyle.valueOf(savedStyle)) }

        // 性能优化：延迟加载数据，错开导航动画最吃性能的前 300ms
        viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            loadData()
        }
    }

    private fun loadData() {
        viewModelScope.launch {
            // 监听该剧本的历史消息
            messageDao.getMessagesByCampaign(campaignId)
                .map { entities -> 
                    // 性能优化：在后台线程进行数据变换
                    entities.filter { !it.isHidden }.map { 
                        val rawContent = it.content.unescapeJson()
                        ChatMessageUiModel(
                            id = it.id.toString(),
                            content = rawContent,
                            type = when {
                                rawContent.contains("🎲") || rawContent.contains("【掷骰") || rawContent.contains("检定需求") || rawContent.contains("检定") || rawContent.contains("判定：") -> MessageType.DiceCheck
                                it.role == "user" -> MessageType.UserAction
                                it.role == "assistant" -> MessageType.Narrative
                                else -> MessageType.SystemNotice
                            }
                        )
                    }
                }
                .flowOn(kotlinx.coroutines.Dispatchers.Default)
                .collect { uiModels ->
                    updateState { it.copy(messages = uiModels) }
                
                // 自动开场逻辑：如果没有可见消息，且尚未触发过
                if (uiModels.isEmpty() && !isAutoStarting) {
                    isAutoStarting = true
                    viewModelScope.launch {
                        // 确保等待数据加载（最多等待 2s，防止角色缺失导致死循环）
                        var retryCount = 0
                        while (uiState.value.character == null && retryCount < 20) {
                            kotlinx.coroutines.delay(100)
                            retryCount++
                        }
                        
                        // 依然没有角色则不触发（异常存档），否则开始续写
                        if (uiState.value.character != null) {
                            sendActionInternal("")
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            characterDao.observeCharacterByCampaign(campaignId).collect { character ->
                syncCharacterState(character)
            }
        }
        viewModelScope.launch {
            // 监听该剧本的背包物品
            inventoryRepository.getItems(campaignId).collect { items ->
                updateState {
                    it.copy(
                        inventory = items,
                        availableWeapons = items.filter { item -> item.isEquipped }
                            .mapNotNull { item -> item.toWeaponProfileOrNull() },
                        preparedSpells = items.filter { item -> item.isEquipped }
                            .mapNotNull { item -> item.toSpellProfileOrNull() }
                    )
                }
            }
        }
        viewModelScope.launch {
            combatantDao.observeByCampaign(campaignId).collect { entities ->
                persistedCombatants = entities
                restoreCombatStateIfNeeded()
            }
        }
        viewModelScope.launch {
            gameStateDao.observeCombatSession(campaignId).collect { session ->
                persistedCombatSession = session
                restoreCombatStateIfNeeded()
            }
        }
    }

    fun sendAction(text: String) {
        // 如果 text 为空，通常是自动生成的指令（续写/开场），允许通行
        viewModelScope.launch {
            sendActionInternal(text)
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            messageDao.deleteMessageById(messageId)
        }
    }

    private suspend fun sendActionInternal(text: String, retryCount: Int = 0) {
        val cid = campaignId
        val conversation = contextAssembler.buildConversation(text, cid)

        if (text.isNotEmpty()) {
            val isHidden = text.startsWith("[检定结果]")
            messageDao.insertMessage(MessageEntity(campaignId = cid, role = "user", content = text, isHidden = isHidden))
        }

        updateState { it.copy(isLoading = true, streamingNarrative = "") }
        
        val baseUrl = keyManager.getBaseUrl()

        repository.chatStream(baseUrl, conversation).collect { state ->
            when (state) {
                is StreamState.TextChunk -> {
                    updateState { it.copy(streamingNarrative = it.streamingNarrative + state.delta) }
                }
                is StreamState.EventTrigger -> {
                    val finalNarrative = uiState.value.streamingNarrative
                    messageDao.insertMessage(MessageEntity(campaignId = cid, role = "assistant", content = finalNarrative))
                    updateState { it.copy(streamingNarrative = "", isLoading = false) }
                    handleGameEvents(state.events)
                }
                is StreamState.Error -> {
                    updateState { it.copy(isLoading = false, streamingNarrative = "") }
                    if (state.throwable is kotlinx.serialization.SerializationException && retryCount < 1) {
                        messageDao.insertMessage(MessageEntity(
                            campaignId = cid, role = "assistant", isHidden = true,
                            content = "[系统：解析失败，请按 JSON 格式重发]"
                        ))
                        sendActionInternal("", retryCount + 1) 
                    } else {
                        val errMsg = state.throwable.message ?: "Network Error"
                        sendEvent(MainUiEvent.Error(errMsg))
                    }
                }
            }
        }
    }

    internal suspend fun handleGameEvents(events: List<GameEvent>) {
        events.forEach { event ->
            when (event) {
                is GameEvent.RequireRoll -> {
                    val parsedReq = xyz.sakulik.d20.app.util.CheckReasonParser.parse(event.reason)
                    val charStats = uiState.value.character?.stats ?: emptyMap()
                    val ruleset = RulesetRegistry.getRuleset(
                        context,
                        uiState.value.activeRulesetId
                    )
                    if (ruleset == null) {
                        sendEvent(MainUiEvent.Error("找不到当前检定规则配置"))
                        return@forEach
                    }
                    val checkPolicy = RulesetCheckPolicy.from(ruleset)
                    val statId = checkPolicy.resolveStatId(
                        requestedStatId = event.statId,
                        reason = event.reason,
                        character = charStats
                    ).orEmpty()
                    val localStatValue = charStats[statId]?.toIntOrNull()
                    val checkParameters = checkPolicy.resolve(
                        requestedActionId = event.actionId,
                        statValue = localStatValue,
                        eventThreshold = event.threshold,
                        eventTargetValue = event.targetValue,
                        eventModifier = event.modifier
                    )
                    val equippedBonus = statId
                        .takeIf {
                            it.isNotBlank() &&
                                checkPolicy.appliesEquipmentBonus(checkParameters.actionId)
                        }
                        ?.let { inventoryRepository.getEquippedModifiers(campaignId, it) }
                        ?: 0
                    val (targetValue, modifier) = checkPolicy.applyEquipmentBonus(
                        targetValue = checkParameters.targetValue,
                        modifier = checkParameters.modifier,
                        bonus = equippedBonus
                    )
                    val finalCheckParameters = checkParameters.copy(
                        targetValue = targetValue,
                        modifier = modifier
                    )
                    val activeCombatPolicy = combatPolicy(uiState.value.activeRulesetId)
                    val actionId = finalCheckParameters.actionId
                    checkPolicy.validationError(finalCheckParameters)?.let { error ->
                        sendEvent(MainUiEvent.Error("检定“${event.reason}”：$error"))
                        return@forEach
                    }
                    if (
                        activeCombatPolicy.lifePolicyId == RulesetCombatPolicy.LIFE_POLICY_DND_5E &&
                        actionId != DEATH_SAVE_ACTION_ID
                    ) {
                        val life = DndLifeStateRules.snapshot(charStats)
                        if (life.state != DndLifeState.CONSCIOUS) {
                            sendEvent(MainUiEvent.Error("当前生命状态为 ${life.state}，不能发起玩家检定"))
                            return@forEach
                        }
                    }
                    if (actionId == DND_ATTACK_ACTION_ID && combatStateManager.currentState().availableTargets.isEmpty()) {
                        sendEvent(MainUiEvent.Error("当前没有可攻击的战斗目标，请先开始战斗"))
                        return@forEach
                    }
                    if (actionId == DND_ATTACK_ACTION_ID && uiState.value.availableWeapons.isEmpty()) {
                        sendEvent(MainUiEvent.Error("没有可用的已装备武器规则档案"))
                        return@forEach
                    }
                    if (actionId == DND_CAST_ACTION_ID && castableSpells().isEmpty()) {
                        sendEvent(MainUiEvent.Error("没有可用的已准备法术：请检查法术规则档案和剩余法术位"))
                        return@forEach
                    }
                    val combatPolicy = combatPolicy()
                    if (combatPolicy.actionCost(actionId).isNotEmpty()) {
                        val state = combatStateManager.currentState()
                        if (state.isActive && !combatPolicy.canPerform(actionId, state)) {
                            sendEvent(MainUiEvent.Error("当前回合或行动资源不允许执行该动作"))
                            return@forEach
                        }
                    }
                    val rollState = when {
                        event.expression.contains("kh", ignoreCase = true) -> "ADVANTAGE"
                        event.expression.contains("kl", ignoreCase = true) -> "DISADVANTAGE"
                        else -> "NORMAL"
                    }

                    val targetLabel = targetValue?.let {
                        ", ${finalCheckParameters.targetLabel}: $it"
                    }.orEmpty()
                    val reqMsg = "> **检定需求：${parsedReq.displayTitle}** (规则: ${event.expression}$targetLabel)"
                    messageDao.insertMessage(
                        MessageEntity(campaignId = campaignId, role = "assistant", content = reqMsg)
                    )

                    val intent = xyz.sakulik.d20.app.domain.rules.dynamic.CheckIntent(
                        actionId = actionId,
                        meta = mapOf(
                            "expression" to event.expression,
                            "reason" to event.reason,
                            "dc" to (targetValue?.toString() ?: "0"),
                            "target_value" to (targetValue?.toString() ?: "0"),
                            "target_label" to finalCheckParameters.targetLabel,
                            "statId" to statId,
                            "modifier" to modifier.toString(),
                            "bonus_injected" to equippedBonus.toString(),
                            "roll_state" to rollState,
                            "target_id" to event.targetId.orEmpty(),
                            "slot_level" to (event.slotLevel?.toString() ?: ""),
                            "weapon_id" to event.weaponId.orEmpty(),
                            "spell_id" to event.spellId.orEmpty(),
                            "resolution_stage" to "PRIMARY"
                        )
                    )
                    updateState { it.copy(isDicePanelVisible = true, currentDiceIntent = intent) }
                    sendEvent(MainUiEvent.ShowDicePanel(intent))
                }
                is GameEvent.UpdateStat -> {
                    updateCharacterStat(event.statId, event.delta)
                }
                is GameEvent.AddItem -> {
                    val cid = campaignId
                    inventoryRepository.addItem(xyz.sakulik.d20.app.data.local.ItemEntity(
                        id = UUID.randomUUID().toString(),
                        campaignId = cid,
                        name = event.name,
                        description = event.description,
                        category = event.category,
                        modifiers = event.modifiers.mapValues { (_, v) ->
                            if (v is JsonPrimitive) v.content else v.toString().removeSurrounding("\"")
                        },
                        isEquipped = true
                    ))
                    sensoryController?.hapticItemGain()
                }
                is GameEvent.StartCombat -> {
                    val character = uiState.value.character
                    if (character == null) {
                        sendEvent(MainUiEvent.Error("没有可加入战斗的玩家角色"))
                        return@forEach
                    }
                    if (pendingCombatants != null || combatStateManager.currentState().isActive) {
                        sendEvent(MainUiEvent.Error("已有战斗正在进行，不能重复开始战斗"))
                        return@forEach
                    }
                    pendingCombatants = event.combatants
                    val policy = combatPolicy(character.activeSystem)
                    val request = policy.initiativeRequest(character.stats)
                    if (request == null) {
                        startCombat(
                            character = character,
                            enemies = event.combatants,
                            participantInitiative = policy.automaticInitiative(character.stats),
                            policy = policy
                        )
                        pendingCombatants = null
                        return@forEach
                    }
                    val intent = CheckIntent(
                        actionId = COMBAT_INITIATIVE_ACTION_ID,
                        meta = mapOf(
                            "expression" to request.expression,
                            "reason" to request.reason,
                            "modifier" to request.modifier.toString(),
                            "ruleset_id" to policy.rulesetId,
                            "roll_state" to "NORMAL"
                        )
                    )
                    updateState { it.copy(isDicePanelVisible = true, currentDiceIntent = intent) }
                    messageDao.insertMessage(
                        MessageEntity(
                            campaignId = campaignId,
                            role = "assistant",
                            content = "> 【战斗开始】请掷 ${request.expression} 决定行动顺序。"
                        )
                    )
                    sendEvent(MainUiEvent.ShowDicePanel(intent))
                }
                is GameEvent.EndCombat -> {
                    gameStateDao.endCombat(campaignId)
                    combatStateManager.endCombat()
                    pendingCombatants = null
                    updateState { it.copy(combatState = null, turnResourceLabels = emptyMap()) }
                }
                is GameEvent.UpdateLore -> {
                    val cid = campaignId
                    val keywordsStr = if (event.keywords.isNotEmpty()) event.keywords.joinToString(",") else event.title
                    loreEntryDao?.insertOrUpdateLore(xyz.sakulik.d20.app.data.local.LoreEntryEntity(
                        campaignId = cid,
                        title = event.title,
                        category = event.category,
                        keywords = keywordsStr,
                        content = event.content
                    ))
                    Log.d("Lorebook", "Piggyback Lore saved: [${event.title}] (${event.category})")
                }
                is GameEvent.RemoveLore -> {
                    loreEntryDao?.deleteByTitle(campaignId, event.title)
                }
            }
        }
    }

    /**
     * 切换主题风格
     */
    fun cycleThemeStyle() {
        val styles = TRPGThemeStyle.values()
        val currentIndex = styles.indexOf(uiState.value.themeStyle)
        val nextIndex = (currentIndex + 1) % styles.size
        val nextStyle = styles[nextIndex]
        
        updateState { it.copy(themeStyle = nextStyle) }
        keyManager.saveThemeStyle(nextStyle.name)
        
        // 触发触觉反馈提示切换成功
        sensoryController?.hapticClick()
    }

    /**
     * 切换到下一轮战斗
     */
    fun nextCombatTurn() {
        viewModelScope.launch {
            val policy = combatPolicy()
            val character = uiState.value.character ?: return@launch
            val advance = combatStateManager.advanceTurn(
                playerTurnResources = policy.initialTurnResources(isPlayerTurn = true)
            )
            var updatedCharacter = character
            advance.ticks.filter { it.effect.targetId == CombatStateManager.PLAYER_ID }
                .forEach { tick ->
                    val delta = when (tick.effect.operation) {
                        EffectOperation.DAMAGE -> -tick.effect.amount
                        EffectOperation.HEAL -> tick.effect.amount
                    }
                    val stats = if (policy.lifePolicyId == RulesetCombatPolicy.LIFE_POLICY_DND_5E) {
                        DndLifeStateRules.applyHpDelta(updatedCharacter.stats, delta)
                    } else {
                        val currentHp = updatedCharacter.stats["hp"]?.toIntOrNull() ?: 0
                        val maxHp = updatedCharacter.stats["max_hp"]?.toIntOrNull()
                            ?: advance.state.combatants.firstOrNull { it.isPlayer }?.maxHp
                            ?: currentHp.coerceAtLeast(1)
                        updatedCharacter.stats + (
                            "hp" to (currentHp + delta).coerceIn(0, maxHp).toString()
                        )
                    }
                    updatedCharacter = updatedCharacter.copy(stats = stats)
                }
            val playerHp = updatedCharacter.stats["hp"]?.toIntOrNull()
            val reconciledState = if (playerHp != null) {
                advance.state.copy(
                    combatants = advance.state.combatants.map { combatant ->
                        if (combatant.isPlayer) combatant.copy(hp = playerHp) else combatant
                    }
                )
            } else {
                advance.state
            }
            combatStateManager.restoreState(reconciledState)
            val targetHpUpdates = advance.ticks
                .filter { it.effect.targetId != CombatStateManager.PLAYER_ID }
                .associate { it.effect.targetId to it.currentHp }
            gameStateDao.applyRuleOutcomes(
                character = updatedCharacter,
                targetHpUpdates = targetHpUpdates,
                combatSession = reconciledState.takeIf { it.isActive && !advance.combatEnded }
                    ?.toEntity(campaignId, policy),
                combatEnded = advance.combatEnded
            )
            syncCharacterState(updatedCharacter)
            if (advance.combatEnded) {
                combatStateManager.endCombat()
                updateState { it.copy(combatState = null, turnResourceLabels = emptyMap()) }
                messageDao.insertMessage(
                    MessageEntity(campaignId = campaignId, role = "assistant", content = "> 【系统通知】所有敌人均已被击败，战斗结束。")
                )
                return@launch
            }
            combatStateManager.restoreState(reconciledState)
            updateState {
                it.copy(
                    combatState = reconciledState,
                    turnResourceLabels = reconciledState.turnResources.keys.associateWith(policy::resourceLabel)
                )
            }
            advance.ticks.forEach { tick ->
                val operation = if (tick.effect.operation == EffectOperation.DAMAGE) "受到" else "恢复"
                messageDao.insertMessage(
                    MessageEntity(
                        campaignId = campaignId,
                        role = "assistant",
                        content = "> 【持续效果】${tick.effect.name}：$operation ${tick.appliedAmount} 点生命值。"
                    )
                )
            }
            val currentPlayer = reconciledState.currentCombatant?.name ?: "未知"
            messageDao.insertMessage(MessageEntity(
                campaignId = campaignId,
                role = "assistant",
                content = "> 【系统通知】当前战斗轮到：$currentPlayer"
            ))
        }
    }

    private fun syncCharacterState(character: CharacterEntity?) {
        if (character == null) {
            updateState {
                it.copy(
                    character = null,
                    combatState = null,
                    turnResourceLabels = emptyMap(),
                    availableSpellSlotLevels = emptyList(),
                    isDying = false,
                    isStable = false,
                    isDead = false,
                    deathSaveSuccesses = 0,
                    deathSaveFailures = 0,
                    isDeathSaveRollInProgress = false
                )
            }
            return
        }

        val policy = combatPolicy(character.activeSystem)
        val life = if (policy.lifePolicyId == RulesetCombatPolicy.LIFE_POLICY_DND_5E) {
            DndLifeStateRules.snapshot(character.stats)
        } else {
            null
        }
        updateState {
            it.copy(
                character = character,
                activeRulesetId = character.activeSystem,
                isDying = life?.state == DndLifeState.DYING,
                isStable = life?.state == DndLifeState.STABLE,
                isDead = life?.state == DndLifeState.DEAD,
                deathSaveSuccesses = life?.successes ?: 0,
                deathSaveFailures = life?.failures ?: 0,
                availableSpellSlotLevels = parseAvailableSpellSlotLevels(character.stats),
                isDeathSaveRollInProgress = if (life?.state == DndLifeState.DYING) {
                    it.isDeathSaveRollInProgress
                } else {
                    false
                }
            )
        }
        restoreCombatStateIfNeeded()
    }

    private fun restoreCombatStateIfNeeded() {
        val character = uiState.value.character ?: return
        val session = persistedCombatSession
        if (persistedCombatants.isEmpty() || session == null) {
            combatStateManager.endCombat()
            updateState { it.copy(combatState = null) }
            return
        }
        val policy = combatPolicy(session.rulesetId)
        val current = combatStateManager.currentState()
        val persistedSignature = listOf(
            persistedCombatants.map { listOf(it.id, it.hp.toString(), it.ac.toString()) },
            session.round,
            session.currentTurnIndex,
            session.initiativeQueue,
            session.rulesetId,
            session.lifePolicy,
            session.turnResources,
            session.defeatAtZeroHp,
            session.ongoingEffects
        )
        val currentSignature = listOf(
            current.availableTargets.map { listOf(it.id, it.hp.toString(), it.ac.toString()) },
            current.round,
            current.currentTurnIndex,
            current.initiativeQueue,
            policy.rulesetId,
            policy.lifePolicyId,
            current.turnResources,
            current.defeatAtZeroHp,
            current.ongoingEffects
        )
        if (persistedSignature == currentSignature) return

        val restored = combatStateManager.restoreCombat(
            enemies = persistedCombatants.map { entity ->
                xyz.sakulik.d20.app.domain.combat.CombatantDefinition(
                    id = entity.id,
                    name = entity.name,
                    initiative = entity.initiative,
                    ac = entity.ac,
                    hp = entity.hp,
                    maxHp = entity.maxHp,
                    resistances = entity.resistances,
                    vulnerabilities = entity.vulnerabilities,
                    immunities = entity.immunities,
                    savingThrows = entity.savingThrows,
                    attributes = entity.attributes
                )
            },
            playerName = character.name,
            playerInitiative = session.participantInitiative,
            playerAc = character.stats["ac"]?.toIntOrNull() ?: 10,
            playerHp = character.stats["hp"]?.toIntOrNull() ?: 1,
            playerMaxHp = character.stats["max_hp"]?.toIntOrNull()
                ?: character.stats["hp"]?.toIntOrNull()
                ?: 1,
            round = session.round,
            initiativeQueue = session.initiativeQueue,
            currentTurnIndex = session.currentTurnIndex,
            turnResources = session.turnResources,
            defeatAtZeroHp = session.defeatAtZeroHp,
            ongoingEffects = session.ongoingEffects
        )
        updateState {
            it.copy(
                combatState = restored,
                turnResourceLabels = restored.turnResources.keys.associateWith(policy::resourceLabel)
            )
        }
    }

    private fun parseAvailableSpellSlotLevels(stats: Map<String, String>): List<Int> {
        val resources = runCatching {
            Json.parseToJsonElement(stats["resources"].orEmpty()).jsonObject
        }.getOrNull() ?: return emptyList()
        val spellSlots = resources["spell_slots"]?.jsonObject ?: return emptyList()
        return spellSlots.mapNotNull { (key, value) ->
            val level = key.removePrefix("level_").toIntOrNull() ?: return@mapNotNull null
            val current = value.jsonObject["current"]?.jsonPrimitive?.intOrNull ?: 0
            level.takeIf { current > 0 }
        }.sorted()
    }

    private suspend fun updateCharacterStat(statId: String, delta: Int) {
        val char = uiState.value.character ?: return
        val currentVal = char.stats[statId]?.toIntOrNull() ?: 0
        val policy = combatPolicy(char.activeSystem)
        val isDndHp = statId.equals("hp", ignoreCase = true) &&
            policy.lifePolicyId == RulesetCombatPolicy.LIFE_POLICY_DND_5E
        val newStats = if (isDndHp) {
            DndLifeStateRules.applyHpDelta(char.stats, delta)
        } else {
            char.stats + (statId to (currentVal + delta).toString())
        }
        val newVal = newStats[statId]?.toIntOrNull() ?: currentVal
        val updatedCharacter = char.copy(stats = newStats)
        characterDao.updateCharacter(updatedCharacter)
        syncCharacterState(updatedCharacter)

        if (
            statId.equals("hp", ignoreCase = true) &&
            currentVal > 0 &&
            newVal <= 0 &&
            policy.lifePolicyId == RulesetCombatPolicy.LIFE_POLICY_DND_5E
        ) {
            sensoryController?.hapticHeavyDamage()
        }
    }

    fun requestDeathSaveRoll() {
        val currentState = uiState.value
        if (!currentState.isDying || currentState.isDeathSaveRollInProgress) return

        val intent = CheckIntent(
            actionId = DEATH_SAVE_ACTION_ID,
            meta = mapOf(
                "expression" to "1d20",
                "reason" to "死亡豁免",
                "dc" to "10",
                "modifier" to "0",
                "roll_state" to "NORMAL"
            )
        )
        updateState {
            it.copy(
                isDeathSaveRollInProgress = true,
                isDicePanelVisible = true,
                currentDiceIntent = intent
            )
        }
        sendEvent(MainUiEvent.ShowDicePanel(intent))
    }

    /**
     * 切换背包可见性
     */
    fun toggleInventory(visible: Boolean) {
        updateState { it.copy(isInventoryVisible = visible) }
    }

    /**
     * 切换装备状态
     */
    fun toggleEquip(item: xyz.sakulik.d20.app.data.local.ItemEntity) {
        viewModelScope.launch {
            inventoryRepository.toggleEquip(item)
            sensoryController?.hapticSoftTick() // 装备切换震动反馈
        }
    }

    fun onDiceResult(
        submission: DiceSubmission,
        intent: xyz.sakulik.d20.app.domain.rules.dynamic.CheckIntent
    ) {
        updateState { it.copy(isDicePanelVisible = false, currentDiceIntent = null) }
        viewModelScope.launch {
            try {
                val cid = campaignId
                val char = uiState.value.character
                    ?: error("当前存档没有可用角色")
                if (intent.actionId == COMBAT_INITIATIVE_ACTION_ID) {
                    handleInitiativeResult(char, submission, intent)
                    return@launch
                }
                val rulesetId = char.activeSystem
                if (handleTypedDndAction(char, submission, intent)) {
                    return@launch
                }
                val ruleset = xyz.sakulik.d20.app.domain.rules.RulesetRegistry
                    .getRuleset(context, rulesetId)
                    ?: error("找不到规则配置文件")

                val baseModifier = intent.meta["modifier"]?.toIntOrNull() ?: 0
                val baseTargetValue = intent.meta["target_value"]?.toIntOrNull() ?: 0
                val equippedBonus = intent.meta["bonus_injected"]?.toIntOrNull() ?: 0
                val requestedTargetId = intent.meta["target_id"].orEmpty()
                val attackTarget = requestedTargetId.takeIf { it.isNotBlank() }
                    ?.let { targetId ->
                        combatStateManager.getTarget(targetId)
                            ?: error("所选战斗目标已不存在或已被击败")
                    }
                val augmentedIntent = intent.copy(
                    meta = intent.meta + mapOf(
                        "bonus_injected" to equippedBonus.toString(),
                        "modifier" to baseModifier.toString(),
                        "target_value" to baseTargetValue.toString(),
                        "target_ac" to (attackTarget?.ac?.toString() ?: ""),
                        "target_resistances" to attackTarget?.resistances.orEmpty().joinToString(","),
                        "target_vulnerabilities" to attackTarget?.vulnerabilities.orEmpty().joinToString(","),
                        "target_immunities" to attackTarget?.immunities.orEmpty().joinToString(",")
                    ),
                    diceSubmission = submission
                )

                // 执行 AST 逻辑计算
                val evaluationResult = ruleset.executePipeline(augmentedIntent, char.stats)
                if (!evaluationResult.isValid) {
                    val message = evaluationResult.errors.joinToString("；") { it.message }
                        .ifBlank { "规则引擎没有产生有效裁决" }
                    Log.e("ASTEngine", evaluationResult.logs.joinToString("\n"))
                    if (intent.actionId == DEATH_SAVE_ACTION_ID) {
                        updateState { it.copy(isDeathSaveRollInProgress = false) }
                    }
                    sendEvent(MainUiEvent.Error(message))
                    return@launch
                }

                // 应用计算后的副作用快照
                val newStats = evaluationResult.modifiedCharacterData.mapValues {
                    it.value.toString()
                }
                val updatedCharacter = char.copy(stats = newStats)
                val originalCombatState = combatStateManager.currentState()
                var resultingCombatState = originalCombatState
                var updatedTargetId: String? = null
                var updatedTargetHp: Int? = null
                if (attackTarget != null) {
                    val damage = evaluationResult.resolvedValues["final_damage"]
                        ?.toString()?.toFloatOrNull()?.toInt()?.coerceAtLeast(0) ?: 0
                    if (damage > 0) {
                        resultingCombatState = combatStateManager.applyDamage(attackTarget.id, damage)
                        updatedTargetId = attackTarget.id
                        updatedTargetHp = resultingCombatState.combatants
                            .first { it.id == attackTarget.id }
                            .hp
                    }
                }

                val policy = RulesetCombatPolicy.from(ruleset)
                val combatEnded = resultingCombatState.isActive &&
                    resultingCombatState.availableTargets.isEmpty()
                val stateAfterAction = if (resultingCombatState.isActive && !combatEnded) {
                    policy.consume(intent.actionId, resultingCombatState)
                        ?: run {
                            combatStateManager.restoreState(originalCombatState)
                            sendEvent(MainUiEvent.Error("当前回合或行动资源不允许执行该动作"))
                            return@launch
                        }
                } else {
                    resultingCombatState
                }
                gameStateDao.applyRuleOutcome(
                    character = updatedCharacter,
                    targetId = updatedTargetId,
                    targetHp = updatedTargetHp,
                    combatSession = stateAfterAction.takeIf { it.isActive && !combatEnded }
                        ?.toEntity(campaignId, policy),
                    combatEnded = combatEnded
                )
                syncCharacterState(updatedCharacter)
                if (combatEnded) {
                    combatStateManager.endCombat()
                    updateState { it.copy(combatState = null, turnResourceLabels = emptyMap()) }
                } else if (stateAfterAction.isActive) {
                    combatStateManager.restoreState(stateAfterAction)
                    updateState {
                        it.copy(
                            combatState = stateAfterAction,
                            turnResourceLabels = stateAfterAction.turnResources.keys
                                .associateWith(policy::resourceLabel)
                        )
                    }
                }

                // 死亡豁免是本地完整规则流程，不再回送 LLM，避免再次触发掷骰。
                if (intent.actionId == DEATH_SAVE_ACTION_ID) {
                    handleDeathSaveResult(submission.total, updatedCharacter, evaluationResult)
                    return@launch
                }

                val level = evaluationResult.state
                val resultLevelCn = when (level) {
                    xyz.sakulik.d20.app.domain.rules.dynamic.ResultState.CRITICAL_SUCCESS -> "大成功"
                    xyz.sakulik.d20.app.domain.rules.dynamic.ResultState.EXTREME_SUCCESS -> "极难成功"
                    xyz.sakulik.d20.app.domain.rules.dynamic.ResultState.HARD_SUCCESS -> "困难成功"
                    xyz.sakulik.d20.app.domain.rules.dynamic.ResultState.REGULAR_SUCCESS -> "普通成功"
                    xyz.sakulik.d20.app.domain.rules.dynamic.ResultState.SUCCESS -> "成功"
                    xyz.sakulik.d20.app.domain.rules.dynamic.ResultState.FAILURE -> "失败"
                    xyz.sakulik.d20.app.domain.rules.dynamic.ResultState.CRITICAL_FAILURE -> "大失败"
                    xyz.sakulik.d20.app.domain.rules.dynamic.ResultState.UNKNOWN -> "规则错误"
                }

                val rawReason = intent.meta["reason"]?.ifBlank { null } ?: "检定"
                val rawDc = baseTargetValue.toString()
                val targetLabel = ruleset.checkRules.targetLabel.ifBlank { "目标值" }
                val dcStr = when {
                    rawDc == "0" || rawDc.isBlank() -> ""
                    else -> "$targetLabel $rawDc"
                }
                val rollValueStr = evaluationResult.diceTraces.values
                    .firstOrNull()?.joinToString(", ") ?: submission.total.toString()

                val parsedCheck = xyz.sakulik.d20.app.util.CheckReasonParser.parse(
                    rawReason,
                    resultLevelCn,
                    rollValueStr,
                    dcStr
                )

                // 将开发者调试日志输出到 Logcat，不污染玩家聊天界面
                Log.d("ASTEngine", evaluationResult.logs.joinToString("\n"))

                val userFacingResult = parsedCheck.formattedContent
                messageDao.insertMessage(
                    MessageEntity(campaignId = cid, role = "assistant", content = "> $userFacingResult")
                )

                when (level) {
                    xyz.sakulik.d20.app.domain.rules.dynamic.ResultState.CRITICAL_SUCCESS,
                    xyz.sakulik.d20.app.domain.rules.dynamic.ResultState.EXTREME_SUCCESS,
                    xyz.sakulik.d20.app.domain.rules.dynamic.ResultState.HARD_SUCCESS,
                    xyz.sakulik.d20.app.domain.rules.dynamic.ResultState.REGULAR_SUCCESS,
                    xyz.sakulik.d20.app.domain.rules.dynamic.ResultState.SUCCESS ->
                        sensoryController?.hapticCriticalSuccess()
                    else -> sensoryController?.hapticCheckFailure()
                }

                val expr = intent.meta["expression"] ?: "1d20"
                val boundPayload = "[检定结果] 动作：${parsedCheck.displayTitle} | 规则：$expr | 掷骰：$rollValueStr | 结果：$resultLevelCn。请根据此结果描述接下来的剧情发展。"
                sendAction(boundPayload)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (intent.actionId == DEATH_SAVE_ACTION_ID) {
                    updateState { it.copy(isDeathSaveRollInProgress = false) }
                }
                sendEvent(MainUiEvent.Error(error.message ?: "规则检定失败"))
            }
        }
    }

    fun updateItemRuleParameters(
        item: xyz.sakulik.d20.app.data.local.ItemEntity,
        modifiers: Map<String, String>
    ) {
        val draft = item.copy(modifiers = modifiers)
        val isWeapon = item.category.contains("武器", ignoreCase = true) ||
            item.category.contains("weapon", ignoreCase = true)
        val isSpell = item.category.contains("法术", ignoreCase = true) ||
            item.category.contains("spell", ignoreCase = true)
        if (isWeapon && draft.toWeaponProfileOrNull() == null) {
            sendEvent(MainUiEvent.Error("武器规则不完整：至少需要 damage_formula 和 damage_type"))
            return
        }
        if (isSpell && draft.toSpellProfileOrNull() == null) {
            sendEvent(MainUiEvent.Error("法术规则不完整：请检查 resolution_type、环级及对应效果字段"))
            return
        }
        viewModelScope.launch {
            inventoryRepository.updateRuleParameters(item, modifiers)
            sensoryController?.hapticSoftTick()
        }
    }

    private suspend fun handleTypedDndAction(
        character: CharacterEntity,
        submission: DiceSubmission,
        intent: CheckIntent
    ): Boolean {
        val policy = combatPolicy(character.activeSystem)
        if (policy.localActionHandlerId != RulesetCombatPolicy.LOCAL_ACTION_HANDLER_DND_5E) {
            return false
        }
        val combatState = combatStateManager.currentState()
        if (
            combatState.isActive &&
            intent.actionId in setOf(
                DND_ATTACK_ACTION_ID,
                DND_ATTACK_EFFECT_ACTION_ID,
                DND_CAST_ACTION_ID,
                DND_SPELL_EFFECT_ACTION_ID
            ) &&
            !policy.canPerform(canonicalCombatAction(intent.actionId), combatState)
        ) {
            return reportTypedRuleError("现在不是玩家回合，或本回合行动已经使用")
        }
        return when (intent.actionId) {
            DND_ATTACK_ACTION_ID -> {
                val weapon = selectedWeapon(intent) ?: return reportTypedRuleError(
                    "所选武器不可用或缺少武器规则档案"
                )
                val target = selectedTarget(intent) ?: return reportTypedRuleError(
                    "所选攻击目标已不存在或已被击败"
                )
                handleActionResolution(
                    character = character,
                    intent = intent,
                    subjectName = weapon.name,
                    resolution = dndActionResolver.resolveWeaponAttack(
                        character = character.stats,
                        weapon = weapon,
                        target = target,
                        submission = submission,
                        expectedExpression = intent.requiredExpression()
                    )
                )
                true
            }
            DND_ATTACK_EFFECT_ACTION_ID -> {
                val weapon = selectedWeapon(intent) ?: return reportTypedRuleError(
                    "伤害阶段找不到原攻击武器"
                )
                val target = selectedTarget(intent) ?: return reportTypedRuleError(
                    "伤害阶段的目标已不存在或已被击败"
                )
                handleActionResolution(
                    character = character,
                    intent = intent,
                    subjectName = weapon.name,
                    resolution = dndActionResolver.resolveWeaponDamage(
                        weapon = weapon,
                        target = target,
                        submission = submission,
                        expectedExpression = intent.requiredExpression(),
                        critical = intent.meta["critical"].toBoolean()
                    )
                )
                true
            }
            DND_CAST_ACTION_ID, DND_SPELL_EFFECT_ACTION_ID -> {
                val spell = selectedSpell(intent) ?: return reportTypedRuleError(
                    "所选法术不可用或缺少法术规则档案"
                )
                val resourceChanges = dndActionResolver.spellResourceChanges(spell)
                if (!ResourceLedger.canApply(character.stats, resourceChanges)) {
                    return reportTypedRuleError("${spell.name} 所需的 ${spell.slotLevel} 环法术位不足")
                }
                val target = intent.meta["target_id"]
                    ?.takeIf { it.isNotBlank() }
                    ?.let(combatStateManager::getTarget)
                val isEffectStage = intent.actionId == DND_SPELL_EFFECT_ACTION_ID ||
                    intent.meta["resolution_stage"] == "EFFECT"
                val resolution = if (isEffectStage) {
                    dndActionResolver.resolveSpellEffect(
                        spell = spell,
                        target = target,
                        submission = submission,
                        expectedExpression = intent.requiredExpression(),
                        critical = intent.meta["critical"].toBoolean(),
                        targetSaved = intent.meta["target_saved"]?.toBooleanStrictOrNull()
                    ).withResourceChanges(resourceChanges)
                } else {
                    dndActionResolver.resolveSpell(
                        character = character.stats,
                        spell = spell,
                        target = target,
                        submission = submission,
                        expectedExpression = intent.requiredExpression()
                    )
                }
                handleActionResolution(character, intent, spell.name, resolution)
                true
            }
            else -> false
        }
    }

    private suspend fun handleActionResolution(
        character: CharacterEntity,
        intent: CheckIntent,
        subjectName: String,
        resolution: ActionResolution
    ) {
        when (resolution) {
            is ActionResolution.Invalid -> reportTypedRuleError(resolution.error.message)
            is ActionResolution.Completed -> completeOrContinueTargetBatch(
                character = character,
                intent = intent,
                subjectName = subjectName,
                outcome = resolution.outcome
            )
            is ActionResolution.NeedsEffectRoll -> {
                if (!ResourceLedger.canApply(character.stats, resolution.resourceChanges)) {
                    reportTypedRuleError("$subjectName 所需资源不足")
                    return
                }
                val isSpell = intent.actionId == DND_CAST_ACTION_ID
                val batchMeta = if (intent.batchTargetIds().size > 1) {
                    mapOf(
                        "batch_index" to (intent.meta["batch_index"] ?: "0"),
                        "batch_primary_expression" to (
                            intent.meta["batch_primary_expression"]
                                ?: intent.requiredExpression()
                            ),
                        "batch_primary_stage" to (
                            intent.meta["batch_primary_stage"]
                                ?: intent.meta["resolution_stage"]
                                ?: "PRIMARY"
                            )
                    )
                } else {
                    emptyMap()
                }
                val nextIntent = intent.copy(
                    actionId = if (isSpell) DND_SPELL_EFFECT_ACTION_ID else DND_ATTACK_EFFECT_ACTION_ID,
                    meta = intent.meta + mapOf(
                        "expression" to resolution.expression,
                        "reason" to buildEffectReason(subjectName, resolution),
                        "resolution_stage" to "EFFECT",
                        "critical" to resolution.critical.toString(),
                        "target_saved" to resolution.targetSaved?.toString().orEmpty()
                    ) + batchMeta,
                    diceSubmission = null
                )
                messageDao.insertMessage(
                    MessageEntity(
                        campaignId = campaignId,
                        role = "assistant",
                        content = "> 【本地裁决】${buildEffectReason(subjectName, resolution)}，请掷 ${resolution.expression}。"
                    )
                )
                updateState {
                    it.copy(isDicePanelVisible = true, currentDiceIntent = nextIntent)
                }
                sendEvent(MainUiEvent.ShowDicePanel(nextIntent))
            }
        }
    }

    private suspend fun completeOrContinueTargetBatch(
        character: CharacterEntity,
        intent: CheckIntent,
        subjectName: String,
        outcome: RuleOutcome
    ) {
        val targetIds = intent.batchTargetIds()
        if (targetIds.size <= 1) {
            applyRuleOutcome(
                character = character,
                subjectName = subjectName,
                outcome = outcome,
                combatActionId = canonicalCombatAction(intent.actionId)
            )
            return
        }

        val previousOutcomes = intent.meta["batch_outcomes"]
            ?.takeIf(String::isNotBlank)
            ?.let { encoded -> Json.decodeFromString<List<RuleOutcome>>(encoded) }
            .orEmpty()
        val resourceChanges = intent.meta["batch_resource_changes"]
            ?.takeIf(String::isNotBlank)
            ?.let { encoded -> Json.decodeFromString<Map<String, Int>>(encoded) }
            .orEmpty()
            .mergeBatchResources(outcome.resourceChanges)
        val accumulatedOutcomes = previousOutcomes + outcome.copy(resourceChanges = emptyMap())
        val currentIndex = intent.meta["batch_index"]?.toIntOrNull() ?: 0
        val nextIndex = currentIndex + 1
        if (nextIndex < targetIds.size) {
            val primaryActionId = when {
                intent.actionId in setOf(DND_CAST_ACTION_ID, DND_SPELL_EFFECT_ACTION_ID) ->
                    DND_CAST_ACTION_ID
                else -> DND_ATTACK_ACTION_ID
            }
            val nextIntent = intent.copy(
                actionId = primaryActionId,
                meta = intent.meta + mapOf(
                    "target_id" to targetIds[nextIndex],
                    "batch_index" to nextIndex.toString(),
                    "batch_outcomes" to Json.encodeToString(accumulatedOutcomes),
                    "batch_resource_changes" to Json.encodeToString(resourceChanges),
                    "expression" to (
                        intent.meta["batch_primary_expression"]
                            ?: intent.requiredExpression()
                        ),
                    "resolution_stage" to (
                        intent.meta["batch_primary_stage"]
                            ?: intent.meta["resolution_stage"]
                            ?: "PRIMARY"
                        ),
                    "critical" to "false",
                    "target_saved" to ""
                ),
                diceSubmission = null
            )
            val nextTarget = combatStateManager.getTarget(targetIds[nextIndex])
            if (nextTarget == null) {
                reportTypedRuleError("批量裁决的下一个目标已不存在或已被击败")
                return
            }
            messageDao.insertMessage(
                MessageEntity(
                    campaignId = campaignId,
                    role = "assistant",
                    content = "> 【批量裁决 ${nextIndex + 1}/${targetIds.size}】请为 ${nextTarget.name} 完成 ${nextIntent.requiredExpression()}。"
                )
            )
            updateState { it.copy(isDicePanelVisible = true, currentDiceIntent = nextIntent) }
            sendEvent(MainUiEvent.ShowDicePanel(nextIntent))
            return
        }

        applyBatchRuleOutcome(
            character = character,
            subjectName = subjectName,
            batch = BatchRuleOutcome(
                outcomes = accumulatedOutcomes,
                resourceChanges = resourceChanges
            ),
            combatActionId = canonicalCombatAction(intent.actionId)
        )
    }

    private fun Map<String, Int>.mergeBatchResources(next: Map<String, Int>): Map<String, Int> {
        if (isEmpty()) return next
        if (next.isEmpty() || this == next) return this
        throw IllegalArgumentException("同一个批量动作产生了不一致的资源变化")
    }

    private suspend fun applyBatchRuleOutcome(
        character: CharacterEntity,
        subjectName: String,
        batch: BatchRuleOutcome,
        combatActionId: String
    ) {
        if (batch.outcomes.isEmpty()) {
            reportTypedRuleError("批量裁决没有产生任何目标结果")
            return
        }
        val policy = combatPolicy(character.activeSystem)
        val resourceStats = ResourceLedger.apply(character.stats, batch.resourceChanges)
        if (resourceStats == null) {
            reportTypedRuleError("$subjectName 所需资源不足或资源账本格式无效")
            return
        }
        val currentCombatState = combatStateManager.currentState()
        val plan = when (
            val planning = batchOutcomePlanner.plan(batch, currentCombatState.availableTargets)
        ) {
            is BatchPlanningResult.Invalid -> {
                reportTypedRuleError(planning.error.message)
                return
            }
            is BatchPlanningResult.Completed -> planning
        }
        val targetHpUpdates = plan.targetHpUpdates
        val targetNames = plan.targetNames

        val resultingCombatState = currentCombatState.copy(
            combatants = currentCombatState.combatants.map { combatant ->
                val nextHp = targetHpUpdates[combatant.id]
                if (nextHp != null && !combatant.isPlayer) {
                    combatant.copy(hp = nextHp.coerceIn(0, combatant.maxHp))
                } else {
                    combatant
                }
            },
            ongoingEffects = batch.outcomes.flatMap { it.ongoingEffects }
                .fold(currentCombatState.ongoingEffects) { effects, effect -> effects.withEffect(effect) }
        )
        val combatEnded = resultingCombatState.isActive && resultingCombatState.availableTargets.isEmpty()
        val stateAfterAction = if (resultingCombatState.isActive && !combatEnded) {
            policy.consume(combatActionId, resultingCombatState)
                ?: run {
                    reportTypedRuleError("当前回合或行动资源不允许执行该批量动作")
                    return
                }
        } else {
            resultingCombatState
        }
        val updatedCharacter = character.copy(stats = resourceStats)
        gameStateDao.applyRuleOutcomes(
            character = updatedCharacter,
            targetHpUpdates = targetHpUpdates,
            combatSession = stateAfterAction.takeIf { it.isActive && !combatEnded }
                ?.toEntity(campaignId, policy),
            combatEnded = combatEnded
        )
        syncCharacterState(updatedCharacter)
        if (combatEnded) {
            combatStateManager.endCombat()
            updateState { it.copy(combatState = null) }
        } else if (stateAfterAction.isActive) {
            combatStateManager.restoreState(stateAfterAction)
            updateState { it.copy(combatState = stateAfterAction) }
        }

        val details = batch.outcomes.joinToString("；") { outcome ->
            val targetId = requireNotNull(outcome.targetId)
            val targetName = targetNames[targetId] ?: targetId
            val targetHp = targetHpUpdates[targetId]
            "$targetName：${formatRuleOutcome(subjectName, outcome, targetHp, 0)}"
        }
        val summary = "【批量裁决 ${batch.outcomes.size} 个目标】$details"
        messageDao.insertMessage(
            MessageEntity(campaignId = campaignId, role = "assistant", content = "> $summary")
        )
        if (combatEnded) {
            messageDao.insertMessage(
                MessageEntity(
                    campaignId = campaignId,
                    role = "assistant",
                    content = "> 【系统通知】所有敌人均已被击败，战斗结束。"
                )
            )
        }
        sensoryController?.hapticCriticalSuccess()
        sendAction(
            "[检定结果] 本地规则已原子完成批量裁决：$summary。仅叙述这些既定结果，不要重新计算或再次输出规则事件。"
        )
    }

    private fun CheckIntent.batchTargetIds(): List<String> {
        return meta["target_ids"]
            ?.split('|')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.distinct()
            .orEmpty()
            .ifEmpty { listOfNotNull(meta["target_id"]?.takeIf(String::isNotBlank)) }
    }

    private suspend fun applyRuleOutcome(
        character: CharacterEntity,
        subjectName: String,
        outcome: RuleOutcome,
        combatActionId: String
    ) {
        val policy = combatPolicy(character.activeSystem)
        val resourceStats = ResourceLedger.apply(character.stats, outcome.resourceChanges)
        if (resourceStats == null) {
            reportTypedRuleError("$subjectName 所需资源不足或资源账本格式无效")
            return
        }
        if (
            outcome.healing > 0 &&
            policy.lifePolicyId == RulesetCombatPolicy.LIFE_POLICY_DND_5E &&
            DndLifeStateRules.snapshot(resourceStats).state == DndLifeState.DEAD
        ) {
            reportTypedRuleError("普通治疗不能复活已死亡角色")
            return
        }
        val currentHp = resourceStats["hp"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val healedStats = if (outcome.healing > 0) {
            val maxHp = resourceStats["max_hp"]?.toIntOrNull()
            if (maxHp == null) {
                reportTypedRuleError("角色缺少 max_hp，无法安全结算治疗")
                return
            }
            if (maxHp < currentHp) {
                reportTypedRuleError("角色 HP 状态无效：当前 HP 高于 max_hp")
                return
            }
            if (policy.lifePolicyId == RulesetCombatPolicy.LIFE_POLICY_DND_5E) {
                DndLifeStateRules.applyHpDelta(resourceStats, outcome.healing)
            } else {
                resourceStats + ("hp" to (currentHp + outcome.healing).coerceAtMost(maxHp).toString())
            }
        } else {
            resourceStats
        }
        val updatedCharacter = character.copy(stats = healedStats)
        val totalDamage = outcome.damage.sumOf { it.finalAmount }.coerceAtLeast(0)
        val target = outcome.targetId?.let(combatStateManager::getTarget)
        if (outcome.targetId != null && target == null) {
            reportTypedRuleError("裁决目标已不存在或已被击败")
            return
        }
        val targetHp = target?.let { (it.hp - totalDamage).coerceAtLeast(0) }

        val currentCombatState = combatStateManager.currentState()
        val resultingCombatState = if (target != null && targetHp != null) {
            currentCombatState.copy(
                combatants = currentCombatState.combatants.map { combatant ->
                    if (combatant.id == target.id && !combatant.isPlayer) {
                        combatant.copy(hp = targetHp.coerceIn(0, combatant.maxHp))
                    } else {
                        combatant
                    }
                },
                ongoingEffects = outcome.ongoingEffects.fold(currentCombatState.ongoingEffects) {
                        effects, effect -> effects.withEffect(effect)
                }
            )
        } else {
            currentCombatState.copy(
                ongoingEffects = outcome.ongoingEffects.fold(currentCombatState.ongoingEffects) {
                        effects, effect -> effects.withEffect(effect)
                }
            )
        }
        val combatEnded = resultingCombatState.isActive && resultingCombatState.availableTargets.isEmpty()
        val stateAfterAction = if (resultingCombatState.isActive && !combatEnded) {
            policy.consume(combatActionId, resultingCombatState)
                ?: run {
                    reportTypedRuleError("当前回合或行动资源不允许执行该动作")
                    return
                }
        } else {
            resultingCombatState
        }

        gameStateDao.applyRuleOutcome(
            character = updatedCharacter,
            targetId = target?.id,
            targetHp = targetHp,
            combatSession = stateAfterAction.takeIf { it.isActive && !combatEnded }
                ?.toEntity(campaignId, policy),
            combatEnded = combatEnded
        )
        syncCharacterState(updatedCharacter)
        if (combatEnded) {
            combatStateManager.endCombat()
            updateState { it.copy(combatState = null) }
        } else if (stateAfterAction.isActive) {
            combatStateManager.restoreState(stateAfterAction)
            updateState { it.copy(combatState = stateAfterAction) }
        }

        val actualHealing = (healedStats["hp"]?.toIntOrNull() ?: currentHp) - currentHp
        val summary = formatRuleOutcome(subjectName, outcome, targetHp, actualHealing)
        messageDao.insertMessage(
            MessageEntity(campaignId = campaignId, role = "assistant", content = "> $summary")
        )
        if (combatEnded) {
            messageDao.insertMessage(
                MessageEntity(
                    campaignId = campaignId,
                    role = "assistant",
                    content = "> 【系统通知】所有敌人均已被击败，战斗结束。"
                )
            )
        }
        if (outcome.hit == false) {
            sensoryController?.hapticCheckFailure()
        } else {
            sensoryController?.hapticCriticalSuccess()
        }
        sendAction(
            "[检定结果] 本地规则已完成唯一裁决：$summary。仅叙述这一既定结果，不要重新计算命中、伤害、治疗或资源，也不要为同一动作再次输出规则事件。"
        )
    }

    private fun selectedWeapon(intent: CheckIntent): WeaponProfile? {
        val weaponId = intent.meta["weapon_id"].orEmpty()
        return uiState.value.availableWeapons.firstOrNull { it.itemId == weaponId }
    }

    private fun selectedSpell(intent: CheckIntent): SpellProfile? {
        val spellId = intent.meta["spell_id"].orEmpty()
        return castableSpells().firstOrNull { it.spellId == spellId }
    }

    internal fun castableSpells(): List<SpellProfile> {
        val availableLevels = uiState.value.availableSpellSlotLevels
        return uiState.value.preparedSpells.filter { spell ->
            spell.slotLevel == 0 || spell.isRitual || spell.slotLevel in availableLevels
        }
    }

    private fun selectedTarget(intent: CheckIntent): xyz.sakulik.d20.app.domain.combat.Combatant? {
        return combatStateManager.getTarget(intent.meta["target_id"].orEmpty())
    }

    private fun buildEffectReason(
        subjectName: String,
        resolution: ActionResolution.NeedsEffectRoll
    ): String {
        return when {
            resolution.targetSaved == true -> "$subjectName：目标豁免成功，结算效果"
            resolution.targetSaved == false -> "$subjectName：目标豁免失败，结算效果"
            resolution.critical -> "$subjectName 暴击伤害"
            else -> "$subjectName 效果"
        }
    }

    private fun formatRuleOutcome(
        subjectName: String,
        outcome: RuleOutcome,
        targetHp: Int?,
        actualHealing: Int
    ): String {
        val parts = mutableListOf("【$subjectName】")
        when (outcome.hit) {
            true -> parts += if (outcome.critical) "暴击命中" else "命中"
            false -> parts += "未命中"
            null -> Unit
        }
        when (outcome.targetSaved) {
            true -> parts += "目标豁免成功"
            false -> parts += "目标豁免失败"
            null -> Unit
        }
        if (outcome.damage.isNotEmpty()) {
            parts += outcome.damage.joinToString("，") { component ->
                "${component.finalAmount} 点 ${component.type.ifBlank { "未分类" }}伤害" +
                    if (component.rawAmount != component.finalAmount) "（原始 ${component.rawAmount}）" else ""
            }
            if (targetHp != null) parts += "目标剩余 HP $targetHp"
        }
        if (outcome.healing > 0) parts += "恢复 $actualHealing HP"
        if (outcome.resourceChanges.isNotEmpty()) parts += "法术位已扣除"
        return parts.joinToString("；")
    }

    private fun CheckIntent.requiredExpression(): String {
        return meta["expression"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("动作缺少骰子表达式")
    }

    private fun ActionResolution.withResourceChanges(
        changes: Map<String, Int>
    ): ActionResolution {
        return if (this is ActionResolution.Completed) {
            copy(outcome = outcome.copy(resourceChanges = changes))
        } else {
            this
        }
    }

    private fun reportTypedRuleError(message: String): Boolean {
        sendEvent(MainUiEvent.Error(message))
        return true
    }

    private suspend fun handleInitiativeResult(
        character: CharacterEntity,
        submission: DiceSubmission,
        intent: CheckIntent
    ) {
        val expression = intent.requiredExpression()
        submission.validateAgainst(expression)?.let { error ->
            pendingCombatants = null
            reportTypedRuleError(error.message)
            return
        }
        val enemies = pendingCombatants
        pendingCombatants = null
        if (enemies == null) {
            reportTypedRuleError("先攻结算已失效，请重新开始战斗")
            return
        }
        val policy = combatPolicy(intent.meta["ruleset_id"] ?: character.activeSystem)
        startCombat(character, enemies, submission.total, policy)
    }

    private suspend fun handleDeathSaveResult(
        submittedRoll: Int,
        character: CharacterEntity,
        evaluationResult: xyz.sakulik.d20.app.domain.rules.dynamic.EvaluationResult
    ) {
        val resolvedRoll = evaluationResult.diceTraces.values
            .firstOrNull()?.firstOrNull() ?: submittedRoll
        val snapshot = DndLifeStateRules.snapshot(character.stats)
        val outcome = when (resolvedRoll) {
            20 -> "自然 20：恢复 1 HP，从濒死中苏醒。"
            1 -> "自然 1：计入 2 次失败。"
            in 10..19 -> "死亡豁免成功。"
            else -> "死亡豁免失败。"
        }
        val status = when {
            snapshot.hp > 0 -> "当前 HP ${snapshot.hp}，死亡豁免计数已重置。"
            snapshot.state == DndLifeState.DEAD -> "失败达到 3 次，角色死亡。"
            snapshot.state == DndLifeState.STABLE -> "成功达到 3 次，角色伤势稳定。"
            else -> "当前成功 ${snapshot.successes}/3，失败 ${snapshot.failures}/3。"
        }

        updateState { it.copy(isDeathSaveRollInProgress = false) }
        messageDao.insertMessage(
            MessageEntity(
                campaignId = campaignId,
                role = "assistant",
                content = "> 【死亡豁免】掷出 $resolvedRoll。$outcome\n> $status"
            )
        )
        messageDao.insertMessage(
            MessageEntity(
                campaignId = campaignId,
                role = "assistant",
                content = "[系统状态：死亡豁免掷出 $resolvedRoll；$status]",
                isHidden = true
            )
        )

        if (resolvedRoll >= 10) {
            sensoryController?.hapticCriticalSuccess()
        } else {
            sensoryController?.hapticCheckFailure()
        }
    }

    fun dismissDicePanel() {
        if (uiState.value.currentDiceIntent?.actionId == COMBAT_INITIATIVE_ACTION_ID) {
            pendingCombatants = null
        }
        updateState {
            it.copy(
                isDicePanelVisible = false,
                currentDiceIntent = null,
                isDeathSaveRollInProgress = false
            )
        }
    }

    private suspend fun startCombat(
        character: CharacterEntity,
        enemies: List<xyz.sakulik.d20.app.domain.combat.CombatantDefinition>,
        participantInitiative: Int,
        policy: RulesetCombatPolicy
    ) {
        val combatState = combatStateManager.startCombat(
            enemies = enemies,
            playerName = character.name,
            playerInitiative = participantInitiative,
            playerAc = character.stats["ac"]?.toIntOrNull() ?: 0,
            playerHp = character.stats["hp"]?.toIntOrNull() ?: 1,
            playerMaxHp = character.stats["max_hp"]?.toIntOrNull()
                ?: character.stats["hp"]?.toIntOrNull()
                ?: 1,
            playerTurnResources = policy.initialTurnResources(isPlayerTurn = true),
            defeatAtZeroHp = policy.rules.defeatAtZeroHp
        )
        gameStateDao.startCombat(
            campaignId = campaignId,
            combatants = enemies.map { combatant -> combatant.toEntity(campaignId) },
            session = combatState.toEntity(campaignId, policy)
        )
        updateState {
            it.copy(
                combatState = combatState,
                turnResourceLabels = combatState.turnResources.keys.associateWith(policy::resourceLabel)
            )
        }
        messageDao.insertMessage(
            MessageEntity(
                campaignId = campaignId,
                role = "assistant",
                content = "> 【行动顺序】玩家值为 $participantInitiative；当前轮到 ${combatState.currentCombatant?.name ?: "未知"}。"
            )
        )
        sensoryController?.hapticCombatStart()
    }

    private fun combatPolicy(rulesetId: String = uiState.value.activeRulesetId): RulesetCombatPolicy {
        val ruleset = RulesetRegistry.getRuleset(context, rulesetId)
        return if (ruleset == null) {
            RulesetCombatPolicy.generic(rulesetId)
        } else {
            RulesetCombatPolicy.from(ruleset)
        }
    }

    private fun canonicalCombatAction(actionId: String): String {
        return when (actionId) {
            DND_ATTACK_EFFECT_ACTION_ID -> DND_ATTACK_ACTION_ID
            DND_SPELL_EFFECT_ACTION_ID -> DND_CAST_ACTION_ID
            else -> actionId
        }
    }
}

private fun CombatState.toEntity(
    campaignId: String,
    policy: RulesetCombatPolicy
): CombatSessionEntity {
    val participantInitiative = combatants.firstOrNull { it.isPlayer }?.initiative ?: 0
    return CombatSessionEntity(
        campaignId = campaignId,
        round = round,
        initiativeQueue = initiativeQueue,
        currentTurnIndex = currentTurnIndex,
        rulesetId = policy.rulesetId,
        lifePolicy = policy.lifePolicyId,
        participantInitiative = participantInitiative,
        turnResources = turnResources,
        defeatAtZeroHp = defeatAtZeroHp,
        ongoingEffects = ongoingEffects
    )
}

private fun xyz.sakulik.d20.app.domain.combat.CombatantDefinition.toEntity(
    campaignId: String
): CombatantEntity {
    return CombatantEntity(
        campaignId = campaignId,
        id = id,
        name = name,
        initiative = initiative,
        ac = ac,
        hp = hp,
        maxHp = maxHp,
        resistances = resistances,
        vulnerabilities = vulnerabilities,
        immunities = immunities,
        savingThrows = savingThrows,
        attributes = attributes
    )
}
