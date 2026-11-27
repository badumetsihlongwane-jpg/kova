package com.kova.workstation.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SettingsApplications
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kova.workstation.BuildConfig
import com.kova.workstation.data.model.AppPreferences
import com.kova.workstation.ui.StageInputMode
import com.kova.workstation.ui.WorkstationUiState
import com.kova.workstation.ui.stage.PointerCurve
import com.kova.workstation.ui.theme.KovaPalette
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: WorkstationUiState,
    onUpdatePrefs: ((AppPreferences) -> AppPreferences) -> Unit,
    onPingNow: () -> Unit,
    onReconnectNow: () -> Unit,
    onActivateDisplay: () -> Unit,
    onOpenRemoteWindowsSettings: () -> Unit,
    onResetDefaults: () -> Unit,
    onDisconnect: () -> Unit
) {
    val prefs = state.preferences
    val info = state.systemInfo

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Kova Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        // 1. Live Connection & Host Diagnostics
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(state.activeProfile?.name ?: "Disconnected", style = MaterialTheme.typography.titleMedium)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (state.connected) KovaPalette.Emerald.copy(alpha = 0.16f) else KovaPalette.Amber.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = if (state.connected) (state.latencyMs?.let { "Live · ${it}ms" } ?: "Connected") else "Offline",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.connected) KovaPalette.Emerald else KovaPalette.Amber,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Text(
                    text = state.activeProfile?.baseHttpUrl.orEmpty(),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = KovaPalette.Cyan
                )
                val fp = state.activeProfile?.certFingerprint.orEmpty()
                if (fp.isNotBlank()) {
                    Text(
                        text = "TLS Pin: ${fp.take(16)}...${fp.takeLast(12)}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = KovaPalette.Emerald
                    )
                }

                Spacer(Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = onPingNow,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Rounded.NetworkCheck, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Test Ping", maxLines = 1)
                    }
                    FilledTonalButton(
                        onClick = onReconnectNow,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Reconnect", maxLines = 1)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onActivateDisplay,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Attach Display", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onOpenRemoteWindowsSettings,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Rounded.SettingsApplications, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Windows Settings", maxLines = 1)
                    }
                }

                OutlinedButton(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = KovaPalette.Rose),
                    border = BorderStroke(1.dp, KovaPalette.Rose.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Disconnect & Switch Workstation")
                }
            }
        }

        // 2. Touch & Precision Pointer Engine
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Touch & Precision Pointer", style = MaterialTheme.typography.titleMedium)

                Text("Default Stage Input Mode", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StageInputMode.entries.forEach { mode ->
                        FilterChip(
                            selected = prefs.defaultInputMode.equals(mode.id, ignoreCase = true),
                            onClick = { onUpdatePrefs { it.copy(defaultInputMode = mode.id) } },
                            label = { Text(mode.label) }
                        )
                    }
                }

                Text("Trackpad Velocity Curve", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PointerCurve.entries.forEach { curve ->
                        FilterChip(
                            selected = prefs.pointerCurve.equals(curve.id, ignoreCase = true),
                            onClick = { onUpdatePrefs { it.copy(pointerCurve = curve.id) } },
                            label = { Text(curve.label) }
                        )
                    }
                }

                LabeledSlider(
                    label = "Trackpad Speed",
                    valueText = "%.2f×".format(prefs.touchpadSensitivity),
                    value = prefs.touchpadSensitivity,
                    range = 0.6f..2.8f,
                    onValueChange = { v -> onUpdatePrefs { it.copy(touchpadSensitivity = v) } }
                )

                LabeledSlider(
                    label = "Scroll Speed",
                    valueText = "%.1f×".format(prefs.scrollSensitivity),
                    value = prefs.scrollSensitivity,
                    range = 0.5f..2.5f,
                    onValueChange = { v -> onUpdatePrefs { it.copy(scrollSensitivity = v) } }
                )

                LabeledSlider(
                    label = "Touch Long-Press Delay (Right-Click / Drag)",
                    valueText = "${prefs.longPressMs}ms",
                    value = prefs.longPressMs.toFloat(),
                    range = 260f..650f,
                    onValueChange = { v -> onUpdatePrefs { it.copy(longPressMs = (v / 10f).roundToInt() * 10) } }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

                ToggleRow(
                    title = "Natural Mobile Scrolling",
                    subtitle = "Drag finger up to move down the page, matching Android touch.",
                    checked = prefs.naturalScroll,
                    onCheckedChange = { c -> onUpdatePrefs { it.copy(naturalScroll = c) } }
                )
                ToggleRow(
                    title = "Auto-Offer Keyboard on Text Fields",
                    subtitle = "Shows a 1-tap typing pill whenever you touch an editable Windows input.",
                    checked = prefs.autoPromptOnTextInput,
                    onCheckedChange = { c -> onUpdatePrefs { it.copy(autoPromptOnTextInput = c) } }
                )
                ToggleRow(
                    title = "Show Virtual Cursor Reticle",
                    subtitle = "Renders the crisp live cursor ring in Trackpad & Drag modes.",
                    checked = prefs.showCursorReticle,
                    onCheckedChange = { c -> onUpdatePrefs { it.copy(showCursorReticle = c) } }
                )
            }
        }

        // 3. Visual Stage Display & Streaming
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Display & Streaming", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = prefs.streamFps <= 8 && prefs.streamQuality <= 68,
                        onClick = { onUpdatePrefs { it.copy(streamFps = 8, streamQuality = 65, streamMaxWidth = 720) } },
                        label = { Text("Cellular Saver") }
                    )
                    FilterChip(
                        selected = prefs.streamFps in 9..15 && prefs.streamMaxWidth in 840..1080,
                        onClick = { onUpdatePrefs { it.copy(streamFps = 12, streamQuality = 78, streamMaxWidth = 960) } },
                        label = { Text("Balanced") }
                    )
                    FilterChip(
                        selected = prefs.streamFps >= 18 || prefs.streamMaxWidth >= 1200,
                        onClick = { onUpdatePrefs { it.copy(streamFps = 20, streamQuality = 88, streamMaxWidth = 1280) } },
                        label = { Text("Crisp Wi-Fi") }
                    )
                }

                LabeledSlider(
                    label = "Target Frame Rate",
                    valueText = "${prefs.streamFps} FPS",
                    value = prefs.streamFps.toFloat(),
                    range = 4f..24f,
                    onValueChange = { v -> onUpdatePrefs { it.copy(streamFps = v.roundToInt()) } }
                )
                LabeledSlider(
                    label = "WebP Sharpness",
                    valueText = "${prefs.streamQuality}%",
                    value = prefs.streamQuality.toFloat(),
                    range = 45f..95f,
                    onValueChange = { v -> onUpdatePrefs { it.copy(streamQuality = v.roundToInt()) } }
                )
                LabeledSlider(
                    label = "Max Stream Width",
                    valueText = "${prefs.streamMaxWidth}px",
                    value = prefs.streamMaxWidth.toFloat(),
                    range = 640f..1440f,
                    onValueChange = { v -> onUpdatePrefs { it.copy(streamMaxWidth = ((v / 64f).roundToInt() * 64).coerceIn(640, 1440)) } }
                )
            }
        }

        // 4. Typography, Experience & Reliability
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Experience & Reliability", style = MaterialTheme.typography.titleMedium)

                LabeledSlider(
                    label = "Terminal Font Size",
                    valueText = "%.1f sp".format(prefs.terminalFontSp),
                    value = prefs.terminalFontSp,
                    range = 9f..20f,
                    onValueChange = { v -> onUpdatePrefs { it.copy(terminalFontSp = (v * 2f).roundToInt() / 2f) } }
                )
                LabeledSlider(
                    label = "Code Editor Font Size",
                    valueText = "%.1f sp".format(prefs.editorFontSp),
                    value = prefs.editorFontSp,
                    range = 10f..22f,
                    onValueChange = { v -> onUpdatePrefs { it.copy(editorFontSp = (v * 2f).roundToInt() / 2f) } }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

                ToggleRow(
                    title = "Tactile Haptic Feedback",
                    subtitle = "Subtle vibration ticks on taps, long-press holds, and shortcuts.",
                    checked = prefs.hapticsEnabled,
                    onCheckedChange = { c -> onUpdatePrefs { it.copy(hapticsEnabled = c) } }
                )
                ToggleRow(
                    title = "Auto-Sync Windows Clipboard",
                    subtitle = "Automatically mirror newly copied Windows text to Android.",
                    checked = prefs.autoClipboardSync,
                    onCheckedChange = { c -> onUpdatePrefs { it.copy(autoClipboardSync = c) } }
                )
                ToggleRow(
                    title = "Background Keep-Alive Service",
                    subtitle = "Starts or stops the foreground notification service immediately.",
                    checked = prefs.keepAliveService,
                    onCheckedChange = { c -> onUpdatePrefs { it.copy(keepAliveService = c) } }
                )
                ToggleRow(
                    title = "Obsidian Dark Theme",
                    subtitle = "High-contrast dark cockpit optimized for OLED screens.",
                    checked = prefs.darkTheme,
                    onCheckedChange = { c -> onUpdatePrefs { it.copy(darkTheme = c) } }
                )
            }
        }

        // 5. About & Reset
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DesktopWindows, null, tint = KovaPalette.Cyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Kova Android v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                }
                if (info != null) {
                    Text(
                        text = buildString {
                            append("Host v${info.version.ifBlank { "2.0.0" }} · ${info.os}")
                            append("\nDisplay ${info.screen.virtualWidth}×${info.screen.virtualHeight}")
                            append(if (info.session.isRdp) " · RDP Session #${info.session.sessionId}" else " · Console Session #${info.session.sessionId}")
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = onResetDefaults,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.RestartAlt, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Restore Default Settings")
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(valueText, style = MaterialTheme.typography.labelSmall, color = KovaPalette.Cyan)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
