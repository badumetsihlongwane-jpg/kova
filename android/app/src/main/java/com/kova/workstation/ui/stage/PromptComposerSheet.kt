package com.kova.workstation.ui.stage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kova.workstation.ui.theme.KovaPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptComposerSheet(
    targetTitle: String,
    onDispatch: (text: String, pressEnter: Boolean, mode: String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf("") }
    var pasteMode by remember { mutableStateOf(true) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = KovaPalette.Cyan, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Smart Prompt & Text Dispatch", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Use Gboard swipe or voice dictation, then inject cleanly into $targetTitle",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Write a prompt, message, URL, or code snippet...") },
                minLines = 3,
                maxLines = 7,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = pasteMode,
                    onClick = { pasteMode = true },
                    label = { Text("Fast Paste (Ctrl+V)") }
                )
                FilterChip(
                    selected = !pasteMode,
                    onClick = { pasteMode = false },
                    label = { Text("Direct Unicode Type") }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = {
                        onDispatch(text, false, if (pasteMode) "paste" else "type")
                        text = ""
                        onDismiss()
                    },
                    enabled = text.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.Send, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Inject Only")
                }
                Button(
                    onClick = {
                        onDispatch(text, true, if (pasteMode) "paste" else "type")
                        text = ""
                        onDismiss()
                    },
                    enabled = text.isNotEmpty(),
                    modifier = Modifier.weight(1.2f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.KeyboardReturn, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Send + Enter")
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}
