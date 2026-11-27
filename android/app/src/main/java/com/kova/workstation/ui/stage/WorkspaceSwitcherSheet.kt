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
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kova.workstation.data.model.ClosedWindowRecord
import com.kova.workstation.data.model.QuickLaunchItem
import com.kova.workstation.data.model.WindowInfo
import com.kova.workstation.ui.WorkstationUiState
import com.kova.workstation.ui.theme.KovaPalette

/**
 * macOS Mission Control / Glass Collage Workspace Switcher.
 * Displays open Windows apps in a sleek 2-column frosted-glass collage with specular borders,
 * aspect-preserved thumbnails, 1-tap Mobile-Fit / Maximize / X-Ray / Close, Recently Closed
 * reopen, Quick Launch dock, live CPU/RAM/Disk strip, and Clipboard Bridge.
 */
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
    val glassRim = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.05f)))
    val glassFill = Brush.verticalGradient(listOf(Color(0xD81A253A), Color(0xE80D1422)))

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xF20B111E),
        scrimColor = Color.Black.copy(alpha = 0.52f)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.86f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Mission Control Header + Refresh
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Mission Control",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = KovaPalette.InkPrimary
                        )
                        Text(
                            text = buildString {
                                append(info?.hostname?.ifBlank { state.activeProfile?.name.orEmpty() } ?: "Windows Host")
                                if (info != null) append(" · ${info.screen.virtualWidth}×${info.screen.virtualHeight} · ${formatUptime(info.uptimeSeconds)}")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = KovaPalette.InkSecondary
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, glassRim)
                    ) {
                        IconButton(onClick = onRefreshWindows, modifier = Modifier.size(38.dp)) {
                            if (state.windowsLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = KovaPalette.Cyan)
                            } else {
                                Icon(Icons.Rounded.Refresh, contentDescription = "Refresh Windows", tint = KovaPalette.Cyan, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            // 2. Full Desktop Glass Hero Tile
            item {
                val isDesktopActive = state.stageMode == "desktop"
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Transparent,
                    border = BorderStroke(
                        width = if (isDesktopActive) 1.5.dp else 1.dp,
                        brush = if (isDesktopActive) Brush.linearGradient(listOf(KovaPalette.Cyan, KovaPalette.Violet)) else glassRim
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectDesktop() }
                ) {
                    Row(
                        modifier = Modifier
                            .background(glassFill)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(KovaPalette.Cyan.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.DesktopWindows, null, tint = KovaPalette.Cyan, modifier = Modifier.size(21.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Full Windows Desktop",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isDesktopActive) KovaPalette.Cyan else KovaPalette.InkPrimary
                            )
                            Text(
                                "All open windows, taskbar & system tray",
                                style = MaterialTheme.typography.labelSmall,
                                color = KovaPalette.InkSecondary
                            )
                        }
                        if (isDesktopActive) {
                            Surface(shape = RoundedCornerShape(10.dp), color = KovaPalette.Cyan.copy(alpha = 0.18f)) {
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

            // 3. 2-Column macOS Frosted-Glass Collage of Open Windows
            item {
                Text(
                    text = "Open Windows Collage (${state.windows.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = KovaPalette.InkSecondary
                )
            }

            if (state.windows.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, glassRim)
                    ) {
                        Text(
                            text = "No open desktop windows detected. Launch an app below or switch to Full Desktop.",
                            style = MaterialTheme.typography.bodySmall,
                            color = KovaPalette.InkSecondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(glassFill)
                                .padding(16.dp)
                        )
                    }
                }
            } else {
                val rows = state.windows.chunked(2)
                items(rows) { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        pair.forEach { win ->
                            val isStaged = state.stageMode == "window" && state.stageHwnd == win.hwnd
                            GlassCollageWindowTile(
                                window = win,
                                isStaged = isStaged,
                                thumbnail = state.windowThumbnails[win.hwnd],
                                onStage = { onOpenWindow(win.hwnd, false, false) },
                                onMobileFit = {
                                    if (win.mobileFit) onWindowAction(win.hwnd, "restore_fit")
                                    else onOpenWindow(win.hwnd, true, false)
                                },
                                onMaximize = { onWindowAction(win.hwnd, if (win.maximized) "restore" else "maximize") },
                                onXRay = { onOpenWindow(win.hwnd, false, true) },
                                onClose = { onWindowAction(win.hwnd, "close") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (pair.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            // 4. Recently Closed (1-Tap Reopen)
            if (state.recentlyClosedWindows.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Recently Closed", style = MaterialTheme.typography.labelLarge, color = KovaPalette.InkSecondary)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.recentlyClosedWindows) { rec ->
                                AssistChip(
                                    onClick = { onReopenClosed(rec) },
                                    label = { Text(rec.title.ifBlank { rec.process }.take(22), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    leadingIcon = { Icon(Icons.Rounded.History, null, Modifier.size(16.dp), tint = KovaPalette.Amber) }
                                )
                            }
                        }
                    }
                }
            }

            // 5. Glass Quick-Launch Dock
            val quickItems = info?.quickLaunch.orEmpty()
            if (quickItems.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Launch App", style = MaterialTheme.typography.labelLarge, color = KovaPalette.InkSecondary)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(quickItems) { app ->
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = Color.Transparent,
                                    border = BorderStroke(1.dp, glassRim),
                                    modifier = Modifier.clickable { onLaunchApp(app) }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .background(glassFill)
                                            .padding(horizontal = 12.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
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
                                        Text(app.label, style = MaterialTheme.typography.labelMedium, color = KovaPalette.InkPrimary, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 6. Glass Telemetry Strip
            item {
                val disk = info?.disks?.firstOrNull()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlassMetricTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Speed,
                        label = "CPU",
                        value = "${info?.cpuPercent?.toInt() ?: 0}%",
                        fraction = ((info?.cpuPercent ?: 0.0) / 100.0).toFloat(),
                        tint = KovaPalette.Cyan
                    )
                    GlassMetricTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Memory,
                        label = "RAM",
                        value = "${info?.memory?.percent?.toInt() ?: 0}%",
                        fraction = ((info?.memory?.percent ?: 0.0) / 100.0).toFloat(),
                        tint = KovaPalette.Violet
                    )
                    GlassMetricTile(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Storage,
                        label = disk?.drive?.removeSuffix("\\") ?: "C:",
                        value = "${disk?.percent?.toInt() ?: 0}%",
                        fraction = ((disk?.percent ?: 0.0) / 100.0).toFloat(),
                        tint = KovaPalette.Amber
                    )
                }
            }

            // 7. Glass Clipboard Bridge
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, glassRim)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(glassFill)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Clipboard Bridge", style = MaterialTheme.typography.titleSmall, color = KovaPalette.InkPrimary)
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
                            color = KovaPalette.InkSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black.copy(alpha = 0.28f))
                                .clickable {
                                    if (state.windowsClipboardText.isNotBlank()) onCopyClipboard(state.windowsClipboardText)
                                }
                                .padding(9.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassCollageWindowTile(
    window: WindowInfo,
    isStaged: Boolean,
    thumbnail: android.graphics.Bitmap?,
    onStage: () -> Unit,
    onMobileFit: () -> Unit,
    onMaximize: () -> Unit,
    onXRay: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rimBrush = if (isStaged) {
        Brush.linearGradient(listOf(KovaPalette.Cyan, KovaPalette.Violet))
    } else {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.05f)))
    }
    val cardFill = Brush.verticalGradient(
        listOf(
            if (isStaged) Color(0xE01A2C47) else Color(0xD8152033),
            Color(0xEB0B121F)
        )
    )

    Surface(
        modifier = modifier.clickable { onStage() },
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        border = BorderStroke(if (isStaged) 1.5.dp else 1.dp, rimBrush),
        shadowElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .background(cardFill)
                .padding(9.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            // macOS traffic-light-inspired header row with close dot
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
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
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = KovaPalette.InkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(KovaPalette.Rose.copy(alpha = 0.16f))
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Close, "Close Window", tint = KovaPalette.Rose, modifier = Modifier.size(13.dp))
                }
            }

            // Aspect-preserved glass preview pane (zero squishing!)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 11f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Color(0xFF060A12)),
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
                    Icon(Icons.Rounded.DesktopWindows, null, tint = KovaPalette.InkMuted, modifier = Modifier.size(26.dp))
                }

                // Subtle bottom-left resolution tag
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Black.copy(alpha = 0.62f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(5.dp)
                ) {
                    Text(
                        text = "${window.width}×${window.height}",
                        fontSize = 9.sp,
                        color = Color(0xFFD0DCEB),
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }

            // Compact frosted icon action bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                CollageMiniAction(
                    icon = Icons.Rounded.PhoneAndroid,
                    label = if (window.mobileFit) "Fit" else "Phone",
                    tint = if (window.mobileFit) KovaPalette.Emerald else KovaPalette.Cyan,
                    onClick = onMobileFit,
                    modifier = Modifier.weight(1.25f)
                )
                CollageMiniAction(
                    icon = Icons.Rounded.CropSquare,
                    label = "",
                    tint = KovaPalette.InkSecondary,
                    onClick = onMaximize,
                    modifier = Modifier.weight(0.7f)
                )
                CollageMiniAction(
                    icon = Icons.Rounded.CenterFocusStrong,
                    label = "",
                    tint = KovaPalette.Amber,
                    onClick = onXRay,
                    modifier = Modifier.weight(0.7f)
                )
            }
        }
    }
}

@Composable
private fun CollageMiniAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(9.dp),
        color = Color.White.copy(alpha = 0.08f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(13.dp))
            if (label.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(label, fontSize = 10.sp, color = tint, maxLines = 1, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun GlassMetricTile(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    fraction: Float,
    tint: Color
) {
    val rimBrush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.04f)))
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Color(0xC8141E30),
        border = BorderStroke(1.dp, rimBrush)
    ) {
        Column(
            modifier = Modifier.padding(9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(13.dp))
                    Text(label, style = MaterialTheme.typography.labelSmall, color = KovaPalette.InkSecondary)
                }
                Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = KovaPalette.InkPrimary)
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
