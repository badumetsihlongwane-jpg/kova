package com.kova.workstation.ui.stage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPasteGo
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Minimize
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kova.workstation.data.model.ClosedWindowRecord
import com.kova.workstation.data.model.QuickLaunchItem
import com.kova.workstation.data.model.WindowInfo
import com.kova.workstation.ui.WorkstationUiState
import com.kova.workstation.ui.theme.KovaPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceSwitcherSheet(
    state: WorkstationUiState,
    onSelectDesktop: () -> Unit,
    onOpenWindow: (hwnd: Long, mobileFit: Boolean, openXRay: Boolean) -> Unit,
    onWindowAction: (hwnd: Long, action: String) -> Unit,
    onReopenClosed: (ClosedWindowRecord) -> Unit,
    onLaunchApp: (QuickLaunchItem) -> Unit,
    onRefreshWindows: () -> Unit,
    onPushClipboard: () -> Unit,
    onPullClipboard: () -> Unit,
    onCopyClipboard: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val info = state.systemInfo

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.86f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Header + Full Desktop Quick Switch
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Windows Workspace", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = buildString {
                                append(info?.hostname?.ifBlank { state.activeProfile?.name.orEmpty() } ?: "Windows Host")
                                if (info != null) append(" · ${formatUptime(info.uptimeSeconds)}")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onRefreshWindows) {
                        if (state.windowsLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = KovaPalette.Cyan)
                        } else {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Refresh Windows", tint = KovaPalette.Cyan)
                        }
                    }
                }
            }

            // 2. Full Desktop Banner Option
            item {
                val isDesktopActive = state.stageMode == "desktop"
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isDesktopActive) KovaPalette.Cyan.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, if (isDesktopActive) KovaPalette.Cyan else MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectDesktop() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.DesktopWindows,
                            contentDescription = null,
                            tint = if (isDesktopActive) KovaPalette.Cyan else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Full Windows Desktop",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isDesktopActive) KovaPalette.Cyan else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = info?.screen?.let { "${it.virtualWidth}×${it.virtualHeight} · All windows & taskbar" }
                                    ?: "Complete desktop & taskbar",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isDesktopActive) {
                            Surface(shape = RoundedCornerShape(8.dp), color = KovaPalette.Cyan.copy(alpha = 0.2f)) {
                                Text(
                                    "Active",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = KovaPalette.Cyan,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 3. Open Windows Strip (Aspect-Preserved Previews + Full Controls)
            item {
                Text(
                    text = "Open Windows (${state.windows.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (state.windows.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Text(
                            text = "No open desktop windows detected. Launch an app below or switch to Full Desktop.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            } else {
                items(state.windows, key = { it.hwnd }) { win ->
                    val isStaged = state.stageMode == "window" && state.stageHwnd == win.hwnd
                    SwitcherWindowRow(
                        window = win,
                        isStaged = isStaged,
                        thumbnail = state.windowThumbnails[win.hwnd],
                        onStage = { onOpenWindow(win.hwnd, false, false) },
                        onMobileFitToggle = {
                            if (win.mobileFit) onWindowAction(win.hwnd, "restore_fit")
                            else onOpenWindow(win.hwnd, true, false)
                        },
                        onMaximize = { onWindowAction(win.hwnd, if (win.maximized) "restore" else "maximize") },
                        onMinimize = { onWindowAction(win.hwnd, if (win.minimized) "restore" else "minimize") },
                        onXRay = { onOpenWindow(win.hwnd, false, true) },
                        onClose = { onWindowAction(win.hwnd, "close") }
                    )
                }
            }

            // 4. Recently Closed Windows (1-Tap Reopen!)
            if (state.recentlyClosedWindows.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Recently Closed (Tap to Reopen)",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.recentlyClosedWindows) { rec ->
                                AssistChip(
                                    onClick = { onReopenClosed(rec) },
                                    label = {
                                        Text(
                                            rec.title.ifBlank { rec.process }.take(22),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.History, null, Modifier.size(16.dp), tint = KovaPalette.Amber)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 5. Quick Launch App Dock
            val quickItems = info?.quickLaunch.orEmpty()
            if (quickItems.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Launch Application",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(quickItems) { app ->
                                AssistChip(
                                    onClick = { onLaunchApp(app) },
                                    label = { Text(app.label, maxLines = 1) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = when (app.icon) {
                                                "terminal" -> Icons.Rounded.Terminal
                                                "folder" -> Icons.Rounded.Folder
                                                "code" -> Icons.Rounded.Code
                                                "web" -> Icons.Rounded.Language
                                                "edit" -> Icons.Rounded.Edit
                                                "settings" -> Icons.Rounded.Settings
                                                else -> Icons.Rounded.DesktopWindows
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = KovaPalette.Cyan
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 6. Compact Live Host Telemetry Strip
            item {
                val disk = info?.disks?.firstOrNull()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CompactMetricTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Speed,
                        label = "CPU",
                        value = "${info?.cpuPercent?.toInt() ?: 0}%",
                        fraction = ((info?.cpuPercent ?: 0.0) / 100.0).toFloat(),
                        tint = KovaPalette.Cyan
                    )
                    CompactMetricTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Memory,
                        label = "RAM",
                        value = "${info?.memory?.percent?.toInt() ?: 0}%",
                        fraction = ((info?.memory?.percent ?: 0.0) / 100.0).toFloat(),
                        tint = KovaPalette.Violet
                    )
                    CompactMetricTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Storage,
                        label = disk?.drive?.removeSuffix("\\") ?: "C:",
                        value = "${disk?.percent?.toInt() ?: 0}%",
                        fraction = ((disk?.percent ?: 0.0) / 100.0).toFloat(),
                        tint = KovaPalette.Amber
                    )
                }
            }

            // 7. Bi-Directional Clipboard Bridge
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Clipboard Bridge", style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AssistChip(
                                    onClick = onPushClipboard,
                                    label = { Text("Phone → PC") },
                                    leadingIcon = { Icon(Icons.Rounded.ContentPasteGo, null, Modifier.size(15.dp)) }
                                )
                                AssistChip(
                                    onClick = onPullClipboard,
                                    label = { Text("PC → Phone") },
                                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(15.dp)) }
                                )
                            }
                        }
                        val preview = state.windowsClipboardText.ifBlank { "(Windows clipboard is empty)" }
                        Text(
                            text = preview.take(180),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable {
                                    if (state.windowsClipboardText.isNotBlank()) onCopyClipboard(state.windowsClipboardText)
                                }
                                .padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitcherWindowRow(
    window: WindowInfo,
    isStaged: Boolean,
    thumbnail: android.graphics.Bitmap?,
    onStage: () -> Unit,
    onMobileFitToggle: () -> Unit,
    onMaximize: () -> Unit,
    onMinimize: () -> Unit,
    onXRay: () -> Unit,
    onClose: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isStaged) KovaPalette.Cyan.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = BorderStroke(
            width = if (isStaged) 1.5.dp else 1.dp,
            color = if (isStaged) KovaPalette.Cyan else MaterialTheme.colorScheme.outline
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onStage() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Aspect-preserved thumbnail box (never stretches or distorts!)
                Box(
                    modifier = Modifier
                        .width(84.dp)
                        .aspectRatio(16f / 10f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (thumbnail != null) {
                        val imgBmp = remember(thumbnail) { thumbnail.asImageBitmap() }
                        Image(
                            bitmap = imgBmp,
                            contentDescription = window.title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            Icons.Rounded.DesktopWindows,
                            contentDescription = null,
                            tint = KovaPalette.InkMuted,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isStaged -> KovaPalette.Cyan
                                        window.focused -> KovaPalette.Emerald
                                        window.minimized -> KovaPalette.InkMuted
                                        else -> KovaPalette.Amber
                                    }
                                )
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = window.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = buildString {
                            append(window.process.removeSuffix(".exe"))
                            append(" · ${window.width}×${window.height}")
                            if (window.minimized) append(" · Minimized")
                            else if (window.mobileFit) append(" · Mobile-Fit")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (window.mobileFit) KovaPalette.Emerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onMinimize, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.Minimize, "Minimize/Restore", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onMaximize, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.CropSquare, "Maximize/Restore", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Rounded.Close, "Close Window", tint = KovaPalette.Rose, modifier = Modifier.size(17.dp))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onStage,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(if (isStaged) "Viewing" else "Focus", maxLines = 1)
                }
                FilledTonalButton(
                    onClick = onMobileFitToggle,
                    modifier = Modifier.weight(1.25f),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Rounded.PhoneAndroid, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (window.mobileFit) "Restore Size" else "Mobile-Fit", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onXRay,
                    modifier = Modifier.weight(0.95f),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Rounded.CenterFocusStrong, null, Modifier.size(14.dp), tint = KovaPalette.Amber)
                    Spacer(Modifier.width(4.dp))
                    Text("X-Ray", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun CompactMetricTile(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    fraction: Float,
    tint: Color
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(13.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = tint,
            trackColor = tint.copy(alpha = 0.16f)
        )
    }
}

private fun formatUptime(seconds: Long): String {
    if (seconds <= 0L) return "Online"
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val mins = (seconds % 3600) / 60
    return when {
        days > 0 -> "Up ${days}d ${hours}h"
        hours > 0 -> "Up ${hours}h ${mins}m"
        else -> "Up ${mins}m"
    }
}
