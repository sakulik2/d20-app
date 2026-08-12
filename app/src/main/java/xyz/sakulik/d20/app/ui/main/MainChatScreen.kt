package xyz.sakulik.d20.app.ui.main

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import xyz.sakulik.d20.app.domain.rules.dynamic.CheckIntent
import xyz.sakulik.d20.app.ui.base.CollectEvent
import xyz.sakulik.d20.app.ui.theme.TRPGTheme
import xyz.sakulik.d20.app.ui.theme.TRPGThemeStyle

/**
 * 主交互界面 (MainChatScreen)
 * 实现 SSOT 聚合状态、解耦组件与沉浸式主题适配
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainChatScreen(
    viewModel: MainViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    var currentDiceIntent by remember { mutableStateOf<CheckIntent?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    CollectEvent(viewModel.uiEvent) { event ->
        when (event) {
            is MainUiEvent.ShowDicePanel -> currentDiceIntent = event.intent
            is MainUiEvent.Error -> {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = event.message,
                        duration = SnackbarDuration.Short
                    )
                }
            }
        }
    }

    // 主题自动适配当前的 Ruleset
    TRPGTheme(
        rulesetId = uiState.activeRulesetId,
        style = uiState.themeStyle
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = TRPGTheme.colors.narrativeSurface
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    topBar = {
                        uiState.character?.let { char ->
                            CharacterStatusHeader(
                                name = char.name,
                                stats = char.stats,
                                onOpenInventory = { viewModel.toggleInventory(true) },
                                onChangeTheme = { viewModel.cycleThemeStyle() }
                            )
                        }
                    },
                    // 彻底移除 bottomBar 槽位以防双倍 Padding
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { padding ->
                    // 核心组件：叙事列表与悬浮输入框
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .consumeWindowInsets(padding)
                            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    ) {
                        uiState.combatState?.let { combat ->
                            CombatHeader(
                                state = combat,
                                resourceLabels = uiState.turnResourceLabels,
                                onNextTurn = { viewModel.nextCombatTurn() }
                            )
                        }
                        if (uiState.isStable || uiState.isDead) {
                            Surface(
                                color = if (uiState.isDead) {
                                    MaterialTheme.colorScheme.errorContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (uiState.isDead) {
                                        "角色已死亡；普通治疗无法恢复生命。"
                                    } else {
                                        "角色伤势稳定但仍为 0 HP；受到伤害会重新进入濒死。"
                                    },
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                        
                        val sensoryController = xyz.sakulik.d20.app.ui.common.SensoryController.getInstance(androidx.compose.ui.platform.LocalContext.current)
                        
                        NarrativeMessageList(
                            messages = uiState.messages,
                            streamingNarrative = uiState.streamingNarrative,
                            onDeleteMessage = { viewModel.deleteMessage(it) },
                            sensoryController = sensoryController,
                            modifier = Modifier.weight(1f)
                        )
                        
                        InputSection(
                            isLoading = uiState.isLoading,
                            canSubmitAction = true,
                            onSend = { viewModel.sendAction(it) }
                        )
                    }
                }

                // 交互层覆盖物：骰子面板 (带动画显隐)
                val activeIntent = uiState.currentDiceIntent ?: currentDiceIntent
                AnimatedVisibility(
                    visible = uiState.isDicePanelVisible && activeIntent != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    activeIntent?.let { intent ->
                        CheckInteractionSheet(
                            intent = intent,
                            combatTargets = uiState.combatState?.availableTargets.orEmpty(),
                            weapons = uiState.availableWeapons,
                            spells = viewModel.castableSpells(),
                            onResult = { submission, resolvedIntent ->
                                currentDiceIntent = null
                                viewModel.onDiceResult(submission, resolvedIntent)
                            },
                            onDismiss = {
                                currentDiceIntent = null
                                viewModel.dismissDicePanel()
                            }
                        )
                    }
                }

                // 交互层覆盖物：死亡豁免追踪 (Death Save Tracker)
                if (uiState.isDying && !uiState.isDeathSaveRollInProgress) {
                    AlertDialog(
                        onDismissRequest = { /* 濒死状态不可随意关闭 */ },
                        containerColor = TRPGTheme.colors.panelBackground,
                        title = { Text("濒死状态", color = TRPGTheme.colors.primaryAccent) },
                        text = {
                            Column {
                                DeathSaveTracker(
                                    successes = uiState.deathSaveSuccesses,
                                    failures = uiState.deathSaveFailures
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    "角色 HP 已归零。点击按钮进行一次可见的 d20 死亡豁免，结果将由规则引擎自动记录。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TRPGTheme.colors.onNarrativeSurface
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = viewModel::requestDeathSaveRoll) {
                                Text("掷 d20")
                            }
                        }
                    )
                }


                // 交互层覆盖物：背包 (Inventory Sheet)
                if (uiState.isInventoryVisible) {
                    InventorySheet(
                        items = uiState.inventory,
                        onToggleEquip = { viewModel.toggleEquip(it) },
                        onUpdateRuleParameters = { item, modifiers ->
                            viewModel.updateItemRuleParameters(item, modifiers)
                        },
                        onDismiss = { viewModel.toggleInventory(false) }
                    )
                }
            }
        }
    }
}

