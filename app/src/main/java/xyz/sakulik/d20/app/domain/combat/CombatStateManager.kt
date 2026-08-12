package xyz.sakulik.d20.app.domain.combat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CombatantDefinition(
    val id: String,
    val name: String,
    val initiative: Int,
    val ac: Int = 0,
    val hp: Int = 1,
    @SerialName("max_hp")
    val maxHp: Int = hp,
    val resistances: List<String> = emptyList(),
    val vulnerabilities: List<String> = emptyList(),
    val immunities: List<String> = emptyList(),
    @SerialName("saving_throws")
    val savingThrows: Map<String, Int> = emptyMap(),
    val attributes: Map<String, String> = emptyMap()
)

@Serializable
data class Combatant(
    val id: String,
    val name: String,
    val initiative: Int,
    val ac: Int,
    val hp: Int,
    val maxHp: Int,
    val resistances: List<String> = emptyList(),
    val vulnerabilities: List<String> = emptyList(),
    val immunities: List<String> = emptyList(),
    val savingThrows: Map<String, Int> = emptyMap(),
    val attributes: Map<String, String> = emptyMap(),
    val isPlayer: Boolean = false
) {
    fun isDefeated(defeatAtZeroHp: Boolean): Boolean = defeatAtZeroHp && hp <= 0
}

@Serializable
data class CombatState(
    val isActive: Boolean = false,
    val round: Int = 1,
    val combatants: List<Combatant> = emptyList(),
    val initiativeQueue: List<String> = emptyList(),
    val currentTurnIndex: Int = 0,
    val turnResources: Map<String, Int> = emptyMap(),
    val defeatAtZeroHp: Boolean = false,
    val ongoingEffects: List<OngoingEffect> = emptyList()
) {
    val currentCombatant: Combatant?
        get() = initiativeQueue.getOrNull(currentTurnIndex)
            ?.let { currentId -> combatants.firstOrNull { it.id == currentId } }

    val availableTargets: List<Combatant>
        get() = combatants.filter { !it.isPlayer && !it.isDefeated(defeatAtZeroHp) }

    val isPlayerTurn: Boolean
        get() = currentCombatant?.isPlayer == true
}

class CombatStateManager {
    private var state = CombatState()

    fun startCombat(
        enemies: List<CombatantDefinition>,
        playerName: String,
        playerInitiative: Int,
        playerAc: Int,
        playerHp: Int,
        playerMaxHp: Int = playerHp,
        playerTurnResources: Map<String, Int> = emptyMap(),
        defeatAtZeroHp: Boolean = false
    ): CombatState {
        val player = Combatant(
            id = PLAYER_ID,
            name = playerName,
            initiative = playerInitiative,
            ac = playerAc.coerceAtLeast(0),
            hp = playerHp.coerceAtLeast(0),
            maxHp = playerMaxHp.coerceAtLeast(playerHp.coerceAtLeast(1)),
            isPlayer = true
        )
        val uniqueEnemies = enemies
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .distinctBy { it.id }
            .map { definition ->
                Combatant(
                    id = definition.id,
                    name = definition.name,
                    initiative = definition.initiative,
                    ac = definition.ac.coerceAtLeast(0),
                    hp = definition.hp,
                    maxHp = definition.maxHp.coerceAtLeast(definition.hp.coerceAtLeast(1)),
                    resistances = definition.resistances.normalizedDamageTypes(),
                    vulnerabilities = definition.vulnerabilities.normalizedDamageTypes(),
                    immunities = definition.immunities.normalizedDamageTypes(),
                    savingThrows = definition.savingThrows.mapKeys { it.key.lowercase() },
                    attributes = definition.attributes
                )
            }
        val combatants = listOf(player) + uniqueEnemies
        state = CombatState(
            isActive = true,
            combatants = combatants,
            initiativeQueue = combatants
                .sortedWith(compareByDescending<Combatant> { it.initiative }.thenBy { it.id })
                .map { it.id },
            turnResources = playerTurnResources,
            defeatAtZeroHp = defeatAtZeroHp
        )
        return state
    }

    fun restoreCombat(
        enemies: List<CombatantDefinition>,
        playerName: String,
        playerInitiative: Int,
        playerAc: Int,
        playerHp: Int,
        playerMaxHp: Int = playerHp,
        round: Int,
        initiativeQueue: List<String>,
        currentTurnIndex: Int,
        turnResources: Map<String, Int>,
        defeatAtZeroHp: Boolean,
        ongoingEffects: List<OngoingEffect> = emptyList()
    ): CombatState {
        startCombat(
            enemies,
            playerName,
            playerInitiative,
            playerAc,
            playerHp,
            playerMaxHp,
            playerTurnResources = emptyMap(),
            defeatAtZeroHp = defeatAtZeroHp
        )
        val validQueue = initiativeQueue.filter { id -> state.combatants.any { it.id == id } }
            .ifEmpty { state.initiativeQueue }
        state = state.copy(
            round = round.coerceAtLeast(1),
            initiativeQueue = validQueue,
            currentTurnIndex = currentTurnIndex.coerceIn(0, validQueue.lastIndex.coerceAtLeast(0)),
            turnResources = turnResources,
            ongoingEffects = ongoingEffects.filter { effect ->
                effect.remainingTicks > 0 && state.combatants.any { it.id == effect.targetId }
            }
        )
        return state
    }

