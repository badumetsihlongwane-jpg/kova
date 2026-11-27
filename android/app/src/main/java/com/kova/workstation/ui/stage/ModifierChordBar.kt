package com.kova.workstation.ui.stage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kova.workstation.ui.StageInputMode
import com.kova.workstation.ui.theme.KovaPalette

private data class QuickKey(val label: String, val keys: List<String>)

private val DESKTOP_SHORTCUTS = listOf(
    QuickKey("Esc", listOf("esc")),
    QuickKey("Tab", listOf("tab")),
    QuickKey("Enter", listOf("enter")),
    QuickKey("Bksp", listOf("backspace")),
    QuickKey("Ctrl+C", listOf("ctrl", "c")),
    QuickKey("Ctrl+V", listOf("ctrl", "v")),
    QuickKey("Ctrl+Z", listOf("ctrl", "z")),
    QuickKey("Ctrl+S", listOf("ctrl", "s")),
    QuickKey("Cmd Pal", listOf("ctrl", "shift", "p")),
    QuickKey("Alt+Tab", listOf("alt", "tab")),
    QuickKey("Win", listOf("win")),
    QuickKey("↑", listOf("up")),
    QuickKey("↓", listOf("down")),
    QuickKey("←", listOf("left")),
    QuickKey("→", listOf("right")),
    QuickKey("F5", listOf("f5")),
    QuickKey("Del", listOf("delete"))
)

@Composable
fun ModifierChordBar(
    inputMode: StageInputMode,
    dragLocked: Boolean,
    hapticsEnabled: Boolean,
    onModeChange: (StageInputMode) -> Unit,
    onLeftClick: () -> Unit,
    onRightClick: () -> Unit,
    onToggleDragLock: () -> Unit,
    onOpenComposer: () -> Unit,
    onOpenControls: () -> Unit,
    onSendKeys: (List<String>) -> Unit
) {
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Row 1: Primary one-thumb interaction controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ModePill(
                label = "Pad",
                icon = Icons.Rounded.Mouse,
                selected = inputMode == StageInputMode.TRACKPAD,
                onClick = { onModeChange(StageInputMode.TRACKPAD) },
                modifier = Modifier.weight(1f)
            )
            ModePill(
                label = "Loupe",
                icon = Icons.Rounded.ZoomIn,
                selected = inputMode == StageInputMode.LOUPE,
                onClick = { onModeChange(StageInputMode.LOUPE) },
                modifier = Modifier.weight(1f)
            )
            ModePill(
                label = "Touch",
                icon = Icons.Rounded.TouchApp,
                selected = inputMode == StageInputMode.DIRECT,
                onClick = { onModeChange(StageInputMode.DIRECT) },
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = KovaPalette.Cyan.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, KovaPalette.Cyan.copy(alpha = 0.5f)),
                modifier = Modifier
                    .weight(1.1f)
                    .clickable {
                        if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onOpenComposer()
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = KovaPalette.Cyan, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Type", style = MaterialTheme.typography.labelMedium, color = KovaPalette.Cyan)
                }
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = KovaPalette.Amber.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, KovaPalette.Amber.copy(alpha = 0.45f)),
                modifier = Modifier
                    .weight(1.1f)
                    .clickable { onOpenControls() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.CenterFocusStrong, null, tint = KovaPalette.Amber, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("UI", style = MaterialTheme.typography.labelMedium, color = KovaPalette.Amber)
                }
            }
        }

        // Row 2: Mouse clicks + Drag Lock + Scrollable Windows shortcuts
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyChip("L-Click", accent = KovaPalette.Emerald) {
                if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onLeftClick()
            }
            KeyChip("R-Click", accent = KovaPalette.Violet) {
                if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onRightClick()
            }
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = if (dragLocked) KovaPalette.Rose.copy(alpha = 0.24f) else MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, if (dragLocked) KovaPalette.Rose else MaterialTheme.colorScheme.outline),
                modifier = Modifier.clickable {
                    if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggleDragLock()
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.DragIndicator,
                        null,
                        modifier = Modifier.size(14.dp),
                        tint = if (dragLocked) KovaPalette.Rose else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        if (dragLocked) "Dragging" else "Drag",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (dragLocked) KovaPalette.Rose else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            DESKTOP_SHORTCUTS.forEach { key ->
                KeyChip(key.label) {
                    if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSendKeys(key.keys)
                }
            }
        }
    }
}

@Composable
private fun ModePill(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            1.dp,
            if (selected) KovaPalette.Cyan else MaterialTheme.colorScheme.outline
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                null,
                modifier = Modifier.size(14.dp),
                tint = if (selected) KovaPalette.Cyan else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) KovaPalette.Cyan else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun KeyChip(
    label: String,
    accent: Color? = null,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = accent?.copy(alpha = 0.14f) ?: MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, accent?.copy(alpha = 0.45f) ?: MaterialTheme.colorScheme.outline),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            ),
            color = accent ?: MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}