/**
 * 底部输入组件
 */
@Composable
fun InputSection(
    isLoading: Boolean,
    canSubmitAction: Boolean = true,
    onSend: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    val isEnabled = !isLoading && canSubmitAction && text.isNotBlank()

    val containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
    val activeBtnColor = MaterialTheme.colorScheme.primary
    val activeIconColor = MaterialTheme.colorScheme.onPrimary
    val disabledBtnColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val disabledIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)

    val buttonScale by animateFloatAsState(
        targetValue = if (isEnabled) 1.0f else 0.92f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "btnScale"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        ),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (canSubmitAction) "输入你的行动..." else "等待当前行动者结束回合...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                },
                maxLines = 4,
                enabled = !isLoading && canSubmitAction,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                )
            )

            Spacer(modifier = Modifier.width(8.dp))

            Surface(
                onClick = {
                    if (isEnabled) {
                        onSend(text)
                        text = ""
                    }
                },
                enabled = isEnabled,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                color = if (isEnabled) activeBtnColor else disabledBtnColor,
                shadowElevation = if (isEnabled) 3.dp else 0.dp,
                modifier = Modifier
                    .size(42.dp)
                    .scale(buttonScale)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = activeIconColor
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "发送",
                            tint = if (isEnabled) activeIconColor else disabledIconColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 状态信息栏组件
 */
@Composable
fun CharacterStatusHeader(
    name: String, 
    stats: Map<String, String>,
    onOpenInventory: () -> Unit,
    onChangeTheme: () -> Unit
) {
    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Surface(
            color = TRPGTheme.colors.panelBackground.copy(alpha = 0.85f),
            tonalElevation = 6.dp,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, TRPGTheme.colors.dividerColor.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = TRPGTheme.colors.primaryAccent,
                        letterSpacing = 1.sp
                    )
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onChangeTheme,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Palette,
                                contentDescription = "切换主题",
                                tint = TRPGTheme.colors.primaryAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        IconButton(
                            onClick = onOpenInventory,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.List,
                                contentDescription = "背包",
                                tint = TRPGTheme.colors.primaryAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val displayStats = stats.filter { it.key.lowercase() in listOf("hp", "san", "mp", "race", "subclass") }.toList()
                    displayStats.forEachIndexed { index, pair ->
                        val (id, value) = pair
                        Text(
                            text = "$id: $value",
                            style = MaterialTheme.typography.labelSmall,
                            color = TRPGTheme.colors.onNarrativeSurface.copy(alpha = 0.7f),
                            fontWeight = FontWeight.Bold
                        )
                        if (index < displayStats.size - 1) {
                            VerticalDivider(
                                modifier = Modifier.height(10.dp).padding(horizontal = 12.dp),
                                color = TRPGTheme.colors.dividerColor.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 战斗顶部信息栏
 */
@Composable
fun CombatHeader(
    state: xyz.sakulik.d20.app.domain.combat.CombatState,
    resourceLabels: Map<String, String> = emptyMap(),
    onNextTurn: () -> Unit
) {
    Surface(
        color = TRPGTheme.colors.primaryAccent.copy(alpha = 0.12f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, TRPGTheme.colors.primaryAccent.copy(alpha = 0.25f))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "战斗轮次: ${state.round}",
                    style = MaterialTheme.typography.labelLarge,
                    color = TRPGTheme.colors.primaryAccent,
                    fontWeight = FontWeight.Bold
                )
                
                Button(
                    onClick = onNextTurn,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TRPGTheme.colors.primaryAccent,
                        contentColor = Color.Black
                    )
                ) {
                    Text("下一回合", fontSize = 12.sp)
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))

            if (state.turnResources.isNotEmpty()) {
                Text(
                    text = state.turnResources.entries.joinToString(" · ") { (resource, amount) ->
                        "${resourceLabels[resource] ?: resource} $amount"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TRPGTheme.colors.onNarrativeSurface.copy(alpha = 0.75f)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (state.ongoingEffects.isNotEmpty()) {
                Text(
                    text = "持续效果: " + state.ongoingEffects.joinToString(" · ") { effect ->
                        "${effect.name}(${effect.remainingTicks})"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TRPGTheme.colors.onNarrativeSurface.copy(alpha = 0.75f)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.initiativeQueue.size) { index ->
                    val id = state.initiativeQueue[index]
                    val combatant = state.combatants.firstOrNull { it.id == id }
                    val isCurrent = index == state.currentTurnIndex
                    
                    Surface(
                        color = if (isCurrent) TRPGTheme.colors.primaryAccent else Color.Transparent,
                        shape = MaterialTheme.shapes.small,
                        border = if (isCurrent) null else androidx.compose.foundation.BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = combatant?.let { "${it.name} ${it.hp}/${it.maxHp}" } ?: id,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isCurrent) Color.Black else TRPGTheme.colors.onNarrativeSurface,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}
