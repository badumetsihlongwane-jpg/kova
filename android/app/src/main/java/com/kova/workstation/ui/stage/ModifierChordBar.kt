package com.kova.workstation.ui.stage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.Icon
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
    QuickKey("Cmd+P", listOf("ctrl", "shift", "p")),
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
    var showKeysDrawer by remember { mutableStateOf(false) }

    val nextMode = when (inputMode) {
        StageInputMode.TRACKPAD -> StageInputMode.LOUPE
        StageInputMode.LOUPE -> StageInputMode.DIRECT
        StageInputMode.DIRECT -> StageInputMode.TRACKPAD
    }
    val (modeLabel, modeIcon) = when (inputMode) {
        StageInputMode.TRACKPAD -> "Pad" to Icons.Rounded.Mouse
        StageInputMode.LOUPE -> "Loupe" to Icons.Rounded.ZoomIn
        StageInputMode.DIRECT -> "Touch" to Icons.Rounded.TouchApp
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KovaPalette.SlateDeep.copy(alpha = 0.96f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Expandable compact shortcut & mouse-button drawer (keeps default canvas clutter-free!)
        AnimatedVisibility(
            visible = showKeysDrawer || dragLocked,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 2.dp),
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
                    shape = RoundedCornerShape(10.dp),
                    color = if (dragLocked) KovaPalette.Rose.copy(alpha = 0.24f) else KovaPalette.SlateCard,
                    border = BorderStroke(1.dp, if (dragLocked) KovaPalette.Rose else KovaPalette.BorderDark),
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
                            tint = if (dragLocked) KovaPalette.Rose else KovaPalette.InkSecondary
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            if (dragLocked) "Dragging" else "Drag",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (dragLocked) KovaPalette.Rose else KovaPalette.InkPrimary
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

        // Clean, Calm Single-Row Primary Dock
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = KovaPalette.SlateCard,
            border = BorderStroke(1.dp, KovaPalette.BorderDark.copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DockAction(
                    label = modeLabel,
                    icon = modeIcon,
                    tint = KovaPalette.Cyan,
                    selected = true,
                    onClick = {
                        if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onModeChange(nextMode)
                    },
                    modifier = Modifier.weight(1f)
                )
                DockAction(
                    label = "Type",
                    icon = Icons.Rounded.AutoAwesome,
                    tint = KovaPalette.Emerald,
                    selected = false,
                    onClick = {
                        if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onOpenComposer()
                    },
                    modifier = Modifier.weight(1f)
                )
                DockAction(
                    label = "Controls",
                    icon = Icons.Rounded.CenterFocusStrong,
                    tint = KovaPalette.Amber,
                    selected = false,
                    onClick = onOpenControls,
                    modifier = Modifier.weight(1.1f)
                )
                DockAction(
                    label = "Keys",
                    icon = Icons.Rounded.Keyboard,
                    tint = if (showKeysDrawer) KovaPalette.Cyan else KovaPalette.InkSecondary,
                    selected = showKeysDrawer,
                    onClick = { showKeysDrawer = !showKeysDrawer },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun DockAction(
    label: String,
    icon: ImageVector,
    tint: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(11.dp),
        color = if (selected) tint.copy(alpha = 0.14f) else Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, modifier = Modifier.size(16.dp), tint = tint)
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) tint else KovaPalette.InkPrimary,
                maxLines = 1
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
        shape = RoundedCornerShape(10.dp),
        color = accent?.copy(alpha = 0.14f) ?: KovaPalette.SlateCard,
        border = BorderStroke(1.dp, accent?.copy(alpha = 0.35f) ?: KovaPalette.BorderDark),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            ),
            color = accent ?: KovaPalette.InkPrimary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}