    fun currentState(): CombatState = state

    fun restoreState(snapshot: CombatState): CombatState {
        state = snapshot
        return state
    }

    fun getTarget(targetId: String): Combatant? {
        return state.availableTargets.firstOrNull { it.id == targetId }
    }

    fun applyDamage(targetId: String, damage: Int): CombatState {
        if (damage <= 0) return state
        val target = state.combatants.firstOrNull { it.id == targetId && !it.isPlayer }
            ?: return state
        return setTargetHp(targetId, target.hp - damage)
    }

    fun setTargetHp(targetId: String, hp: Int): CombatState {
        state = state.copy(
            combatants = state.combatants.map { combatant ->
                if (combatant.id == targetId && !combatant.isPlayer) {
                    combatant.copy(hp = hp.coerceIn(0, combatant.maxHp))
                } else {
                    combatant
                }
            }
        )
        return state
    }

    fun addOngoingEffect(effect: OngoingEffect): CombatState {
        if (!state.isActive || state.combatants.none { it.id == effect.targetId }) return state
        state = state.copy(ongoingEffects = state.ongoingEffects.withEffect(effect))
        return state
    }

    fun removeOngoingEffect(effectId: String): CombatState {
        state = state.copy(ongoingEffects = state.ongoingEffects.filterNot { it.id == effectId })
        return state
    }

    fun nextTurn(playerTurnResources: Map<String, Int> = emptyMap()): CombatState {
        return advanceTurn(playerTurnResources).state
    }

    fun advanceTurn(playerTurnResources: Map<String, Int> = emptyMap()): TurnAdvanceResult {
        if (!state.isActive || state.initiativeQueue.isEmpty()) return state
            .let { TurnAdvanceResult(it, emptyList(), combatEnded = !it.isActive) }
        val allTicks = mutableListOf<EffectTickResult>()
        state.currentCombatant?.id?.let { participantId ->
            allTicks += tickEffects(participantId, EffectTickTiming.TURN_END)
        }
        if (state.availableTargets.isEmpty()) {
            state = state.copy(isActive = false)
            return TurnAdvanceResult(state, allTicks, combatEnded = true)
        }
        var nextIndex = state.currentTurnIndex
        var nextRound = state.round
        repeat(state.initiativeQueue.size) {
            nextIndex++
            if (nextIndex >= state.initiativeQueue.size) {
                nextIndex = 0
                nextRound++
            }
            val candidate = state.initiativeQueue[nextIndex]
            val combatant = state.combatants.firstOrNull { it.id == candidate }
            if (combatant != null && !combatant.isDefeated(state.defeatAtZeroHp)) {
                state = state.copy(round = nextRound, currentTurnIndex = nextIndex)
                    .refreshParticipantResources(playerTurnResources)
                allTicks += tickEffects(candidate, EffectTickTiming.TURN_START)
                val activeCandidate = state.combatants.firstOrNull { it.id == candidate }
                if (state.availableTargets.isEmpty()) {
                    state = state.copy(isActive = false)
                    return TurnAdvanceResult(state, allTicks, combatEnded = true)
                }
                if (activeCandidate != null && !activeCandidate.isDefeated(state.defeatAtZeroHp)) {
                    return TurnAdvanceResult(state, allTicks, combatEnded = false)
                }
            }
        }
        state = state.copy(isActive = false)
        return TurnAdvanceResult(state, allTicks, combatEnded = true)
    }

    fun endCombat(): CombatState {
        state = CombatState()
        return state
    }

    companion object {
        const val PLAYER_ID = "player"
    }

    private fun CombatState.refreshParticipantResources(
        playerTurnResources: Map<String, Int>
    ): CombatState {
        return if (isPlayerTurn) copy(turnResources = playerTurnResources) else this
    }

    private fun tickEffects(
        participantId: String,
        timing: EffectTickTiming
    ): List<EffectTickResult> {
        val dueEffects = state.ongoingEffects.filter { effect ->
            effect.targetId == participantId && effect.timing == timing
        }
        if (dueEffects.isEmpty()) return emptyList()
        val ticks = mutableListOf<EffectTickResult>()
        var combatants = state.combatants
        dueEffects.forEach { effect ->
            combatants = combatants.map { combatant ->
                if (combatant.id != effect.targetId) return@map combatant
                val nextHp = when (effect.operation) {
                    EffectOperation.DAMAGE -> combatant.hp - effect.amount
                    EffectOperation.HEAL -> combatant.hp + effect.amount
                }.coerceIn(0, combatant.maxHp)
                ticks += EffectTickResult(effect, combatant.hp, nextHp)
                combatant.copy(hp = nextHp)
            }
        }
        val dueIds = dueEffects.map(OngoingEffect::id).toSet()
        val effects = state.ongoingEffects.mapNotNull { effect ->
            if (effect.id !in dueIds) effect else effect.copy(
                remainingTicks = effect.remainingTicks - 1
            ).takeIf { it.remainingTicks > 0 }
        }
        state = state.copy(
            combatants = combatants,
            ongoingEffects = effects.filter { effect ->
                val target = combatants.firstOrNull { it.id == effect.targetId }
                target != null && !target.isDefeated(state.defeatAtZeroHp)
            }
        )
        return ticks
    }
}

private fun List<String>.normalizedDamageTypes(): List<String> {
    return map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
}
