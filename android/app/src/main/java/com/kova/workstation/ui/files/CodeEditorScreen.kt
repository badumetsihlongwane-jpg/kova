package com.kova.workstation.ui.files

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kova.workstation.ui.OpenEditorState
import com.kova.workstation.ui.theme.KovaPalette

private val CODE_SYMBOLS = listOf("    ", "{", "}", "(", ")", "[", "]", "=", ":", ";", "\"", "'", "->", "=>", "/", "\\", "_")

@Composable
fun CodeEditorScreen(
    editor: OpenEditorState,
    fontSizeSp: Float,
    onChangeFontSize: (Float) -> Unit,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onToggleTail: () -> Unit,
    onClose: () -> Unit
) {
    var editMode by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KovaPalette.Obsidian)
            .imePadding()
    ) {
        // Editor Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = (if (editor.dirty) "● " else "") + editor.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (editor.dirty) KovaPalette.Amber else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${editor.language} · ${editor.content.count { it == '\n' } + 1} lines",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = { onChangeFontSize(-1f) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.TextDecrease, "Smaller", Modifier.size(17.dp))
            }
            IconButton(onClick = { onChangeFontSize(1f) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.TextIncrease, "Larger", Modifier.size(17.dp))
            }
            IconButton(onClick = onToggleTail, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Rounded.LiveTv,
                    contentDescription = "Live Tail",
                    tint = if (editor.liveTail) KovaPalette.Emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (!editor.isBinary) {
                IconButton(onClick = { editMode = !editMode }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        if (editMode) Icons.Rounded.Visibility else Icons.Rounded.Edit,
                        contentDescription = "Toggle Edit Mode",
                        tint = if (editMode) KovaPalette.Cyan else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onSave,
                    enabled = editor.dirty && !editor.saving,
                    modifier = Modifier.size(36.dp)
                ) {
                    if (editor.saving) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Rounded.Save,
                            contentDescription = "Save",
                            tint = if (editor.dirty) KovaPalette.Emerald else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (editor.isBinary) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Binary file preview is disabled. Use Download from Forge to open locally.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else if (!editMode) {
            // Crisp line-numbered syntax-highlighted mobile reader
            val lines = remember(editor.content) { editor.content.split('\n') }
            SelectionContainer(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF070B13))
                        .padding(vertical = 8.dp)
                ) {
                    itemsIndexed(lines) { idx, rawLine ->
                        val highlighted = remember(rawLine, editor.language) {
                            SyntaxHighlighter.highlightLine(rawLine, editor.language)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = (idx + 1).toString().padStart(4, ' '),
                                fontFamily = FontFamily.Monospace,
                                fontSize = (fontSizeSp - 1.5f).coerceAtLeast(9f).sp,
                                color = KovaPalette.InkMuted,
                                modifier = Modifier.width(38.dp)
                            )
                            Text(
                                text = highlighted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSizeSp.sp,
                                lineHeight = (fontSizeSp * 1.42f).sp,
                                color = KovaPalette.InkPrimary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        } else {
            // Mobile-ergonomic monospace editor with symbol dock
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF070B13))
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                BasicTextField(
                    value = editor.content,
                    onValueChange = onContentChange,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSizeSp.sp,
                        lineHeight = (fontSizeSp * 1.45f).sp,
                        color = KovaPalette.InkPrimary
                    ),
                    cursorBrush = SolidColor(KovaPalette.Cyan),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Quick Coding Symbol Bar right above Android keyboard
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CODE_SYMBOLS.forEach { sym ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.clickable { onContentChange(editor.content + sym) }
                    ) {
                        Text(
                            text = if (sym == "    ") "TAB" else sym,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}
