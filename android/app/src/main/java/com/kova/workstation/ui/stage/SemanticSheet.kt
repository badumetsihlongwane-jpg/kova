package com.kova.workstation.ui.stage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kova.workstation.data.model.UiaElement
import com.kova.workstation.data.model.UiaInspectResponse
import com.kova.workstation.ui.theme.KovaPalette

private enum class SemanticFilter { ALL, ACTIONS, INPUTS, TEXT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SemanticSheet(
    uiaData: UiaInspectResponse?,
    loading: Boolean,
    onRefresh: () -> Unit,
    onInvoke: (UiaElement, String, String) -> Unit,
    onCopyText: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(SemanticFilter.ALL) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Semantic Controls (UI Automation)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = buildString {
                            append(uiaData?.windowTitle?.ifBlank { "Active Window" } ?: "Inspecting...")
                            if (uiaData != null) append(" · ${uiaData.elements.size} controls · ${uiaData.elapsedMs}ms")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onRefresh) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Re-scan UI", tint = KovaPalette.Cyan)
                    }
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search any button, menu, input, or text...") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SemanticFilter.entries.forEach { tab ->
                    FilterChip(
                        selected = filter == tab,
                        onClick = { filter = tab },
                        label = {
                            Text(
                                when (tab) {
                                    SemanticFilter.ALL -> "All"
                                    SemanticFilter.ACTIONS -> "Buttons"
                                    SemanticFilter.INPUTS -> "Inputs"
                                    SemanticFilter.TEXT -> "Read Text"
                                }
                            )
                        }
                    )
                }
            }

            if (filter == SemanticFilter.TEXT) {
                val snippets = uiaData?.readableText.orEmpty().filter {
                    query.isBlank() || it.contains(query, ignoreCase = true)
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (snippets.isEmpty()) {
                        item {
                            Text(
                                "No readable text nodes extracted for this window.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(snippets) { snippet ->
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    SelectionContainer(modifier = Modifier.weight(1f)) {
                                        Text(snippet, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    IconButton(onClick = { onCopyText(snippet) }, modifier = Modifier.size(30.dp)) {
                                        Icon(Icons.Rounded.ContentCopy, "Copy", Modifier.size(16.dp), tint = KovaPalette.Cyan)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                val filtered = uiaData?.elements.orEmpty().filter { el ->
                    val matchesFilter = when (filter) {
                        SemanticFilter.ALL -> true
                        SemanticFilter.ACTIONS -> !el.isEditable
                        SemanticFilter.INPUTS -> el.isEditable
                        SemanticFilter.TEXT -> false
                    }
                    val matchesQuery = query.isBlank() ||
                        el.name.contains(query, ignoreCase = true) ||
                        el.role.contains(query, ignoreCase = true) ||
                        el.value.contains(query, ignoreCase = true)
                    matchesFilter && matchesQuery
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                text = uiaData?.error?.takeIf { it.isNotBlank() }
                                    ?: "No matching controls found. Try tapping Refresh or switching to Visual Stage.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    } else {
                        items(filtered, key = { "${it.id}_${it.runtimeId}" }) { el ->
                            SemanticElementRow(element = el, onInvoke = onInvoke)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SemanticElementRow(
    element: UiaElement,
    onInvoke: (UiaElement, String, String) -> Unit
) {
    var draft by remember(element.runtimeId, element.value) { mutableStateOf(element.value) }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(
            1.dp,
            if (element.focused) KovaPalette.Cyan else MaterialTheme.colorScheme.outline
        )
    ) {
        if (element.isEditable) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Edit, null, tint = KovaPalette.Emerald, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = element.name.ifBlank { element.role },
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    RoleBadge(element.role)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("Type value to inject...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { onInvoke(element, "set_value", draft) },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(KovaPalette.Cyan)
                    ) {
                        Icon(Icons.Rounded.Send, "Set value", tint = KovaPalette.Obsidian, modifier = Modifier.size(18.dp))
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = element.enabled) { onInvoke(element, "invoke", "") }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = KovaPalette.Amber.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = "#${element.id}",
                        style = MaterialTheme.typography.labelSmall,
                        color = KovaPalette.Amber,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = element.name.ifBlank { "(${element.role})" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (element.value.isNotBlank() && element.value != element.name) {
                        Text(
                            text = element.value,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                RoleBadge(element.role)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Rounded.TouchApp, null, tint = KovaPalette.Cyan, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun RoleBadge(role: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = KovaPalette.Cyan.copy(alpha = 0.12f)
    ) {
        Text(
            text = role,
            style = MaterialTheme.typography.labelSmall,
            color = KovaPalette.Cyan,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}
