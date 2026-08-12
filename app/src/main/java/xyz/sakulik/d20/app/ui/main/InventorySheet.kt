package xyz.sakulik.d20.app.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.sakulik.d20.app.data.local.ItemEntity
import xyz.sakulik.d20.app.ui.theme.TRPGTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventorySheet(
    items: List<ItemEntity>,
    onToggleEquip: (ItemEntity) -> Unit,
    onUpdateRuleParameters: (ItemEntity, Map<String, String>) -> Unit,
    onDismiss: () -> Unit
) {
    var editingItem by remember { mutableStateOf<ItemEntity?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = TRPGTheme.colors.panelBackground,
        dragHandle = { BottomSheetDefaults.DragHandle(color = TRPGTheme.colors.primaryAccent) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "角色背包",
                style = MaterialTheme.typography.titleLarge,
                color = TRPGTheme.colors.primaryAccent,
                modifier = Modifier.padding(16.dp)
            )
            
            if (items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Text(
                        "背包空空如也",
                        color = TRPGTheme.colors.onNarrativeSurface.copy(alpha = 0.5f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(items) { item ->
                        InventoryItemCard(
                            item = item,
                            onToggleEquip = { onToggleEquip(item) },
                            onEditRules = { editingItem = item }
                        )
                    }
                }
            }
        }
    }
    editingItem?.let { item ->
        RuleParameterEditor(
            item = item,
            onSave = { modifiers ->
                onUpdateRuleParameters(item, modifiers)
                editingItem = null
            },
            onDismiss = { editingItem = null }
        )
    }
}

@Composable
fun InventoryItemCard(
    item: ItemEntity,
    onToggleEquip: () -> Unit,
    onEditRules: () -> Unit
) {
    Surface(
        color = TRPGTheme.colors.panelBackground.copy(alpha = 0.5f),
        border = androidx.compose.foundation.BorderStroke(1.dp, TRPGTheme.colors.primaryAccent.copy(alpha = 0.3f)),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = TRPGTheme.colors.primaryAccent
                )
                if (item.description.isNotBlank()) {
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = TRPGTheme.colors.onNarrativeSurface.copy(alpha = 0.7f)
                    )
                }
                if (item.modifiers.isNotEmpty()) {
                    Text(
                        text = "规则: " + item.modifiers.entries
                            .joinToString(", ") { "${it.key}=${it.value}" },
                        style = MaterialTheme.typography.labelSmall,
                        color = TRPGTheme.colors.onNarrativeSurface.copy(alpha = 0.5f)
                    )
                }
                if (item.category.contains("武器", ignoreCase = true) ||
                    item.category.contains("weapon", ignoreCase = true) ||
                    item.category.contains("法术", ignoreCase = true) ||
                    item.category.contains("spell", ignoreCase = true)
                ) {
                    TextButton(onClick = onEditRules, contentPadding = PaddingValues(0.dp)) {
                        Text("编辑本地规则")
                    }
                }
            }
            
            Switch(
                checked = item.isEquipped,
                onCheckedChange = { onToggleEquip() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = TRPGTheme.colors.primaryAccent,
                    checkedTrackColor = TRPGTheme.colors.primaryAccent.copy(alpha = 0.5f)
                )
            )
        }
    }
}

@Composable
private fun RuleParameterEditor(
    item: ItemEntity,
    onSave: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(item.id, item.modifiers) {
        mutableStateOf(
            item.modifiers.entries.sortedBy { it.key }
                .joinToString("\n") { "${it.key}=${it.value}" }
        )
    }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${item.name} · 本地规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "每行填写 key=value。武器至少需要 damage_formula、damage_type；" +
                        "法术需要 resolution_type、slot_level 及对应效果字段；" +
                        "多目标可设置 targeting=MULTIPLE/ALL_ENEMIES 与 max_targets。",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    label = { Text("规则参数") },
                    minLines = 6,
                    isError = error != null,
                    supportingText = { error?.let { message -> Text(message) } },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = parseRuleParameters(text)
                if (parsed == null) {
                    error = "格式错误；请确保每个非空行都是 key=value"
                } else {
                    onSave(parsed)
                }
            }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun parseRuleParameters(text: String): Map<String, String>? {
    val result = linkedMapOf<String, String>()
    text.lineSequence().map(String::trim).filter(String::isNotBlank).forEach { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) return null
        val key = line.take(separator).trim()
        val value = line.drop(separator + 1).trim()
        if (key.isBlank() || value.isBlank()) return null
        result[key] = value
    }
    return result
}
