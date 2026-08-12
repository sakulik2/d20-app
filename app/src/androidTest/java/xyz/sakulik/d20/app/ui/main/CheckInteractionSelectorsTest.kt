package xyz.sakulik.d20.app.ui.main

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.sakulik.d20.app.domain.combat.Combatant
import xyz.sakulik.d20.app.domain.rules.action.AttackAbility
import xyz.sakulik.d20.app.domain.rules.action.SpellProfile
import xyz.sakulik.d20.app.domain.rules.action.SpellResolutionType
import xyz.sakulik.d20.app.domain.rules.action.WeaponProfile
import xyz.sakulik.d20.app.ui.testing.ComposeTestActivity

class CheckInteractionSelectorsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComposeTestActivity>()

    @Test
    fun combatTargetSelectorChangesStableTargetId() {
        var selectedTargetId by mutableStateOf("goblin_1")
        val targets = listOf(
            Combatant("goblin_1", "地精", 12, 15, 7, 7),
            Combatant("orc_1", "兽人", 10, 13, 15, 15)
        )
        composeRule.setContent {
            MaterialTheme {
                CombatTargetSelector(
                    targets = targets,
                    selectedTargetId = selectedTargetId,
                    onTargetSelected = { selectedTargetId = it }
                )
            }
        }

        composeRule.onNodeWithTag("combat-target-orc_1")
            .performSemanticsAction(SemanticsActions.OnClick) { click -> click() }

        composeRule.runOnIdle { assertEquals("orc_1", selectedTargetId) }
        composeRule.onNodeWithTag("combat-target-orc_1").assertIsSelected()
    }

    @Test
    fun multiTargetSelectorKeepsMultipleTargetsAndHonorsLimit() {
        var selectedTargetIds by mutableStateOf(setOf("goblin_1"))
        val targets = listOf(
            Combatant("goblin_1", "地精一", 12, 15, 7, 7),
            Combatant("goblin_2", "地精二", 11, 15, 7, 7),
            Combatant("orc_1", "兽人", 10, 13, 15, 15)
        )
        composeRule.setContent {
            MaterialTheme {
                MultiCombatTargetSelector(
                    targets = targets,
                    selectedTargetIds = selectedTargetIds,
                    maxTargets = 2,
                    onTargetSelectionChanged = { selectedTargetIds = it }
                )
            }
        }

        composeRule.onNodeWithTag("combat-target-goblin_2")
            .performSemanticsAction(SemanticsActions.OnClick) { click -> click() }
        composeRule.onNodeWithTag("combat-target-orc_1")
            .performSemanticsAction(SemanticsActions.OnClick) { click -> click() }

        composeRule.runOnIdle {
            assertEquals(setOf("goblin_1", "goblin_2"), selectedTargetIds)
        }
        composeRule.onNodeWithTag("combat-target-goblin_1").assertIsSelected()
        composeRule.onNodeWithTag("combat-target-goblin_2").assertIsSelected()
    }

    @Test
    fun spellSlotSelectorChangesSelectedLevel() {
        var selectedLevel by mutableIntStateOf(1)
        composeRule.setContent {
            MaterialTheme {
                SpellSlotSelector(
                    levels = listOf(1, 2, 3),
                    selectedLevel = selectedLevel,
                    onLevelSelected = { selectedLevel = it }
                )
            }
        }

        composeRule.onNodeWithTag("spell-slot-3")
            .performSemanticsAction(SemanticsActions.OnClick) { click -> click() }

        composeRule.runOnIdle { assertEquals(3, selectedLevel) }
        composeRule.onNodeWithTag("spell-slot-3").assertIsSelected()
    }

    @Test
    fun weaponSelectorChangesStableWeaponId() {
        var selectedId by mutableStateOf("sword")
        val weapons = listOf("sword", "bow").map { id ->
            WeaponProfile(
                itemId = id,
                name = id,
                attackAbility = AttackAbility.STR,
                proficient = true,
                attackBonus = 0,
                damageFormula = "1d8",
                damageAbility = AttackAbility.STR,
                damageBonus = 0,
                damageType = "slashing"
            )
        }
        composeRule.setContent {
            MaterialTheme {
                WeaponSelector(weapons, selectedId) { selectedId = it }
            }
        }

        composeRule.onNodeWithTag("weapon-bow")
            .performSemanticsAction(SemanticsActions.OnClick) { click -> click() }

        composeRule.runOnIdle { assertEquals("bow", selectedId) }
        composeRule.onNodeWithTag("weapon-bow").assertIsSelected()
    }

    @Test
    fun spellSelectorIncludesCantripAndChangesStableSpellId() {
        var selectedId by mutableStateOf("fire_bolt")
        val spells = listOf(
            SpellProfile(
                spellId = "fire_bolt",
                name = "火焰箭",
                resolutionType = SpellResolutionType.ATTACK,
                slotLevel = 0,
                isRitual = false,
                abilityId = "int",
                attackBonus = 0,
                targetId = null,
                saveAbilityId = null,
                saveDc = null,
                damageFormula = "1d10",
                damageType = "fire",
                halfDamageOnSave = false,
                healingFormula = null
            ),
            SpellProfile(
                spellId = "cure_wounds",
                name = "疗伤术",
                resolutionType = SpellResolutionType.HEALING,
                slotLevel = 1,
                isRitual = false,
                abilityId = "wis",
                attackBonus = 0,
                targetId = null,
                saveAbilityId = null,
                saveDc = null,
                damageFormula = null,
                damageType = null,
                halfDamageOnSave = false,
                healingFormula = "1d8+3"
            )
        )
        composeRule.setContent {
            MaterialTheme {
                SpellSelector(spells, selectedId) { selectedId = it }
            }
        }

        composeRule.onNodeWithTag("spell-cure_wounds")
            .performSemanticsAction(SemanticsActions.OnClick) { click -> click() }

        composeRule.runOnIdle { assertEquals("cure_wounds", selectedId) }
        composeRule.onNodeWithTag("spell-cure_wounds").assertIsSelected()
    }
}
