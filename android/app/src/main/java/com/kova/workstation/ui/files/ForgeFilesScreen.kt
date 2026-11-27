package com.kova.workstation.ui.files

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Source
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kova.workstation.data.model.FileEntry
import com.kova.workstation.ui.WorkstationUiState
import com.kova.workstation.ui.theme.KovaPalette

@Composable
fun ForgeFilesScreen(
    state: WorkstationUiState,
    onBrowse: (String?) -> Unit,
    onSearch: (String) -> Unit,
    onToggleHidden: () -> Unit,
    onOpenFile: (String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onCreateFile: (String) -> Unit,
    onRename: (FileEntry, String) -> Unit,
    onDelete: (FileEntry) -> Unit,
    onUploadBytes: (String, ByteArray) -> Unit,
    onDownloadFile: (String, (ByteArray) -> Unit) -> Unit,
    onOpenTerminalHere: (String) -> Unit,
    onCopyPath: (String) -> Unit,
    onOpenGit: () -> Unit
) {
    val context = LocalContext.current
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<FileEntry?>(null) }
    var pendingDownloadEntry by remember { mutableStateOf<FileEntry?>(null) }

    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val resolver = context.contentResolver
            var name = "uploaded_file"
            resolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
            }
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) onUploadBytes(name, bytes)
        }
    }

    val downloadSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val target = pendingDownloadEntry
        pendingDownloadEntry = null
        if (uri != null && target != null) {
            onDownloadFile(target.path) { bytes ->
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            }
        }
    }

    if (showNewFolderDialog) {
        PromptNameDialog(
            title = "New Folder",
            placeholder = "Folder name",
            onConfirm = { onCreateFolder(it); showNewFolderDialog = false },
            onDismiss = { showNewFolderDialog = false }
        )
    }

    if (showNewFileDialog) {
        PromptNameDialog(
            title = "New File",
            placeholder = "script.py or notes.md",
            onConfirm = { onCreateFile(it); showNewFileDialog = false },
            onDismiss = { showNewFileDialog = false }
        )
    }

    renameTarget?.let { entry ->
        PromptNameDialog(
            title = "Rename ${entry.name}",
            initial = entry.name,
            placeholder = "New name",
            onConfirm = { onRename(entry, it); renameTarget = null },
            onDismiss = { renameTarget = null }
        )
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${if (entry.isDir) "Folder" else "File"}?") },
            text = { Text("Permanently remove ${entry.name} on your Windows host?") },
            confirmButton = {
                TextButton(onClick = { onDelete(entry); deleteTarget = null }) {
                    Text("Delete", color = KovaPalette.Rose)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 1. Pinned Roots & Drives
        if (state.fileRoots.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.fileRoots) { root ->
                    val selected = state.currentPath.equals(root.path, ignoreCase = true)
                    AssistChip(
                        onClick = { onBrowse(root.path) },
                        label = { Text(root.label) },
                        leadingIcon = {
                            Icon(
                                imageVector = when (root.icon) {
                                    "home" -> Icons.Rounded.Home
                                    "code" -> Icons.Rounded.Code
                                    "storage" -> Icons.Rounded.Storage
                                    else -> Icons.Rounded.Folder
                                },
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = if (selected) KovaPalette.Emerald else KovaPalette.Cyan
                            )
                        }
                    )
                }
            }
        }

        // 2. Path Breadcrumb + Up + Actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { state.parentPath?.let { onBrowse(it) } },
                enabled = state.parentPath != null,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Parent folder")
            }
            Text(
                text = state.currentPath.ifBlank { "Loading..." },
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = KovaPalette.Cyan,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onCopyPath(state.currentPath) }
                    .padding(horizontal = 4.dp)
            )
            IconButton(onClick = onToggleHidden, modifier = Modifier.size(34.dp)) {
                Icon(
                    if (state.showHiddenFiles) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                    contentDescription = "Hidden files",
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // 3. Action Pill Row + Search
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!state.currentGitRoot.isNullOrBlank()) {
                AssistChip(
                    onClick = onOpenGit,
                    label = { Text("Git", color = KovaPalette.Emerald) },
                    leadingIcon = { Icon(Icons.Rounded.Source, null, Modifier.size(16.dp), tint = KovaPalette.Emerald) }
                )
            }
            AssistChip(
                onClick = { onOpenTerminalHere(state.currentPath) },
                label = { Text("Shell Here") },
                leadingIcon = { Icon(Icons.Rounded.Terminal, null, Modifier.size(16.dp), tint = KovaPalette.Cyan) }
            )
            AssistChip(
                onClick = { uploadPicker.launch(arrayOf("*/*")) },
                label = { Text("Upload") },
                leadingIcon = { Icon(Icons.Rounded.UploadFile, null, Modifier.size(16.dp)) }
            )
            AssistChip(
                onClick = { showNewFileDialog = true },
                label = { Text("New File") },
                leadingIcon = { Icon(Icons.Rounded.NoteAdd, null, Modifier.size(16.dp)) }
            )
            AssistChip(
                onClick = { showNewFolderDialog = true },
                label = { Text("New Folder") },
                leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null, Modifier.size(16.dp)) }
            )
        }

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearch,
            placeholder = { Text("Search files in this folder...") },
            leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )

        if (state.filesLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = KovaPalette.Cyan)
        }

        val visibleItems = state.searchResults ?: state.fileEntries

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (visibleItems.isEmpty() && !state.filesLoading) {
                item {
                    Text(
                        "Folder is empty.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                items(visibleItems, key = { it.path }) { entry ->
                    FileEntryRow(
                        entry = entry,
                        onClick = {
                            if (entry.isDir) onBrowse(entry.path) else onOpenFile(entry.path)
                        },
                        onRename = { renameTarget = entry },
                        onDelete = { deleteTarget = entry },
                        onCopyPath = { onCopyPath(entry.path) },
                        onDownload = {
                            pendingDownloadEntry = entry
                            downloadSaver.launch(entry.name)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FileEntryRow(
    entry: FileEntry,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopyPath: () -> Unit,
    onDownload: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when {
                    entry.isDir -> Icons.Rounded.Folder
                    entry.language !in setOf("text", "binary") -> Icons.Rounded.Code
                    else -> Icons.AutoMirrored.Rounded.InsertDriveFile
                },
                contentDescription = null,
                tint = when {
                    entry.isDir -> KovaPalette.Amber
                    entry.language !in setOf("text", "binary") -> KovaPalette.Cyan
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (entry.isDir) "Folder" else "${formatSize(entry.size)} · ${entry.language}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.MoreVert, "Actions", Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Copy Path") }, onClick = { menuOpen = false; onCopyPath() })
                    if (!entry.isDir) {
                        DropdownMenuItem(text = { Text("Download to Phone") }, onClick = { menuOpen = false; onDownload() })
                    }
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
                    DropdownMenuItem(
                        text = { Text("Delete", color = KovaPalette.Rose) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun PromptNameDialog(
    title: String,
    placeholder: String,
    initial: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(placeholder) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text("Confirm") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
