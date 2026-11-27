package com.kova.workstation.ui.files

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Source
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kova.workstation.data.model.GitStatusResponse
import com.kova.workstation.ui.theme.KovaPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitPanelSheet(
    status: GitStatusResponse?,
    diffText: String,
    diffFile: String?,
    onRefresh: () -> Unit,
    onSelectDiff: (String?, Boolean) -> Unit,
    onStageAll: () -> Unit,
    onCommit: (String) -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var commitMessage by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.84f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Source, null, tint = KovaPalette.Emerald)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Git · ${status?.branch?.ifBlank { "repo" } ?: "..."}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = status?.root.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Rounded.Refresh, "Refresh Git", tint = KovaPalette.Cyan)
                }
            }

            if (status != null && !status.gitAvailable) {
                Text(
                    status.error.ifBlank { "Git is not installed on this Windows host." },
                    style = MaterialTheme.typography.bodySmall,
                    color = KovaPalette.Amber
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(onClick = onStageAll, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Text("Stage All")
                    }
                    FilledTonalButton(onClick = onPull, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Rounded.CloudDownload, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Pull")
                    }
                    Button(onClick = onPush, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Rounded.CloudUpload, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Push")
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = commitMessage,
                        onValueChange = { commitMessage = it },
                        placeholder = { Text("Commit message...") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (commitMessage.isNotBlank()) {
                                onCommit(commitMessage)
                                commitMessage = ""
                            }
                        },
                        enabled = commitMessage.isNotBlank(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                    }
                }

                // Changed Files + Colored Diff Viewer
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val files = status?.files.orEmpty()
                    if (files.isEmpty()) {
                        item {
                            Text(
                                "Working tree clean.",
                                style = MaterialTheme.typography.bodySmall,
                                color = KovaPalette.Emerald
                            )
                        }
                    } else {
                        items(files) { f ->
                            val badge = if (f.untracked) "?" else (f.worktreeStatus.trim().ifEmpty { f.indexStatus.trim() })
                            val badgeColor = when (badge) {
                                "A", "?" -> KovaPalette.Emerald
                                "D" -> KovaPalette.Rose
                                else -> KovaPalette.Amber
                            }
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectDiff(f.path, f.staged) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        badge,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = badgeColor,
                                        modifier = Modifier.width(20.dp)
                                    )
                                    Text(
                                        f.path,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    if (diffText.isNotBlank()) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Diff: ${diffFile.orEmpty()}",
                                style = MaterialTheme.typography.titleSmall,
                                color = KovaPalette.Cyan
                            )
                        }
                        val diffLines = diffText.lines().take(350)
                        items(diffLines) { line ->
                            val styled = remember(line) { SyntaxHighlighter.highlightLine(line, "diff") }
                            Text(
                                text = styled,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF070B13))
                                    .padding(horizontal = 8.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
