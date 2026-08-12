package xyz.sakulik.d20.app.domain.rules.dynamic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RulesetAssetContractTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun dndChecksUseTotalInsteadOfAutomaticNaturalResults() {
        val ruleset = loadRuleset("dnd_5e")

        val naturalOne = ruleset.executePipeline(
            dndCheck(roll = 1, dc = 1),
            dndCharacter(hp = 10)
        )
        val naturalTwenty = ruleset.executePipeline(
            dndCheck(roll = 20, dc = 25),
            dndCharacter(hp = 10)
        )

        assertEquals(ResultState.SUCCESS, naturalOne.state)
        assertEquals(ResultState.FAILURE, naturalTwenty.state)
        assertTrue(naturalOne.errors.isEmpty())
        assertTrue(naturalTwenty.errors.isEmpty())
    }

    @Test
    fun bundledRulesetsPassManifestValidation() {
        val dnd = loadRuleset("dnd_5e")
        val coc = loadRuleset("coc_7e")

        assertEquals("dnd_5e", dnd.id)
        assertEquals("ABILITY_MODIFIER", dnd.checkRules.modifierSource)
        assertEquals("DC", dnd.checkRules.targetLabel)
        assertEquals("coc_7e", coc.id)
        assertEquals("STAT_VALUE", coc.checkRules.targetSource)
        assertEquals("目标值", coc.checkRules.targetLabel)
    }

    @Test
    fun dndAdvantageConsumesBothDiceAndKeepsHighest() {
        val ruleset = loadRuleset("dnd_5e")
        val result = ruleset.executePipeline(
            CheckIntent(
                actionId = "dnd_check",
                meta = mapOf(
                    "expression" to "2d20kh1",
                    "modifier" to "0",
                    "dc" to "15",
                    "roll_state" to "ADVANTAGE"
                ),
                diceSubmission = DiceSubmission.virtual("2d20kh1", listOf(7, 18))
            ),
            dndCharacter(hp = 10)
        )

        assertEquals(ResultState.SUCCESS, result.state)
        assertEquals(listOf(7, 18), result.diceTraces["roll_advantage_trace"])
        assertEquals(18f, result.resolvedValues["raw_roll"])
    }

    @Test
    fun dndAdvantageAcceptsOfflineFinalResultWithoutRawDice() {
        val ruleset = loadRuleset("dnd_5e")
        val result = ruleset.executePipeline(
            CheckIntent(
                actionId = "dnd_check",
                meta = mapOf(
                    "expression" to "2d20kh1",
                    "modifier" to "0",
                    "dc" to "15",
                    "roll_state" to "ADVANTAGE"
                ),
                diceSubmission = DiceSubmission.manual("2d20kh1", 18)
            ),
            dndCharacter(hp = 10)
        )

        assertEquals(ResultState.SUCCESS, result.state)
        assertEquals(listOf(18), result.diceTraces["roll_advantage_trace"])
        assertEquals(18f, result.resolvedValues["raw_roll"])
    }

    @Test
    fun zeroHpDoesNotRewriteOrdinaryCheckAsDeathSave() {
        val ruleset = loadRuleset("dnd_5e")
        val result = ruleset.executePipeline(
            dndCheck(roll = 12, dc = 10),
            dndCharacter(hp = 0)
        )

        assertEquals(ResultState.SUCCESS, result.state)
        assertEquals(DEFAULT_DEATH_SAVES, result.modifiedCharacterData["deathSaves"])
        assertFalse(result.diceTraces.containsKey("death_save_trigger_trace"))
    }

    @Test
    fun explicitDeathSaveStillUpdatesDeathSaveState() {
        val ruleset = loadRuleset("dnd_5e")
        val result = ruleset.executePipeline(
            CheckIntent(
                actionId = "dnd_death_save",
                meta = mapOf("expression" to "1d20"),
                diceSubmission = DiceSubmission.manual("1d20", 12)
            ),
            dndCharacter(hp = 0)
        )

        assertEquals(ResultState.SUCCESS, result.state)
        assertEquals(
            "{\"successes\":1,\"failures\":0,\"isStable\":false}",
            result.modifiedCharacterData["deathSaves"]
        )
    }

    @Test
    fun cocFumbleBoundaryDependsOnTargetValue() {
        val ruleset = loadRuleset("coc_7e")

        val belowFifty = ruleset.executePipeline(cocCheck(roll = 96, target = 49), emptyMap())
        val fifty = ruleset.executePipeline(cocCheck(roll = 96, target = 50), emptyMap())
        val hundred = ruleset.executePipeline(cocCheck(roll = 100, target = 50), emptyMap())

        assertEquals(ResultState.CRITICAL_FAILURE, belowFifty.state)
        assertEquals(ResultState.FAILURE, fifty.state)
        assertEquals(ResultState.CRITICAL_FAILURE, hundred.state)
    }

    @Test
    fun cocPreservesSuccessLevels() {
        val ruleset = loadRuleset("coc_7e")

        assertEquals(ResultState.CRITICAL_SUCCESS, ruleset.executePipeline(cocCheck(1, 50), emptyMap()).state)
        assertEquals(ResultState.EXTREME_SUCCESS, ruleset.executePipeline(cocCheck(5, 50), emptyMap()).state)
        assertEquals(ResultState.EXTREME_SUCCESS, ruleset.executePipeline(cocCheck(10, 50), emptyMap()).state)
        assertEquals(ResultState.HARD_SUCCESS, ruleset.executePipeline(cocCheck(20, 50), emptyMap()).state)
        assertEquals(ResultState.REGULAR_SUCCESS, ruleset.executePipeline(cocCheck(40, 50), emptyMap()).state)
    }

    private fun loadRuleset(id: String): IRuleset {
        val json = context.assets.open("rulesets/$id.json")
            .bufferedReader()
            .use { it.readText() }
        return requireNotNull(RulesetProvider.parseManifest(json))
    }

    private fun dndCheck(roll: Int, dc: Int): CheckIntent {
        return CheckIntent(
            actionId = "dnd_check",
            meta = mapOf(
                "expression" to "1d20",
                "modifier" to "0",
                "dc" to dc.toString(),
                "roll_state" to "NORMAL"
            ),
            diceSubmission = DiceSubmission.manual("1d20", roll)
        )
    }

    private fun cocCheck(roll: Int, target: Int): CheckIntent {
        return CheckIntent(
            actionId = "coc_check",
            meta = mapOf(
                "expression" to "1d100",
                "target_value" to target.toString()
            ),
            diceSubmission = DiceSubmission.manual("1d100", roll)
        )
    }

    private fun dndCharacter(hp: Int): Map<String, Any> {
        return mapOf(
            "hp" to hp.toString(),
            "deathSaves" to DEFAULT_DEATH_SAVES
        )
    }

    private companion object {
        const val DEFAULT_DEATH_SAVES =
            "{\"successes\":0,\"failures\":0,\"isStable\":false}"
    }
}
