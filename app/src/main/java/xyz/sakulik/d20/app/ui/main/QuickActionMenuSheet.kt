package xyz.sakulik.d20.app.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backpack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.sakulik.d20.app.data.local.ItemEntity
import xyz.sakulik.d20.app.domain.rules.dynamic.QuickActionDefinition

private enum class ReferenceFilter(val label: String) {
    ALL("全部"),
    EQUIPMENT("装备"),
    SPELL("法术")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickActionMenuSheet(
    inventory: List<ItemEntity>,
    quickActions: List<QuickActionDefinition>,
    actionsEnabled: Boolean,
    onQuickAction: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ReferenceFilter.ALL) }
    val visibleItems = remember(inventory, query, filter) {
        inventory.filter { item ->
            val isSpell = item.isSpellReference()
            val matchesFilter = when (filter) {
                ReferenceFilter.ALL -> true
                ReferenceFilter.EQUIPMENT -> !isSpell
                ReferenceFilter.SPELL -> isSpell
            }
            val searchable = buildString {
                append(item.name)
                append(' ')
                append(item.category)
                append(' ')
                append(item.description)
                append(' ')
                append(item.modifiers.entries.joinToString(" ") { "${it.key} ${it.value}" })
            }
            matchesFilter && (query.isBlank() || searchable.contains(query.trim(), ignoreCase = true))
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                    Text(
                        "快捷行动",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (actionsEnabled) {
                            "动作由当前规则包提供；本地规则动作不会请求模型。"
                        } else {
                            "当前只能查询资料，请先完成正在进行的响应或结算。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    if (quickActions.isEmpty()) {
                        Text(
                            "当前场景没有可用快捷行动",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        quickActions.forEach { action ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                shape = MaterialTheme.shapes.medium,
                                tonalElevation = 2.dp
                            ) {
                                TextButton(
                                    onClick = { onQuickAction(action.id) },
                                    enabled = actionsEnabled,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Bolt, contentDescription = null)
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            action.label,
                                            modifier = Modifier.fillMaxWidth(),
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        if (action.description.isNotBlank()) {
                                            Text(
                                                action.description,
                                                modifier = Modifier.fillMaxWidth(),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                    Text(
                        "装备与法术查询",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        label = { Text("搜索名称、分类、描述或规则字段") },
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ReferenceFilter.entries.forEach { option ->
                            FilterChip(
                                selected = filter == option,
                                onClick = { filter = option },
                                label = { Text(option.label) }
                            )
                        }
                    }
                }
            }

            if (visibleItems.isEmpty()) {
                item {
                    Text(
                        if (inventory.isEmpty()) "当前没有已记录的装备或法术" else "没有匹配的资料",
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(visibleItems, key = ItemEntity::id) { item ->
                    val isSpell = item.isSpellReference()
                    ListItem(
                        headlineContent = { Text(item.name, fontWeight = FontWeight.SemiBold) },
                        supportingContent = {
                            Column {
                                if (item.description.isNotBlank()) {
                                    Text(
                                        item.description,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                val ruleSummary = item.modifiers.entries
                                    .joinToString(" · ") { "${it.key}=${it.value}" }
                                if (ruleSummary.isNotBlank()) {
                                    Text(
                                        ruleSummary,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        },
                        leadingContent = {
                            Icon(
                                if (isSpell) Icons.Default.AutoAwesome else Icons.Default.Backpack,
                                contentDescription = null
                            )
                        },
                        trailingContent = {
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        buildString {
                                            append(item.category.ifBlank { "道具" })
                                            if (item.isEquipped) append(" · 已装备")
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

private fun ItemEntity.isSpellReference(): Boolean {
    return category.contains("法术", ignoreCase = true) ||
        category.contains("spell", ignoreCase = true)
}
