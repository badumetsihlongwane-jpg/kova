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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Description
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    var showSearchBar by remember { mutableStateOf(false) }
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
        // 1. Sleek Quick Roots Pill Strip
        if (state.fileRoots.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.fileRoots) { root ->
                    val selected = state.currentPath.equals(root.path, ignoreCase = true)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) KovaPalette.Cyan.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(
                            1.dp,
                            if (selected) KovaPalette.Cyan.copy(alpha = 0.6f) else Color.Transparent
                        ),
                        modifier = Modifier.clickable { onBrowse(root.path) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = when (root.icon) {
                                    "home" -> Icons.Rounded.Home
                                    "code" -> Icons.Rounded.Code
                                    "storage" -> Icons.Rounded.Storage
                                    else -> Icons.Rounded.Folder
                                },
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (selected) KovaPalette.Cyan else KovaPalette.InkSecondary
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                root.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) KovaPalette.Cyan else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // 2. Interactive Segmented Breadcrumb Bar + Search & Hidden Toggles
        val segments = remember(state.currentPath) { parseWindowsBreadcrumbs(state.currentPath) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { state.parentPath?.let { onBrowse(it) } },
                enabled = state.parentPath != null,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Up", Modifier.size(18.dp))
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                segments.forEachIndexed { idx, (label, fullPath) ->
                    if (idx > 0) {
                        Icon(
                            Icons.Rounded.ChevronRight,
                            null,
                            tint = KovaPalette.InkMuted,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (idx == segments.lastIndex) FontWeight.Bold else FontWeight.Normal
                        ),
                        color = if (idx == segments.lastIndex) KovaPalette.Cyan else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onBrowse(fullPath) }
                            .padding(horizontal = 5.dp, vertical = 4.dp)
                    )
                }
            }
            IconButton(onClick = { showSearchBar = !showSearchBar; if (!showSearchBar) onSearch("") }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Rounded.Search,
                    "Search",
                    tint = if (showSearchBar) KovaPalette.Cyan else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onToggleHidden, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (state.showHiddenFiles) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                    "Hidden files",
                    tint = if (state.showHiddenFiles) KovaPalette.Cyan else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // 3. Compact Glass Action Bar (Upload, Shell Here, New File/Folder, Git)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ForgeActionPill("Upload", Icons.Rounded.UploadFile, KovaPalette.Cyan) {
                uploadPicker.launch(arrayOf("*/*"))
            }
            ForgeActionPill("Shell Here", Icons.Rounded.Terminal, KovaPalette.Emerald) {
                onOpenTerminalHere(state.currentPath)
            }
            if (!state.currentGitRoot.isNullOrBlank()) {
                ForgeActionPill("Git", Icons.Rounded.Source, KovaPalette.Violet) { onOpenGit() }
            }
            ForgeActionPill("File", Icons.Rounded.NoteAdd, KovaPalette.InkSecondary) { showNewFileDialog = true }
            ForgeActionPill("Folder", Icons.Rounded.CreateNewFolder, KovaPalette.Amber) { showNewFolderDialog = true }
        }

        if (showSearchBar) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearch,
                placeholder = { Text("Filter or search files...") },
                leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        if (state.filesLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = KovaPalette.Cyan)
        }

        val visibleItems = state.searchResults ?: state.fileEntries

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (visibleItems.isEmpty() && !state.filesLoading) {
                item {
                    Text(
                        "Empty folder.",
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
private fun ForgeActionPill(
    label: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = tint.copy(alpha = 0.12f),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
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

    val (iconVec, badgeTint) = when {
        entry.isDir -> Icons.Rounded.Folder to KovaPalette.Amber
        entry.language in setOf("python", "kotlin", "java", "javascript", "typescript", "csharp", "go", "rust") ->
            Icons.Rounded.Code to KovaPalette.Cyan
        entry.language in setOf("json", "yaml", "toml", "xml") ->
            Icons.Rounded.Code to KovaPalette.Violet
        entry.language in setOf("powershell", "shell", "batch") ->
            Icons.Rounded.Terminal to KovaPalette.Emerald
        entry.language in setOf("log", "markdown") ->
            Icons.Rounded.Description to KovaPalette.Rose
        else -> Icons.AutoMirrored.Rounded.InsertDriveFile to KovaPalette.InkSecondary
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.68f),
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
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(badgeTint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(iconVec, contentDescription = null, tint = badgeTint, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (entry.isDir) FontWeight.Medium else FontWeight.Normal,
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
                    Icon(Icons.Rounded.MoreVert, "Actions", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

private fun parseWindowsBreadcrumbs(path: String): List<Pair<String, String>> {
    if (path.isBlank()) return emptyList()
    val norm = path.replace('/', '\\').trimEnd('\\')
    val parts = norm.split('\\').filter { it.isNotBlank() }
    val out = mutableListOf<Pair<String, String>>()
    var acc = ""
    parts.forEachIndexed { idx, part ->
        acc = if (idx == 0) "$part\\" else "${acc.trimEnd('\\')}\\$part"
        out.add(part to acc)
    }
    return out
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
