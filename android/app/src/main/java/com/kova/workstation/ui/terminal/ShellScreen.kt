package com.kova.workstation.ui.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kova.workstation.data.model.CommandSnippet
import com.kova.workstation.ui.WorkstationUiState
import com.kova.workstation.ui.theme.KovaPalette

private data class TermKey(val label: String, val seq: String, val accent: Color? = null)

private val ACCESSORY_KEYS = listOf(
    TermKey("ESC", "\u001B"),
    TermKey("TAB", "\t"),
    TermKey("Ctrl+C", "\u0003", KovaPalette.Rose),
    TermKey("Ctrl+D", "\u0004"),
    TermKey("Ctrl+L", "\u000C"),
    TermKey("↑", "\u001B[A"),
    TermKey("↓", "\u001B[B"),
    TermKey("←", "\u001B[D"),
    TermKey("→", "\u001B[C"),
    TermKey("HOME", "\u001B[H"),
    TermKey("END", "\u001B[F"),
    TermKey("|", "|"),
    TermKey("\\", "\\"),
    TermKey("/", "/"),
    TermKey("~", "~"),
    TermKey("-", "-")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellScreen(
    state: WorkstationUiState,
    onCreateTerminal: (String) -> Unit,
    onSelectTerminal: (String) -> Unit,
    onCloseTerminal: (String) -> Unit,
    onSendInput: (String) -> Unit,
    onResize: (Int, Int) -> Unit,
    onCopyAll: () -> Unit,
    onFontSizeDelta: (Float) -> Unit,
    onAddSnippet: (String, String) -> Unit,
    onDeleteSnippet: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val activeTab = state.terminals.firstOrNull { it.info.id == state.activeTerminalId }
        ?: state.terminals.firstOrNull()

    var showNewMenu by remember { mutableStateOf(false) }
    var showSnippets by remember { mutableStateOf(false) }
    var showAddSnippet by remember { mutableStateOf(false) }
    var ctrlSticky by remember { mutableStateOf(false) }
    var commandInput by remember { mutableStateOf("") }

    val listState = rememberLazyListState()
    val fontSizeSp = state.preferences.terminalFontSp.coerceIn(9f, 20f)

    // Auto-scroll smoothly when new lines stream in
    LaunchedEffect(activeTab?.lines?.size) {
        val count = activeTab?.lines?.size ?: 0
        if (count > 0) {
            listState.animateScrollToItem(count - 1)
        }
    }

    if (showSnippets) {
        ModalBottomSheet(
            onDismissRequest = { showSnippets = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Command Snippets", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { showAddSnippet = true }) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New")
                    }
                }
                state.snippets.forEach { sn ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSendInput(sn.command)
                                showSnippets = false
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(sn.label, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    sn.command.trim(),
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = KovaPalette.Cyan,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { onDeleteSnippet(sn.id) }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Rounded.DeleteOutline, "Delete", Modifier.size(16.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showAddSnippet) {
        var label by remember { mutableStateOf("") }
        var cmd by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddSnippet = false },
            title = { Text("Add Command Snippet") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Label") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = cmd,
                        onValueChange = { cmd = it },
                        label = { Text("Command") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (label.isNotBlank() && cmd.isNotBlank()) {
                            onAddSnippet(label, cmd)
                            showAddSnippet = false
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAddSnippet = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KovaPalette.Obsidian)
            .imePadding()
    ) {
        // 1. Multi-session Terminal Tab Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                state.terminals.forEach { tab ->
                    val selected = tab.info.id == activeTab?.info?.id
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, if (selected) KovaPalette.Cyan else MaterialTheme.colorScheme.outline),
                        modifier = Modifier.clickable { onSelectTerminal(tab.info.id) }
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (tab.info.alive) KovaPalette.Emerald else KovaPalette.Rose)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = tab.titleOverride.ifBlank { tab.info.title }.take(18),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) KovaPalette.Cyan else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                            IconButton(
                                onClick = { onCloseTerminal(tab.info.id) },
                                modifier = Modifier.size(22.dp)
                            ) {
                                Icon(Icons.Rounded.Close, "Close Tab", Modifier.size(13.dp))
                            }
                        }
                    }
                }

                Box {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = KovaPalette.Cyan.copy(alpha = 0.14f),
                        modifier = Modifier.clickable { showNewMenu = true }
                    ) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = "New Shell",
                            tint = KovaPalette.Cyan,
                            modifier = Modifier.padding(6.dp).size(18.dp)
                        )
                    }
                    DropdownMenu(expanded = showNewMenu, onDismissRequest = { showNewMenu = false }) {
                        DropdownMenuItem(text = { Text("PowerShell") }, onClick = { showNewMenu = false; onCreateTerminal("powershell") })
                        DropdownMenuItem(text = { Text("Command Prompt (CMD)") }, onClick = { showNewMenu = false; onCreateTerminal("cmd") })
                        DropdownMenuItem(text = { Text("Git Bash") }, onClick = { showNewMenu = false; onCreateTerminal("bash") })
                        DropdownMenuItem(text = { Text("WSL") }, onClick = { showNewMenu = false; onCreateTerminal("wsl") })
                    }
                }
            }

            IconButton(onClick = { onFontSizeDelta(-1f) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Rounded.TextDecrease, "Smaller", Modifier.size(17.dp))
            }
            IconButton(onClick = { onFontSizeDelta(1f) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Rounded.TextIncrease, "Larger", Modifier.size(17.dp))
            }
            IconButton(onClick = onCopyAll, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Rounded.ContentCopy, "Copy Output", Modifier.size(16.dp), tint = KovaPalette.Cyan)
            }
        }

        // 2. Native Crisp ANSI ConPTY Output Viewport (Auto-Calculates Exact Cols x Rows!)
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF060910))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            val charWidthDp = fontSizeSp * 0.62f
            val lineHeightDp = fontSizeSp * 1.42f
            val calcCols = (maxWidth.value / charWidthDp).toInt().coerceIn(32, 180)
            val calcRows = (maxHeight.value / lineHeightDp).toInt().coerceIn(10, 80)

            LaunchedEffect(activeTab?.info?.id, calcCols, calcRows) {
                if (activeTab != null) onResize(calcCols, calcRows)
            }

            if (activeTab == null) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("No active terminal", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { onCreateTerminal("powershell") }) {
                        Text("Launch PowerShell Session")
                    }
                }
            } else {
                SelectionContainer(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
                        itemsIndexed(activeTab.lines) { _, line ->
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSizeSp.sp,
                                lineHeight = (fontSizeSp * 1.38f).sp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        // 3. Developer Accessory Key Bar + Snippets Trigger
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Snippets button
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = KovaPalette.Amber.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, KovaPalette.Amber.copy(alpha = 0.5f)),
                modifier = Modifier.clickable { showSnippets = true }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Bolt, null, tint = KovaPalette.Amber, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Snippets", style = MaterialTheme.typography.labelSmall, color = KovaPalette.Amber)
                }
            }

            // Sticky CTRL toggle
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (ctrlSticky) KovaPalette.Cyan else MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, if (ctrlSticky) KovaPalette.Cyan else MaterialTheme.colorScheme.outline),
                modifier = Modifier.clickable { ctrlSticky = !ctrlSticky }
            ) {
                Text(
                    "CTRL",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (ctrlSticky) KovaPalette.Obsidian else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            ACCESSORY_KEYS.forEach { key ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = key.accent?.copy(alpha = 0.14f) ?: MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, key.accent?.copy(alpha = 0.45f) ?: MaterialTheme.colorScheme.outline),
                    modifier = Modifier.clickable {
                        if (state.preferences.hapticsEnabled) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        onSendInput(key.seq)
                    }
                ) {
                    Text(
                        key.label,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = key.accent ?: MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // 4. Native Mobile Command Composer Bar (Zero Autocorrect Friction + Instant Run!)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = commandInput,
                onValueChange = { next ->
                    if (ctrlSticky && next.length == 1) {
                        val ch = next[0].uppercaseChar()
                        if (ch in 'A'..'Z') {
                            val ctrlChar = (ch.code - 64).toChar().toString()
                            onSendInput(ctrlChar)
                            ctrlSticky = false
                            commandInput = ""
                            return@OutlinedTextField
                        }
                    }
                    commandInput = next
                },
                placeholder = {
                    Text(
                        if (ctrlSticky) "Type letter for Ctrl+<key>..." else "Type command & press Enter...",
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        onSendInput("$commandInput\r")
                        commandInput = ""
                    }
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = KovaPalette.Cyan,
                modifier = Modifier
                    .size(48.dp)
                    .clickable {
                        if (state.preferences.hapticsEnabled) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        onSendInput("$commandInput\r")
                        commandInput = ""
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.KeyboardReturn, "Run", tint = KovaPalette.Obsidian)
                }
            }
        }
    }
}
