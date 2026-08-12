package xyz.sakulik.d20.app.ui.main

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.sakulik.d20.app.domain.rules.dynamic.CheckIntent
import xyz.sakulik.d20.app.domain.rules.dynamic.DiceSubmission
import xyz.sakulik.d20.app.domain.combat.Combatant
import xyz.sakulik.d20.app.domain.rules.action.SpellProfile
import xyz.sakulik.d20.app.domain.rules.action.SpellResolutionType
import xyz.sakulik.d20.app.domain.rules.action.TargetingMode
import xyz.sakulik.d20.app.domain.rules.action.WeaponProfile
import xyz.sakulik.d20.app.domain.rules.dynamic.DiceType
import xyz.sakulik.d20.app.engine.Die
import xyz.sakulik.d20.app.ui.common.PolyhedralDice3D
import xyz.sakulik.d20.app.ui.common.SensoryController

/**
 * 检定交互面板：由 ModalBottomSheet 包裹
 * 支持“物理感掷骰”和“手动输入”
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckInteractionSheet(
    intent: CheckIntent,
    combatTargets: List<Combatant> = emptyList(),
    weapons: List<WeaponProfile> = emptyList(),
    spells: List<SpellProfile> = emptyList(),
    onResult: (DiceSubmission, CheckIntent) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    val isBatchStep = intent.meta["batch_index"]?.toIntOrNull() != null
    var selectedTargetIds by remember(intent.actionId, intent.meta["target_id"], combatTargets) {
        mutableStateOf(
            if (isBatchStep) {
                setOfNotNull(intent.meta["target_id"])
            } else intent.meta["target_ids"]
                ?.split(TARGET_ID_SEPARATOR)
                ?.filter { requested -> combatTargets.any { it.id == requested } }
                ?.toSet()
                ?.takeIf { it.isNotEmpty() }
                ?: setOfNotNull(
                    intent.meta["target_id"]
                        ?.takeIf { requested -> combatTargets.any { it.id == requested } }
                        ?: combatTargets.firstOrNull()?.id
                )
        )
    }
    var selectedWeaponId by remember(intent.actionId, weapons) {
        mutableStateOf(
            intent.meta["weapon_id"]
                ?.takeIf { requested -> weapons.any { it.itemId == requested } }
                ?: weapons.firstOrNull()?.itemId.orEmpty()
        )
    }
    var selectedSpellId by remember(intent.actionId, spells) {
        mutableStateOf(
            intent.meta["spell_id"]
                ?.takeIf { requested -> spells.any { it.spellId == requested } }
                ?: spells.firstOrNull()?.spellId.orEmpty()
        )
    }
    val selectedSpell = spells.firstOrNull { it.spellId == selectedSpellId }
    val selectedWeapon = weapons.firstOrNull { it.itemId == selectedWeaponId }
    val isPrimarySpellSelection = intent.actionId == "dnd_cast"
    val targeting = when {
        intent.actionId == "dnd_attack" -> selectedWeapon?.targeting ?: TargetingMode.SINGLE
        isPrimarySpellSelection -> selectedSpell?.targeting ?: TargetingMode.SINGLE
        else -> TargetingMode.SINGLE
    }
    val maxTargets = when {
        intent.actionId == "dnd_attack" -> selectedWeapon?.maxTargets
        isPrimarySpellSelection -> selectedSpell?.maxTargets
        else -> null
    }
    val resolvedTargetIds = if (isBatchStep) {
        listOfNotNull(intent.meta["target_id"]?.takeIf(String::isNotBlank))
    } else {
        when (targeting) {
            TargetingMode.ALL_ENEMIES -> combatTargets.map(Combatant::id)
            TargetingMode.SELF -> emptyList()
            TargetingMode.SINGLE -> selectedTargetIds.take(1)
            TargetingMode.MULTIPLE -> selectedTargetIds.take(maxTargets ?: combatTargets.size)
        }
    }
    val requestedExpression = intent.meta["expression"] ?: "1d20"
    val expression = when {
        isPrimarySpellSelection && selectedSpell?.resolutionType == SpellResolutionType.AUTOMATIC ->
            selectedSpell.damageFormula.orEmpty()
        isPrimarySpellSelection && selectedSpell?.resolutionType == SpellResolutionType.HEALING ->
            selectedSpell.healingFormula.orEmpty()
        intent.actionId == "dnd_attack" || isPrimarySpellSelection ->
            requestedExpression.takeIf(::isSupportedD20Expression) ?: "1d20"
        else -> requestedExpression
    }
    val resolvedIntent = intent.copy(
        meta = intent.meta + mapOf(
            "target_id" to resolvedTargetIds.firstOrNull().orEmpty(),
            "target_ids" to if (isBatchStep) {
                intent.meta["target_ids"].orEmpty()
            } else {
                resolvedTargetIds.joinToString(TARGET_ID_SEPARATOR)
            },
            "slot_level" to selectedSpell?.slotLevel?.toString().orEmpty(),
            "weapon_id" to selectedWeaponId,
            "spell_id" to selectedSpellId,
            "expression" to expression,
            "resolution_stage" to when {
                isPrimarySpellSelection && selectedSpell?.resolutionType in setOf(
                    SpellResolutionType.AUTOMATIC,
                    SpellResolutionType.HEALING
                ) -> "EFFECT"
                else -> intent.meta["resolution_stage"] ?: "PRIMARY"
            }
        )
    )
    
    ModalBottomSheet(
        onDismissRequest = {
            if (intent.meta["resolution_stage"] != "EFFECT") onDismiss()
        },
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = intent.meta["reason"] ?: "检定",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            if (intent.meta["resolution_stage"] == "EFFECT") {
                Text(
                    "该动作已进入效果结算，请完成本次掷骰。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (isBatchStep) {
                val currentTarget = combatTargets.firstOrNull {
                    it.id == intent.meta["target_id"]
                }
                Text(
                    text = "批量裁决目标 ${intent.meta["batch_index"]?.toIntOrNull()?.plus(1)}：" +
                        (currentTarget?.name ?: intent.meta["target_id"].orEmpty()),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp)
                )
            } else if (intent.actionId == "dnd_attack") {
                WeaponSelector(
                    weapons = weapons,
                    selectedWeaponId = selectedWeaponId,
                    onWeaponSelected = { selectedWeaponId = it }
                )
                when (targeting) {
                    TargetingMode.SINGLE -> CombatTargetSelector(
                        targets = combatTargets,
                        selectedTargetId = selectedTargetIds.firstOrNull().orEmpty(),
                        onTargetSelected = { selectedTargetIds = setOf(it) }
                    )
                    TargetingMode.MULTIPLE -> MultiCombatTargetSelector(
                        targets = combatTargets,
                        selectedTargetIds = selectedTargetIds,
                        maxTargets = maxTargets,
                        onTargetSelectionChanged = { selectedTargetIds = it },
                        label = "选择攻击目标"
                    )
                    TargetingMode.ALL_ENEMIES -> Text(
                        "目标：全部 ${combatTargets.size} 名存活敌人",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    TargetingMode.SELF -> Unit
                }
            }

            if (!isBatchStep && intent.actionId == "dnd_cast") {
                SpellSelector(
                    spells = spells,
                    selectedSpellId = selectedSpellId,
                    onSpellSelected = { selectedSpellId = it }
                )
                if (selectedSpell?.resolutionType != SpellResolutionType.HEALING) {
                    val targetLabel = if (selectedSpell?.resolutionType == SpellResolutionType.SAVING_THROW) {
                        "选择进行 ${selectedSpell.saveAbilityId?.uppercase()} 豁免的目标"
                    } else {
                        "选择法术目标"
                    }
                    when (targeting) {
                        TargetingMode.SINGLE -> CombatTargetSelector(
                            targets = combatTargets,
                            selectedTargetId = selectedTargetIds.firstOrNull().orEmpty(),
                            onTargetSelected = { selectedTargetIds = setOf(it) },
                            label = targetLabel
                        )
                        TargetingMode.MULTIPLE -> MultiCombatTargetSelector(
                            targets = combatTargets,
                            selectedTargetIds = selectedTargetIds,
                            maxTargets = maxTargets,
                            onTargetSelectionChanged = { selectedTargetIds = it },
                            label = targetLabel
                        )
                        TargetingMode.ALL_ENEMIES -> Text(
                            "目标：全部 ${combatTargets.size} 名存活敌人",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                        TargetingMode.SELF -> Unit
                    }
                }
            }
            val rawDc = intent.meta["dc"] ?: "0"
            val rawTarget = intent.meta["target_value"] ?: "0"
            val targetLabel = intent.meta["target_label"]?.ifBlank { null } ?: "目标值"
            val threshold = rawTarget.takeUnless { it == "0" } ?: rawDc.takeUnless { it == "0" }
            val thresholdStr = threshold?.let { "$targetLabel $it" }.orEmpty()
            Text(
                text = "要求: $expression" + (if (thresholdStr.isNotEmpty()) " ($thresholdStr)" else ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            SecondaryTabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                    Text("虚拟掷骰", modifier = Modifier.padding(12.dp))
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                    Text("手动输入", modifier = Modifier.padding(12.dp))
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            if (selectedTab == 0) {
                VirtualDiceRoller(
                    expression = expression,
                    onFinished = { 
                        scope.launch {
                            sheetState.hide()
                            onResult(it, resolvedIntent)
                        }
                    }
                )
            } else {
                ManualInputPanel(
                    expression = expression,
                    onFinished = {
                        scope.launch {
                            sheetState.hide()
                            onResult(it, resolvedIntent)
                        }
                    }
                )
            }
        }
    }
}

private const val TARGET_ID_SEPARATOR = "|"

private fun isSupportedD20Expression(expression: String): Boolean {
    return expression.lowercase().filterNot(Char::isWhitespace) in setOf(
        "1d20",
        "2d20kh1",
        "2d20kl1"
    )
}

@Composable
fun CombatTargetSelector(
    targets: List<Combatant>,
    selectedTargetId: String,
    onTargetSelected: (String) -> Unit,
    label: String = "选择攻击目标"
) {
    Spacer(modifier = Modifier.height(16.dp))
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        targets.forEach { target ->
            FilterChip(
                modifier = Modifier.testTag("combat-target-${target.id}"),
                selected = selectedTargetId == target.id,
                onClick = { onTargetSelected(target.id) },
                label = { Text("${target.name} · AC ${target.ac} · HP ${target.hp}/${target.maxHp}") }
            )
        }
    }
}

@Composable
fun MultiCombatTargetSelector(
    targets: List<Combatant>,
    selectedTargetIds: Set<String>,
    maxTargets: Int?,
    onTargetSelectionChanged: (Set<String>) -> Unit,
    label: String = "选择目标"
) {
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = buildString {
            append(label)
            append("（已选 ${selectedTargetIds.size}")
            maxTargets?.let { append("/$it") }
            append("）")
        },
        style = MaterialTheme.typography.labelLarge
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        targets.forEach { target ->
            val selected = target.id in selectedTargetIds
            FilterChip(
                modifier = Modifier.testTag("combat-target-${target.id}"),
                selected = selected,
                onClick = {
                    val next = if (selected) {
                        selectedTargetIds - target.id
                    } else if (maxTargets == null || selectedTargetIds.size < maxTargets) {
                        selectedTargetIds + target.id
                    } else {
                        selectedTargetIds
                    }
                    if (next.isNotEmpty()) onTargetSelectionChanged(next)
                },
                label = { Text("${target.name} · HP ${target.hp}/${target.maxHp}") }
            )
        }
    }
}

@Composable
fun WeaponSelector(
    weapons: List<WeaponProfile>,
    selectedWeaponId: String,
    onWeaponSelected: (String) -> Unit
) {
    Spacer(modifier = Modifier.height(16.dp))
    Text("选择已装备武器", style = MaterialTheme.typography.labelLarge)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        weapons.forEach { weapon ->
            FilterChip(
                modifier = Modifier.testTag("weapon-${weapon.itemId}"),
                selected = selectedWeaponId == weapon.itemId,
                onClick = { onWeaponSelected(weapon.itemId) },
                label = { Text("${weapon.name} · ${weapon.damageFormula} ${weapon.damageType}") }
            )
        }
    }
}

@Composable
fun SpellSelector(
    spells: List<SpellProfile>,
    selectedSpellId: String,
    onSpellSelected: (String) -> Unit
) {
    Spacer(modifier = Modifier.height(16.dp))
    Text("选择已准备法术", style = MaterialTheme.typography.labelLarge)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        spells.forEach { spell ->
            FilterChip(
                modifier = Modifier.testTag("spell-${spell.spellId}"),
                selected = selectedSpellId == spell.spellId,
                onClick = { onSpellSelected(spell.spellId) },
                label = {
                    Text(
                        when {
                            spell.slotLevel == 0 -> "${spell.name} · 戏法"
                            spell.isRitual -> "${spell.name} · ${spell.slotLevel} 环仪式"
                            else -> "${spell.name} · ${spell.slotLevel} 环"
                        }
                    )
                }
            )
        }
    }
}

@Composable
fun SpellSlotSelector(
    levels: List<Int>,
    selectedLevel: Int,
    onLevelSelected: (Int) -> Unit
) {
    Spacer(modifier = Modifier.height(16.dp))
    Text("选择消耗的法术位", style = MaterialTheme.typography.labelLarge)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        levels.forEach { level ->
            FilterChip(
                modifier = Modifier.testTag("spell-slot-$level"),
                selected = selectedLevel == level,
                onClick = { onLevelSelected(level) },
                label = { Text("${level} 环") }
            )
        }
    }
}

@Composable
fun VirtualDiceRoller(
    expression: String,
    onFinished: (DiceSubmission) -> Unit
) {
    val context = LocalContext.current
    val sensory = remember { SensoryController.getInstance(context) }
    var rolling by remember { mutableStateOf(false) }
    var result by remember { mutableIntStateOf(0) }
    var individualResults by remember { mutableStateOf(emptyList<Die>()) }
    var validationMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val previewDice = remember(expression) { parseDicePreview(expression) }
    val displayedDice = individualResults.takeIf { it.isNotEmpty() } ?: previewDice
    val multiDiceSize = when (displayedDice.size) {
        2 -> 96.dp
        3 -> 86.dp
        4 -> 66.dp
        5 -> 50.dp
        else -> 42.dp
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp),
            contentAlignment = Alignment.Center
        ) {
            if (displayedDice.size <= 1) {
                val die = displayedDice.firstOrNull() ?: Die(0, 20)
                PolyhedralDice3D(
                    diceType = DiceType.fromSides(die.sides),
                    isRolling = rolling,
                    finalValue = die.value.takeIf { it > 0 },
                    size = 156.dp,
                    onLandImpact = { sensory.hapticLandImpact() }
                )
            } else {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    displayedDice.forEachIndexed { index, die ->
                        PolyhedralDice3D(
                            diceType = DiceType.fromSides(die.sides),
                            isRolling = rolling,
                            finalValue = die.value.takeIf { it > 0 },
                            size = multiDiceSize,
                            onLandImpact = { if (index == 0) sensory.hapticLandImpact() }
                        )
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = {
                if (rolling) return@Button
                scope.launch {
                    rolling = true
                    result = 0
                    individualResults = emptyList()
                    validationMessage = null
                    sensory.playSound("dice_clatter")
                    sensory.hapticDiceRolling()
                    
                    // 模拟物理下坠与翻滚时长
                    delay(1000)
                    
                    val rollResult = try {
                        xyz.sakulik.d20.app.engine.roll(expression)
                    } catch (e: Exception) {
                        null
                    }
                    if (rollResult == null) {
                        rolling = false
                        validationMessage = "无法解析骰子表达式：$expression"
                        return@launch
                    }
                    individualResults = rollResult.allRolls
                    val submission = DiceSubmission.fromRollResult(rollResult)
                    val submissionError = submission.validateAgainst(expression)
                    if (submissionError != null) {
                        rolling = false
                        validationMessage = submissionError.message
                        return@launch
                    }
                    val finalVal = submission.total
                    result = finalVal
                    rolling = false
                    
                    if (submission.keptTerms.isNotEmpty() &&
                        submission.keptTerms.all { it.value == it.sides }
                    ) {
                        sensory.hapticCriticalSuccess()
                    } else if (finalVal == 1) {
                        sensory.hapticCheckFailure()
                    }

                    delay(1800)
                    onFinished(submission)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !rolling
        ) {
            Text(
                if (rolling) "命运翻滚中..." else "掷出 $expression",
                style = MaterialTheme.typography.titleMedium
            )
        }
        validationMessage?.let { message ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(message, color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun parseDicePreview(expression: String): List<Die> {
    return Regex("(\\d*)d(\\d+)", RegexOption.IGNORE_CASE)
        .findAll(expression)
        .flatMap { match ->
            val count = match.groupValues[1].ifBlank { "1" }.toIntOrNull()
                ?.coerceIn(1, 20) ?: 1
            val sides = match.groupValues[2].toIntOrNull() ?: 20
            List(count) { Die(value = 0, sides = sides) }.asSequence()
        }
        .toList()
        .takeIf { it.isNotEmpty() }
        ?: listOf(Die(value = 0, sides = 20))
}

@Composable
fun ManualInputPanel(
    expression: String,
    onFinished: (DiceSubmission) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var validationMessage by remember { mutableStateOf<String?>(null) }
    val usesKeepRule = remember(expression) {
        expression.contains("kh", ignoreCase = true) ||
            expression.contains("kl", ignoreCase = true)
    }
    
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                if (input.isEmpty() || input == "-" || input.toIntOrNull() != null) {
                    text = input
                    validationMessage = null
                }
            },
            label = { Text("输入线下掷骰的最终结果") },
            supportingText = {
                Text(
                    validationMessage ?: if (usesKeepRule) {
                        "请在线下完成优势/劣势取高或取低后，填写最终保留结果"
                    } else {
                        "填写实体骰计算完成后的最终点数"
                    }
                )
            },
            isError = validationMessage != null,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = {
                val submission = DiceSubmission.manual(
                    expression = expression,
                    finalResult = text.toIntOrNull() ?: 0
                )
                val error = submission.validateAgainst(expression)
                if (error == null) {
                    onFinished(submission)
                } else {
                    validationMessage = error.message
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = text.isNotBlank()
        ) {
            Text("采用线下结果")
        }
    }
}
