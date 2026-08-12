package xyz.sakulik.d20.app.domain.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.sakulik.d20.app.domain.rules.dynamic.CheckRules

class RulesetCheckPolicyTest {

    @Test
    fun abilityModifierCheckUsesLocalStatAndEventTarget() {
        val policy = RulesetCheckPolicy.from(
            CheckRules(
                targetSource = "EVENT",
                modifierSource = "ABILITY_MODIFIER",
                defaultActionId = "dnd_check",
                requiredTargetActionIds = listOf("dnd_check"),
                targetLabel = "DC"
            )
        )

        val result = policy.resolve(null, 16, 15, null, null)

        assertEquals("dnd_check", result.actionId)
        assertEquals(15, result.targetValue)
        assertEquals(3, result.modifier)
        assertNull(policy.validationError(result))
    }

    @Test
    fun statTargetCheckUsesCharacterValueAndEquipmentOnTarget() {
        val policy = RulesetCheckPolicy.from(
            CheckRules(
                targetSource = "STAT_VALUE",
                modifierSource = "EVENT",
                equipmentBonusAppliesTo = "TARGET",
                defaultActionId = "percentile_check",
                requiredTargetActionIds = listOf("percentile_check"),
                targetLabel = "目标值"
            )
        )

        val result = policy.resolve(null, 70, 20, null, 2)
        val enhanced = policy.applyEquipmentBonus(
            targetValue = result.targetValue!!,
            modifier = result.modifier,
            bonus = 5
        )

        assertEquals(70, result.targetValue)
        assertEquals(75 to 2, enhanced)
        assertNull(policy.validationError(result))
    }

    @Test
    fun requiredTargetFailureIsRulesetDriven() {
        val policy = RulesetCheckPolicy.from(
            CheckRules(
                defaultActionId = "check",
                requiredTargetActionIds = listOf("check"),
                targetLabel = "难度"
            )
        )

        val result = policy.resolve(null, null, null, null, null)

        assertEquals("检定缺少有效难度", policy.validationError(result))
    }

    @Test
    fun statInferenceUsesRulesetAliases() {
        val policy = RulesetCheckPolicy.from(
            CheckRules(statAliases = mapOf("agility" to listOf("身手", "灵巧")))
        )

        assertEquals(
            "agility",
            policy.inferStatId("进行一次身手检定", mapOf("agility" to "65"))
        )
    }

    @Test
    fun statInferenceMatchesConfiguredKeysIgnoringCase() {
        val policy = RulesetCheckPolicy.from(
            CheckRules(statAliases = mapOf("dex" to listOf("敏捷", "dexterity")))
        )

        assertEquals(
            "DEX",
            policy.inferStatId("进行一次敏捷检定", mapOf("DEX" to "14"))
        )
    }

    @Test
    fun explicitStatIdMatchesCharacterKeysIgnoringCase() {
        val policy = RulesetCheckPolicy.from(CheckRules())

        assertEquals(
            "DEX",
            policy.resolveStatId("dex", "无属性提示", mapOf("DEX" to "14"))
        )
    }

    @Test
    fun equipmentBonusCanBeScopedToOrdinaryChecks() {
        val policy = RulesetCheckPolicy.from(
            CheckRules(equipmentBonusActionIds = listOf("ordinary_check"))
        )

        assertEquals(true, policy.appliesEquipmentBonus("ORDINARY_CHECK"))
        assertEquals(false, policy.appliesEquipmentBonus("typed_attack"))
    }
}
