package com.kova.workstation.ui.hub

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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPasteGo
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.kova.workstation.data.model.QuickLaunchItem
import com.kova.workstation.data.model.WindowInfo
import com.kova.workstation.ui.WorkstationUiState
import com.kova.workstation.ui.theme.KovaPalette

@Composable
fun PulseHubScreen(
    state: WorkstationUiState,
    onRefreshWindows: () -> Unit,
    onOpenWindowStage: (Long, Boolean, Boolean) -> Unit,
    onOpenFullDesktop: () -> Unit,
    onWindowAction: (Long, String) -> Unit,
    onLaunchApp: (QuickLaunchItem) -> Unit,
    onOpenPromptComposer: () -> Unit,
    onPushClipboard: () -> Unit,
    onPullClipboard: () -> Unit,
    onCopyClipboardItem: (String) -> Unit
) {
    val info = state.systemInfo

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Live System Telemetry Strip
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = info?.hostname?.ifBlank { state.activeProfile?.name.orEmpty() } ?: "Workstation",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = buildString {
                                    append(info?.os ?: "Windows")
                                    if (info?.session?.isRdp == true) append(" · RDP Active")
                                    else if (info != null) append(" · Session #${info.session.sessionId}")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = KovaPalette.Emerald.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = formatUptime(info?.uptimeSeconds ?: 0L),
                                style = MaterialTheme.typography.labelSmall,
                                color = KovaPalette.Emerald,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MetricTile(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.Speed,
                            label = "CPU",
                            value = "${info?.cpuPercent?.toInt() ?: 0}%",
                            fraction = ((info?.cpuPercent ?: 0.0) / 100.0).toFloat(),
                            tint = KovaPalette.Cyan
                        )
                        MetricTile(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.Memory,
                            label = "RAM",
                            value = "${info?.memory?.percent?.toInt() ?: 0}%",
                            fraction = ((info?.memory?.percent ?: 0.0) / 100.0).toFloat(),
                            tint = KovaPalette.Violet
                        )
                        val disk = info?.disks?.firstOrNull()
                        MetricTile(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.Storage,
                            label = disk?.drive?.removeSuffix("\\") ?: "C:",
                            value = "${disk?.percent?.toInt() ?: 0}%",
                            fraction = ((disk?.percent ?: 0.0) / 100.0).toFloat(),
                            tint = KovaPalette.Amber
                        )
                    }

                    // Primary quick actions row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onOpenPromptComposer,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Send Prompt", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = onOpenFullDesktop,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Rounded.DesktopWindows, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Full Desktop", maxLines = 1)
                        }
                    }
                }
            }
        }

        // 2. Quick Launch Dock
        val quickItems = info?.quickLaunch.orEmpty()
        if (quickItems.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Quick Launch",
                        style = MaterialTheme.typography.titleSmall,
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

        // 3. Live Desktop Window Cards (Mobile-Native Task Switcher!)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Active Windows (${state.windows.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = onRefreshWindows, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh Windows", tint = KovaPalette.Cyan)
                }
            }
        }

        if (state.windows.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text(
                        text = "No visible desktop windows found. Launch an app above or switch to Shell / Forge.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(state.windows, key = { it.hwnd }) { win ->
                WindowCard(
                    window = win,
                    thumbnail = state.windowThumbnails[win.hwnd],
                    onStage = { onOpenWindowStage(win.hwnd, false, false) },
                    onMobileFitStage = { onOpenWindowStage(win.hwnd, !win.mobileFit, false) },
                    onXRay = { onOpenWindowStage(win.hwnd, false, true) },
                    onRestoreFit = { onWindowAction(win.hwnd, "restore_fit") },
                    onClose = { onWindowAction(win.hwnd, "close") }
                )
            }
        }

        // 4. Bi-Directional Live Clipboard Bridge
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                leadingIcon = { Icon(Icons.Rounded.ContentPasteGo, null, Modifier.size(16.dp)) }
                            )
                            AssistChip(
                                onClick = onPullClipboard,
                                label = { Text("PC → Phone") },
                                leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp)) }
                            )
                        }
                    }

                    val preview = state.windowsClipboardText.ifBlank { "(Windows clipboard is empty)" }
                    Text(
                        text = preview.take(240),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { if (state.windowsClipboardText.isNotBlank()) onCopyClipboardItem(state.windowsClipboardText) }
                            .padding(10.dp)
                    )

                    if (state.clipboardHistory.size > 1) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.clipboardHistory.drop(1).take(4).forEach { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onCopyClipboardItem(entry.text) }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = entry.text.replace('\n', ' '),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        Icons.Rounded.ContentCopy,
                                        contentDescription = "Copy",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
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
            .background(MaterialTheme.colorScheme.surface)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))
            Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = tint,
            trackColor = tint.copy(alpha = 0.16f)
        )
    }
}

@Composable
private fun WindowCard(
    window: WindowInfo,
    thumbnail: android.graphics.Bitmap?,
    onStage: () -> Unit,
    onMobileFitStage: () -> Unit,
    onXRay: () -> Unit,
    onRestoreFit: () -> Unit,
    onClose: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = if (window.focused) 1.5.dp else 1.dp,
            color = if (window.focused) KovaPalette.Cyan else MaterialTheme.colorScheme.outline
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                window.focused -> KovaPalette.Emerald
                                window.minimized -> KovaPalette.InkMuted
                                else -> KovaPalette.Cyan
                            }
                        )
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = window.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(window.process.removeSuffix(".exe"))
                            append(" · ${window.width}×${window.height}")
                            if (window.mobileFit) append(" · Mobile-Fit")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (window.mobileFit) KovaPalette.Emerald else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Close Window",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (thumbnail != null) {
                val imgBmp = remember(thumbnail) { thumbnail.asImageBitmap() }
                Image(
                    bitmap = imgBmp,
                    contentDescription = window.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 8.5f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onStage() }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onStage,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Rounded.Visibility, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Stage", maxLines = 1)
                }
                FilledTonalButton(
                    onClick = if (window.mobileFit) onRestoreFit else onMobileFitStage,
                    modifier = Modifier.weight(1.15f),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Rounded.PhoneAndroid, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (window.mobileFit) "Desktop Fit" else "Mobile-Fit", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onXRay,
                    modifier = Modifier.weight(0.95f),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Rounded.CenterFocusStrong, null, Modifier.size(15.dp), tint = KovaPalette.Amber)
                    Spacer(Modifier.width(5.dp))
                    Text("X-Ray", maxLines = 1)
                }
            }
        }
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
