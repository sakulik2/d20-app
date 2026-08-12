package xyz.sakulik.d20.app.ui.creation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.sakulik.d20.app.ui.base.CollectEvent
import xyz.sakulik.d20.app.ui.creation.AllocationMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreationScreen(
    viewModel: CreationViewModel,
    onSuccess: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var name by remember { mutableStateOf("") }
    var aiDescription by remember { mutableStateOf("") }
    var showAddItemDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    DisposableEffect(viewModel) {
        onDispose { viewModel.cancelAiGeneration() }
    }
    
    // 同步 AI 生成的姓名到动态字段
    LaunchedEffect(uiState.characterName) {
        if (uiState.characterName.isNotEmpty()) {
            name = uiState.characterName
            viewModel.updateField("name", uiState.characterName)
        }
    }
    
    val haptic = LocalHapticFeedback.current

    CollectEvent(viewModel.uiEvent) { event ->
        when (event) {
            is CreationUiEvent.Success -> onSuccess()
            is CreationUiEvent.Error -> {
                scope.launch {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("创建你的角色", fontWeight = FontWeight.Bold) }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 8.dp) {
                Button(
                    onClick = { viewModel.saveCharacter(name) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    enabled = !uiState.isSaving && name.isNotBlank() && uiState.validationErrors.isEmpty(),
                    shape = MaterialTheme.shapes.medium
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Text("完成创卡", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    "分配模式",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        AllocationMode.POINT_BUY to "购点法",
                        AllocationMode.ROLLING to "掷骰法"
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = uiState.allocationMode == mode,
                            onClick = { viewModel.setAllocationMode(mode) },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            item {
                Text(
                    "AI 辅助创卡",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = aiDescription,
                    onValueChange = { aiDescription = it },
                    label = { Text("描述你的角色 (如：一个忧郁的半精灵吟游诗人)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (uiState.isAiGenerating) {
                            viewModel.cancelAiGeneration()
                        } else {
                            viewModel.generateWithAi(aiDescription)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.isAiGenerating || aiDescription.isNotBlank(),
                    shape = MaterialTheme.shapes.small
                ) {
                    if (uiState.isAiGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("取消生成")
                    } else {
                        Text("AI 自动生成全部信息")
                    }
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            // 这里的硬编码姓名框已删除，改由 DynamicFieldRenderer 渲染 ruleset 中的 name 字段

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("角色信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (uiState.allocationMode == AllocationMode.ROLLING) {
                        TextButton(onClick = { 
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.rollStats() 
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("整体重掷")
                        }
                    }
                }
                if (uiState.allocationMode == AllocationMode.POINT_BUY && uiState.totalPoints > 0) {
                    PointsHeader(
                        remaining = uiState.remainingPoints,
                        total = uiState.totalPoints
                    )
                }
                
                // --- 属性数值审查报告 ---
                if (uiState.validationErrors.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Warning, 
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "数值审查未通过",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            uiState.validationErrors.forEach { err ->
                                Text(
                                    "• $err",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(start = 24.dp, top = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            // --- 动态表单渲染核心 ---
            if (uiState.visibleFields.isNotEmpty()) {
                items(uiState.visibleFields) { field ->
                    DynamicFieldRenderer(
                        field = field,
                        value = uiState.stats[field.id] ?: when(field) {
                            is xyz.sakulik.d20.app.domain.rules.dynamic.DropdownField -> field.options.firstOrNull() ?: ""
                            is xyz.sakulik.d20.app.domain.rules.dynamic.PointBuyField -> field.min
                            else -> ""
                        },
                        onValueChange = { viewModel.updateField(field.id, it) },
                        allocationMode = uiState.allocationMode,
                        rulesetId = uiState.rulesetId,
                        error = uiState.validationErrors.find { it.contains(field.label) }
                    )
                }
            } else {
                // Fallback: 如果没有 Schema，显示旧的属性列表（如果有的话）
                items(uiState.stats.keys.toList()) { statName ->
                    StatRow(
                        name = statName,
                        value = (uiState.stats[statName] as? Number)?.toInt() ?: 0,
                        onValueChange = { viewModel.setStatValue(statName, it) },
                        onIncrement = {
                            viewModel.updateStat(statName, if (uiState.rulesetId == "coc_7e") 5 else 1)
                        },
                        onDecrement = {
                            viewModel.updateStat(statName, if (uiState.rulesetId == "coc_7e") -5 else -1)
                        }
                    )
                }
            }

            if (uiState.bio.isNotEmpty() && uiState.schema == null) {
                item {
                    Text("AI 生成背景", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = uiState.bio,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("初始装备", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { showAddItemDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("新增装备")
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (uiState.generatedItems.isNotEmpty()) {
                items(uiState.generatedItems) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.name, fontWeight = FontWeight.Bold)
                                Text(item.description, style = MaterialTheme.typography.labelSmall)
                            }
                            AssistChip(onClick = {}, label = { Text(item.category) })
                            Spacer(Modifier.width(4.dp))
                            IconButton(onClick = { viewModel.removeCustomItem(item) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除装备",
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 新增初始装备对话框
    if (showAddItemDialog) {
        var newItemName by remember { mutableStateOf("") }
        var newItemCategory by remember { mutableStateOf("武器") }
        var newItemDesc by remember { mutableStateOf("") }
        var newItemRules by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddItemDialog = false },
            title = { Text("新增初始装备") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newItemName,
                        onValueChange = { newItemName = it },
                        label = { Text("装备名称") },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("例如：精钢长剑 / 治疗药水") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newItemCategory,
                        onValueChange = { newItemCategory = it },
                        label = { Text("装备分类") },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("例如：武器 / 防具 / 道具") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newItemDesc,
                        onValueChange = { newItemDesc = it },
                        label = { Text("效果 / 描述说明") },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("例如：1d8 砍劈伤害，近战武器") }
                    )
                    OutlinedTextField(
                        value = newItemRules,
                        onValueChange = { newItemRules = it },
                        label = { Text("本地规则参数（每行 key=value）") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        placeholder = {
                            Text(
                                "武器示例：\nattack_ability=STR\nproficient=true\n" +
                                    "damage_formula=1d8\ndamage_type=slashing\ntargeting=SINGLE\n\n" +
                                    "法术示例：\nresolution_type=ATTACK\nslot_level=1\n" +
                                    "ability=int\ndamage_formula=1d10\ndamage_type=fire\n" +
                                    "targeting=MULTIPLE\nmax_targets=3"
                            )
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newItemName.isNotBlank()) {
                            if (viewModel.addCustomItem(
                                    newItemName,
                                    newItemCategory,
                                    newItemDesc,
                                    newItemRules
                                )
                            ) {
                                showAddItemDialog = false
                            }
                        }
                    }
                ) {
                    Text("添加")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddItemDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun PointsHeader(remaining: Int, total: Int) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (remaining < 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "剩余可用点数",
                style = MaterialTheme.typography.labelMedium,
                color = if (remaining < 0) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                "$remaining / $total",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color = if (remaining < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicFieldRenderer(
    field: xyz.sakulik.d20.app.domain.rules.dynamic.CreationField,
    value: Any,
    onValueChange: (Any) -> Unit,
    allocationMode: AllocationMode,
    rulesetId: String = "dnd_5e",
    error: String? = null
) {
    val rid = rulesetId.lowercase()
    when (field) {
        is xyz.sakulik.d20.app.domain.rules.dynamic.StringInputField -> {
            OutlinedTextField(
                value = value.toString(),
                onValueChange = onValueChange,
                label = { Text(field.label) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = field.hint?.let { { Text(it) } }
            )
        }
        is xyz.sakulik.d20.app.domain.rules.dynamic.PointBuyField -> {
            val (effectiveMin, effectiveMax) = when (allocationMode) {
                AllocationMode.POINT_BUY -> {
                    val maxLimit = if (rid.contains("dnd_5e")) 15 else field.max
                    field.min to maxLimit
                }
                AllocationMode.ROLLING, AllocationMode.AI_GEN -> {
                    when {
                        rid.contains("dnd_5e") -> 3 to 18
                        rid.contains("coc_7e") -> {
                            if (field.id in listOf("siz", "int", "edu")) 40 to 90
                            else 15 to 90
                        }
                        else -> field.min to field.max
                    }
                }
            }

            val detailText = when (allocationMode) {
                AllocationMode.POINT_BUY -> "购点法 (范围: $effectiveMin ~ $effectiveMax)"
                AllocationMode.ROLLING, AllocationMode.AI_GEN -> {
                    when {
                        rid.contains("dnd_5e") -> "掷骰 4d6kh3 (范围: 3 ~ 18)"
                        rid.contains("coc_7e") -> {
                            if (field.id in listOf("siz", "int", "edu")) "掷骰 (2d6+6)×5 (范围: 40 ~ 90)"
                            else "掷骰 3d6×5 (范围: 15 ~ 90)"
                        }
                        else -> "掷骰模式 (范围: $effectiveMin ~ $effectiveMax)"
                    }
                }
            }

            StatRow(
                name = field.label,
                value = (value as? Number)?.toInt() ?: field.min,
                onValueChange = { onValueChange(it) },
                onIncrement = { 
                    val current = (value as? Number)?.toInt() ?: field.min
                    if (current < effectiveMax) onValueChange(current + 1)
                },
                onDecrement = { 
                    val current = (value as? Number)?.toInt() ?: field.min
                    if (current > effectiveMin) onValueChange(current - 1)
                },
                isReadOnly = false,
                detail = detailText,
                errorMessage = error
            )
        }
        is xyz.sakulik.d20.app.domain.rules.dynamic.DiceRollField -> {
            val stepValue = if (rid.contains("coc_7e")) 5 else 1
            StatRow(
                name = field.label,
                value = (value as? Number)?.toInt() ?: 0,
                onValueChange = { onValueChange(it) },
                onIncrement = { 
                    val current = (value as? Number)?.toInt() ?: 0
                    onValueChange(current + stepValue)
                },
                onDecrement = { 
                    val current = (value as? Number)?.toInt() ?: 0
                    onValueChange((current - stepValue).coerceAtLeast(0))
                },
                isReadOnly = false,
                detail = "配方: ${field.formula}",
                errorMessage = error
            )
        }
        is xyz.sakulik.d20.app.domain.rules.dynamic.DropdownField -> {
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = value.toString(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(field.label) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    field.options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                onValueChange(option)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatRow(
    name: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    isReadOnly: Boolean = false,
    detail: String? = null,
    errorMessage: String? = null
) {
    val haptic = LocalHapticFeedback.current
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.labelSmall, color = if (errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                } else {
                    Text("当前值: $value", style = MaterialTheme.typography.labelSmall)
                }
                if (errorMessage != null) {
                    Text(errorMessage, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, fontSize = androidx.compose.ui.unit.TextUnit.Unspecified)
                }
            }

            if (!isReadOnly) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDecrement()
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                }

                // 使用可编辑的数值区域
                var textValue by remember(value) { mutableStateOf(value.toString()) }
                
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { input ->
                        val filtered = input.filter { it.isDigit() }
                        textValue = filtered
                        filtered.toIntOrNull()?.let { parsed ->
                            onValueChange(parsed)
                        }
                    },
                    modifier = Modifier.width(64.dp),
                    textStyle = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    ),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Transparent
                    )
                )

                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onIncrement()
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                }
            } else {
                Text(
                    text = value.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}
