package xyz.sakulik.d20.app.domain.rules.dynamic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicRulesetEngineTest {

    @Test
    fun detailedParserReturnsAuthorFacingJsonError() {
        val result = RulesetProvider.parseManifestDetailed("{not-json}")

        assertTrue(result is RulesetProvider.ParseResult.Invalid)
        assertEquals(
            "INVALID_RULESET_JSON",
            (result as RulesetProvider.ParseResult.Invalid).errors.single().code
        )
    }

    @Test
    fun useInjectedRollInsteadOfRollingAgain() {
        val ruleset = createRuleset(
            entryNodeId = "roll",
            nodes = mapOf(
                "roll" to RollNode(
                    diceFormula = "1d100",
                    outputVariable = "result",
                    nextNodeId = "check"
                ),
                "check" to ConditionNode(
                    leftOperandSource = "variable:result",
                    operator = "==",
                    rightOperandSource = "constant:73",
                    trueNodeId = "success",
                    falseNodeId = "failure"
                ),
                "success" to resultNode(ResultState.SUCCESS),
                "failure" to resultNode(ResultState.FAILURE)
            )
        )

        val result = ruleset.executePipeline(
            CheckIntent(
                actionId = "coc_check",
                meta = mapOf("expression" to "1d100"),
                diceSubmission = DiceSubmission.manual("1d100", 73)
            ),
            emptyMap()
        )

        assertEquals(ResultState.SUCCESS, result.state)
        assertEquals(listOf(73), result.diceTraces["roll_trace"])
    }

    @Test
    fun resolveActionIdFromIntentProperty() {
        val ruleset = createRuleset(
            entryNodeId = "branch",
            nodes = mapOf(
                "branch" to SwitchNode(
                    variable = "intent:actionId",
                    cases = mapOf("dnd_check" to "success"),
                    defaultNodeId = "failure"
                ),
                "success" to resultNode(ResultState.SUCCESS),
                "failure" to resultNode(ResultState.FAILURE)
            )
        )

        val result = ruleset.executePipeline(
            CheckIntent(actionId = "dnd_check", meta = emptyMap()),
            emptyMap()
        )

        assertEquals(ResultState.SUCCESS, result.state)
    }

    @Test
    fun missingNodeReturnsRuleErrorWithoutApplyingChanges() {
        val ruleset = createRuleset(
            entryNodeId = "change",
            nodes = mapOf(
                "change" to EffectNode(
                    targetType = "stat",
                    targetKey = "hp",
                    operation = "subtract",
                    valueSource = "constant:3",
                    nextNodeId = "missing"
                )
            )
        )

        val original = mapOf("hp" to "10", "max_hp" to "10")
        val result = ruleset.executePipeline(
            CheckIntent(actionId = "test", meta = emptyMap()),
            original
        )

        assertEquals("MISSING_RULE_NODE", result.errors.single().code)
        assertEquals(original, result.modifiedCharacterData)
    }

    @Test
    fun unknownResultReturnsRuleError() {
        val ruleset = createRuleset(
            entryNodeId = "unknown",
            nodes = mapOf("unknown" to resultNode(ResultState.UNKNOWN))
        )

        val result = ruleset.executePipeline(
            CheckIntent(actionId = "test", meta = emptyMap()),
            emptyMap()
        )

        assertEquals("UNKNOWN_RULE_RESULT", result.errors.single().code)
        assertTrue(!result.isValid)
    }

    @Test
    fun missingIntentValueReturnsRuleError() {
        val ruleset = createRuleset(
            entryNodeId = "condition",
            nodes = mapOf(
                "condition" to ConditionNode(
                    leftOperandSource = "intent:dc",
                    operator = ">=",
                    rightOperandSource = "constant:10",
                    trueNodeId = "success",
                    falseNodeId = "failure"
                ),
                "success" to resultNode(ResultState.SUCCESS),
                "failure" to resultNode(ResultState.FAILURE)
            )
        )

        val result = ruleset.executePipeline(
            CheckIntent(actionId = "test", meta = emptyMap()),
            emptyMap()
        )

        assertEquals("MISSING_INTENT_VALUE", result.errors.single().code)
    }

    @Test
    fun manifestValidationRejectsMissingEntryAndReferences() {
        val manifest = RulesetManifest(
            id = "invalid",
            name = "Invalid",
            version = "1",
            systemPromptInjection = SystemPromptInjection(""),
            characterTemplate = CharacterTemplate(emptyMap()),
            uiBlueprint = UiBlueprint("TEST", emptyMap()),
            mechanicsPipeline = MechanicsPipeline(
                entryNodeId = "missing_entry",
                nodes = mapOf(
                    "branch" to SwitchNode(
                        variable = "intent:actionId",
                        cases = mapOf("test" to "missing_target")
                    )
                )
            )
        )

        val errors = RulesetProvider.validateManifest(manifest)

        assertEquals(
            setOf("MISSING_ENTRY_NODE", "MISSING_REFERENCED_NODE"),
            errors.map { it.code }.toSet()
        )
    }

    @Test
    fun manifestValidationRejectsUnsafeOrDanglingCombatDeclarations() {
        val manifest = RulesetManifest(
            id = "invalid_combat",
            name = "Invalid Combat",
            version = "1",
            systemPromptInjection = SystemPromptInjection(""),
            characterTemplate = CharacterTemplate(emptyMap()),
            uiBlueprint = UiBlueprint("TEST", emptyMap()),
            combatRules = CombatRules(
                initiative = InitiativeRules(statTransform = "SCRIPT"),
                turnResources = mapOf("action" to -1),
                actionCosts = mapOf("strike" to mapOf("missing" to 1)),
                actionTimings = mapOf("strike" to "SCRIPT"),
                primaryActionResource = "missing",
                lifePolicy = "downloaded.ClassName",
                localActionHandler = "javascript:handler"
            ),
            mechanicsPipeline = MechanicsPipeline(
                entryNodeId = "result",
                nodes = mapOf("result" to resultNode(ResultState.SUCCESS))
            )
        )

        val errors = RulesetProvider.validateManifest(manifest)

        assertEquals(
            setOf(
                "UNSUPPORTED_INITIATIVE_TRANSFORM",
                "UNSUPPORTED_LIFE_POLICY",
                "UNSUPPORTED_LOCAL_ACTION_HANDLER",
                "INVALID_TURN_RESOURCE",
                "INVALID_ACTION_COST",
                "UNSUPPORTED_ACTION_TIMING",
                "INVALID_PRIMARY_ACTION_RESOURCE"
            ),
            errors.map { it.code }.toSet()
        )
    }

    @Test
    fun manifestValidationRejectsUnsupportedCheckPolicy() {
        val manifest = RulesetManifest(
            id = "invalid_check",
            name = "Invalid Check",
            version = "1",
            systemPromptInjection = SystemPromptInjection(""),
            characterTemplate = CharacterTemplate(emptyMap()),
            uiBlueprint = UiBlueprint("TEST", emptyMap()),
            checkRules = CheckRules(
                targetSource = "SCRIPT",
                modifierSource = "CLASS_NAME",
                equipmentBonusAppliesTo = "UNKNOWN",
                defaultActionId = ""
            ),
            mechanicsPipeline = MechanicsPipeline(
                entryNodeId = "result",
                nodes = mapOf("result" to resultNode(ResultState.SUCCESS))
            )
        )

        val errors = RulesetProvider.validateManifest(manifest)

        assertEquals(
            setOf(
                "UNSUPPORTED_CHECK_TARGET_SOURCE",
                "UNSUPPORTED_CHECK_MODIFIER_SOURCE",
                "UNSUPPORTED_EQUIPMENT_BONUS_TARGET",
                "MISSING_DEFAULT_CHECK_ACTION"
            ),
            errors.map { it.code }.toSet()
        )
    }

    @Test
    fun deathSaveUsesInjectedRollAndAccumulatesState() {
        val ruleset = createRuleset(
            entryNodeId = "death_save",
            nodes = mapOf(
                "death_save" to DeathSaveNode(
                    stableNodeId = "stable",
                    failureNodeId = "dead",
                    nextNodeId = "pending"
                ),
                "stable" to resultNode(ResultState.SUCCESS),
                "dead" to resultNode(ResultState.CRITICAL_FAILURE),
                "pending" to resultNode(ResultState.SUCCESS)
            )
        )

        val result = ruleset.executePipeline(
            CheckIntent(
                actionId = "dnd_check",
                meta = mapOf("raw_roll_injected" to "15")
            ),
            mapOf(
                "hp" to "0",
                "deathSaves" to "{\"successes\":1,\"failures\":1,\"isStable\":false}"
            )
        )

        val savedState = Json.parseToJsonElement(
            result.modifiedCharacterData.getValue("deathSaves").toString()
        ).jsonObject
        assertEquals(2, savedState.getValue("successes").jsonPrimitive.int)
        assertEquals(1, savedState.getValue("failures").jsonPrimitive.int)
        assertEquals(listOf(15), result.diceTraces["death_save_trace"])
    }

    @Test
    fun naturalOneReachesThirdDeathSaveFailure() {
        val ruleset = createRuleset(
            entryNodeId = "death_save",
            nodes = mapOf(
                "death_save" to DeathSaveNode(
                    failureNodeId = "dead",
                    nextNodeId = "pending"
                ),
                "dead" to resultNode(ResultState.CRITICAL_FAILURE),
                "pending" to resultNode(ResultState.FAILURE)
            )
        )

        val result = ruleset.executePipeline(
            CheckIntent(
                actionId = "dnd_check",
                meta = mapOf("raw_roll_injected" to "1")
            ),
            mapOf(
                "hp" to "0",
                "deathSaves" to "{\"successes\":0,\"failures\":1,\"isStable\":false}"
            )
        )

        assertEquals(ResultState.CRITICAL_FAILURE, result.state)
    }

    @Test
    fun thirdDeathSaveSuccessStabilizesCharacter() {
        val ruleset = createRuleset(
            entryNodeId = "death_save",
            nodes = mapOf(
                "death_save" to DeathSaveNode(
                    stableNodeId = "stable",
                    failureNodeId = "dead",
                    nextNodeId = "pending"
                ),
                "stable" to resultNode(ResultState.SUCCESS),
                "dead" to resultNode(ResultState.CRITICAL_FAILURE),
                "pending" to resultNode(ResultState.SUCCESS)
            )
        )

        val result = ruleset.executePipeline(
            CheckIntent(
                actionId = "dnd_check",
                meta = mapOf("raw_roll_injected" to "12")
            ),
            mapOf(
                "hp" to "0",
                "deathSaves" to "{\"successes\":2,\"failures\":1,\"isStable\":false}"
            )
        )

        val savedState = Json.parseToJsonElement(
            result.modifiedCharacterData.getValue("deathSaves").toString()
        ).jsonObject
        assertEquals(ResultState.SUCCESS, result.state)
        assertEquals(3, savedState.getValue("successes").jsonPrimitive.int)
        assertEquals(0, savedState.getValue("failures").jsonPrimitive.int)
        assertEquals(true, savedState.getValue("isStable").jsonPrimitive.boolean)
        assertEquals(listOf(12), result.diceTraces["death_save_trace"])
    }

    @Test
    fun naturalTwentyRestoresHpAndResetsDeathSaves() {
        val ruleset = createRuleset(
            entryNodeId = "death_save",
            nodes = mapOf(
                "death_save" to DeathSaveNode(
                    stableNodeId = "revived",
                    failureNodeId = "dead",
                    nextNodeId = "pending"
                ),
                "revived" to resultNode(ResultState.SUCCESS),
                "dead" to resultNode(ResultState.CRITICAL_FAILURE),
                "pending" to resultNode(ResultState.SUCCESS)
            )
        )

        val result = ruleset.executePipeline(
            CheckIntent(
                actionId = "dnd_check",
                meta = mapOf("raw_roll_injected" to "20")
            ),
            mapOf(
                "hp" to "0",
                "deathSaves" to "{\"successes\":2,\"failures\":2,\"isStable\":false}"
            )
        )

        val savedState = Json.parseToJsonElement(
            result.modifiedCharacterData.getValue("deathSaves").toString()
        ).jsonObject
        assertEquals(ResultState.SUCCESS, result.state)
        assertEquals("1", result.modifiedCharacterData.getValue("hp").toString())
        assertEquals(0, savedState.getValue("successes").jsonPrimitive.int)
        assertEquals(0, savedState.getValue("failures").jsonPrimitive.int)
        assertEquals(false, savedState.getValue("isStable").jsonPrimitive.boolean)
        assertEquals(listOf(20), result.diceTraces["death_save_trace"])
    }

    @Test
    fun consumeResourceUpdatesJsonContainingWhitespace() {
        val ruleset = createRuleset(
            entryNodeId = "consume",
            nodes = mapOf(
                "consume" to ConsumeResourceNode(
                    resourcePath = "spell_slots.level_1",
                    amountSource = "constant:1",
                    failNodeId = "failure",
                    nextNodeId = "success"
                ),
                "success" to resultNode(ResultState.SUCCESS),
                "failure" to resultNode(ResultState.FAILURE)
            )
        )

        val result = ruleset.executePipeline(
            CheckIntent(actionId = "dnd_cast", meta = emptyMap()),
            mapOf(
                "resources" to "{\"spell_slots\": {\"level_1\": {\"current\": 4, \"max\": 4}}}"
            )
        )

        assertEquals(ResultState.SUCCESS, result.state)
        val resources = result.modifiedCharacterData.getValue("resources").toString()
        val current = Json.parseToJsonElement(resources)
            .jsonObject.getValue("spell_slots")
            .jsonObject.getValue("level_1")
            .jsonObject.getValue("current")
            .jsonPrimitive.int
        assertEquals(3, current)
    }

    @Test
    fun targetedAttackUsesInjectedNaturalOne() {
        val ruleset = createRuleset(
            entryNodeId = "attack",
            nodes = mapOf(
                "attack" to TargetedAttackNode(
                    targetIdSource = "intent:target_id",
                    attackBonusSource = "intent:modifier",
                    damageFormula = "1d8",
                    damageType = "slashing",
                    outputVariable = "damage",
                    hitNodeId = "success",
                    missNodeId = "failure"
                ),
                "success" to resultNode(ResultState.SUCCESS),
                "failure" to resultNode(ResultState.FAILURE)
            )
        )

        val result = ruleset.executePipeline(
            CheckIntent(
                actionId = "dnd_attack",
                meta = mapOf(
                    "raw_roll_injected" to "1",
                    "modifier" to "99",
                    "target_id" to "target",
                    "target_ac" to "10"
                )
            ),
            emptyMap()
        )

        assertEquals(ResultState.FAILURE, result.state)
        assertEquals(listOf(1), result.diceTraces["attack_trace"])
    }

    @Test
    fun targetedAttackUsesProvidedAcInsteadOfFallbackValue() {
        val ruleset = targetedAttackRuleset()

        val result = ruleset.executePipeline(
            attackIntent(
                roll = 14,
                extraMeta = mapOf("target_ac" to "25")
            ),
            mapOf("str_mod" to "0")
        )

        assertEquals(ResultState.FAILURE, result.state)
    }

    @Test
    fun targetedAttackFailsWhenTargetAcIsMissing() {
        val result = targetedAttackRuleset().executePipeline(
            attackIntent(roll = 20),
            mapOf("str_mod" to "0")
        )

        assertEquals(ResultState.FAILURE, result.state)
        assertTrue(result.logs.any { it.contains("拒绝使用模拟数据") })
    }

    @Test
    fun targetedAttackAppliesResistanceVulnerabilityAndImmunity() {
        val ruleset = targetedAttackRuleset()
        val character = mapOf("str_mod" to "3")

        val resisted = ruleset.executePipeline(
            attackIntent(15, mapOf("target_ac" to "10", "target_resistances" to " FIRE ")),
            character
        )
        val vulnerable = ruleset.executePipeline(
            attackIntent(15, mapOf("target_ac" to "10", "target_vulnerabilities" to "fire")),
            character
        )
        val immune = ruleset.executePipeline(
            attackIntent(15, mapOf("target_ac" to "10", "target_immunities" to "fire")),
            character
        )

        assertEquals(2f, resisted.resolvedValues["final_damage"])
        assertEquals(8f, vulnerable.resolvedValues["final_damage"])
        assertEquals(0f, immune.resolvedValues["final_damage"])
    }

    private fun targetedAttackRuleset(): DynamicRulesetImpl {
        return createRuleset(
            entryNodeId = "attack",
            nodes = mapOf(
                "attack" to TargetedAttackNode(
                    targetIdSource = "intent:target_id",
                    attackBonusSource = "intent:modifier",
                    damageFormula = "1d1",
                    damageType = "Fire",
                    outputVariable = "final_damage",
                    hitNodeId = "success",
                    missNodeId = "failure"
                ),
                "success" to resultNode(ResultState.SUCCESS),
                "failure" to resultNode(ResultState.FAILURE)
            )
        )
    }

    private fun attackIntent(
        roll: Int,
        extraMeta: Map<String, String> = emptyMap()
    ): CheckIntent {
        return CheckIntent(
            actionId = "dnd_attack",
            meta = mapOf(
                "raw_roll_injected" to roll.toString(),
                "modifier" to "0",
                "target_id" to "target"
            ) + extraMeta
        )
    }

    private fun createRuleset(
        entryNodeId: String,
        nodes: Map<String, LogicNode>
    ): DynamicRulesetImpl {
        return DynamicRulesetImpl(
            RulesetManifest(
                id = "test",
                name = "Test",
                version = "1",
                systemPromptInjection = SystemPromptInjection(""),
                characterTemplate = CharacterTemplate(emptyMap<String, JsonElement>()),
                uiBlueprint = UiBlueprint("TEST", emptyMap()),
                mechanicsPipeline = MechanicsPipeline(entryNodeId, nodes)
            )
        )
    }

    private fun resultNode(state: ResultState): EffectNode {
        return EffectNode(
            targetType = "result_state",
            targetKey = "state",
            operation = "set",
            valueSource = "constant:${state.name}"
        )
    }
}
