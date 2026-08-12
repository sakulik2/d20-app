package xyz.sakulik.d20.app.data.model

import xyz.sakulik.d20.app.domain.combat.CombatantDefinition
import xyz.sakulik.d20.app.domain.rules.dynamic.EncounterProfile
import xyz.sakulik.d20.app.domain.rules.dynamic.IRuleset

private fun normalizeDiceExpression(expression: String): String =
    expression.filterNot(Char::isWhitespace).lowercase()

sealed interface TrustedGameEvent {
    data class RequireRoll(val request: GameEvent.RequireRoll) : TrustedGameEvent
    data class AddNarrativeItem(val item: GameEvent.AddItem) : TrustedGameEvent
    data class StartCombat(val combatants: List<CombatantDefinition>) : TrustedGameEvent
}

data class GameEventAuthorizationContext(
    val defaultActionId: String,
    val allowedActionIds: Set<String>,
    val allowedDiceExpressions: Map<String, Set<String>>,
    val characterStatIds: Set<String>,
    val encounterProfiles: Map<String, EncounterProfile>,
    val combatTargetIds: Set<String> = emptySet(),
    val weaponIds: Set<String> = emptySet(),
    val spellIds: Set<String> = emptySet(),
    val availableSpellSlotLevels: Set<Int> = emptySet(),
    val canStartCombat: Boolean = true
) {
    companion object {
        fun from(
            ruleset: IRuleset,
            characterStats: Map<String, String>,
            combatTargetIds: Set<String> = emptySet(),
            weaponIds: Set<String> = emptySet(),
            spellIds: Set<String> = emptySet(),
            availableSpellSlotLevels: Set<Int> = emptySet(),
            canStartCombat: Boolean = true
        ): GameEventAuthorizationContext {
            val checkRules = ruleset.checkRules
            val combatRules = ruleset.combatRules
            return GameEventAuthorizationContext(
                defaultActionId = checkRules.defaultActionId,
                allowedActionIds = checkRules.allowedDiceExpressions.keys,
                allowedDiceExpressions = checkRules.allowedDiceExpressions.mapValues { (_, values) ->
                    values.mapTo(linkedSetOf(), ::normalizeDiceExpression)
                },
                characterStatIds = characterStats.keys,
                encounterProfiles = combatRules.encounterProfiles,
                combatTargetIds = combatTargetIds,
                weaponIds = weaponIds,
                spellIds = spellIds,
                availableSpellSlotLevels = availableSpellSlotLevels,
                canStartCombat = canStartCombat
            )
        }
    }
}

sealed interface GameEventAuthorizationResult {
    data class Authorized(val events: List<TrustedGameEvent>) : GameEventAuthorizationResult
    data class Rejected(val message: String) : GameEventAuthorizationResult
}

object TrustedGameEventAuthorizer {
    fun authorize(
        events: List<GameEvent>,
        context: GameEventAuthorizationContext
    ): GameEventAuthorizationResult {
        GameEventBatchValidator.validate(events)?.let { error ->
            return GameEventAuthorizationResult.Rejected(error.message)
        }

        val trustedEvents = mutableListOf<TrustedGameEvent>()
        events.forEachIndexed { index, event ->
            when (event) {
                is GameEvent.RequireRoll -> {
                    val actionId = event.actionId ?: context.defaultActionId
                    if (actionId.isBlank() || actionId !in context.allowedActionIds) {
                        return rejected(
                            index,
                            "action_id ${event.actionId ?: "<缺失>"} 未由当前规则包授权"
                        )
                    }
                    val allowedExpressions = context.allowedDiceExpressions[actionId].orEmpty()
                    if (normalizeDiceExpression(event.expression) !in allowedExpressions) {
                        return rejected(
                            index,
                            "动作 $actionId 不允许骰式 ${event.expression}"
                        )
                    }
                    val statId = event.statId?.let { requestedStatId ->
                        context.characterStatIds.firstOrNull {
                            it.equals(requestedStatId, ignoreCase = true)
                        } ?: return rejected(
                            index,
                            "stat_id $requestedStatId 不存在于当前角色档案"
                        )
                    }
                    event.targetId?.let { targetId ->
                        if (targetId !in context.combatTargetIds) {
                            return rejected(index, "target_id $targetId 不存在于当前战斗")
                        }
                    }
                    event.weaponId?.let { weaponId ->
                        if (weaponId !in context.weaponIds) {
                            return rejected(index, "weapon_id $weaponId 不存在于本地武器档案")
                        }
                    }
                    event.spellId?.let { spellId ->
                        if (spellId !in context.spellIds) {
                            return rejected(index, "spell_id $spellId 不存在于本地法术档案")
                        }
                    }
                    event.slotLevel?.let { slotLevel ->
                        if (slotLevel != 0 && slotLevel !in context.availableSpellSlotLevels) {
                            return rejected(index, "slot_level $slotLevel 当前不可用")
                        }
                    }
                    trustedEvents += TrustedGameEvent.RequireRoll(
                        event.copy(
                            expression = normalizeDiceExpression(event.expression),
                            actionId = actionId,
                            statId = statId
                        )
                    )
                }
                is GameEvent.AddItem -> trustedEvents += TrustedGameEvent.AddNarrativeItem(event)
                is GameEvent.StartCombat -> {
                    if (!context.canStartCombat) {
                        return rejected(index, "当前已有战斗或待结算的开战请求")
                    }
                    val combatants = event.combatants.map { request ->
                        val profile = context.encounterProfiles[request.profileId]
                            ?: return rejected(
                                index,
                                "profile_id ${request.profileId} 未由当前规则包授权"
                            )
                        profile.toCombatantDefinition(request)
                    }
                    trustedEvents += TrustedGameEvent.StartCombat(combatants)
                }
            }
        }
        return GameEventAuthorizationResult.Authorized(trustedEvents)
    }

    private fun rejected(index: Int, reason: String): GameEventAuthorizationResult.Rejected =
        GameEventAuthorizationResult.Rejected("game_events[$index]：$reason")

    private fun EncounterProfile.toCombatantDefinition(
        request: EncounterParticipantRequest
    ): CombatantDefinition = CombatantDefinition(
        id = request.id,
        name = request.name,
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
