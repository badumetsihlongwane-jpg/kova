package com.kova.workstation.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kova.workstation.data.model.AppPreferences
import com.kova.workstation.ui.WorkstationUiState
import com.kova.workstation.ui.theme.KovaPalette
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    state: WorkstationUiState,
    onUpdatePrefs: ((AppPreferences) -> AppPreferences) -> Unit,
    onDisconnect: () -> Unit
) {
    val prefs = state.preferences

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Workstation Settings", style = MaterialTheme.typography.headlineSmall)

        // Connection Summary
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.activeProfile?.name ?: "Disconnected", style = MaterialTheme.typography.titleMedium)
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
                Spacer(Modifier.height(6.dp))
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

        // Visual Stage Stream Presets & Sliders
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Visual Stage Streaming", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = prefs.streamFps <= 8 && prefs.streamQuality <= 68,
                        onClick = { onUpdatePrefs { it.copy(streamFps = 8, streamQuality = 65, streamMaxWidth = 720) } },
                        label = { Text("Cellular Saver") }
                    )
                    FilterChip(
                        selected = prefs.streamFps in 9..15,
                        onClick = { onUpdatePrefs { it.copy(streamFps = 12, streamQuality = 78, streamMaxWidth = 960) } },
                        label = { Text("Balanced") }
                    )
                    FilterChip(
                        selected = prefs.streamFps >= 18,
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
                    label = "Touchpad Sensitivity",
                    valueText = "%.1f×".format(prefs.touchpadSensitivity),
                    value = prefs.touchpadSensitivity,
                    range = 0.8f..3.0f,
                    onValueChange = { v -> onUpdatePrefs { it.copy(touchpadSensitivity = v) } }
                )
            }
        }

        // Mobile Ergonomics & Experience Toggles
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Experience & Reliability", style = MaterialTheme.typography.titleMedium)

                ToggleRow(
                    title = "Tactile Haptic Feedback",
                    subtitle = "Subtle vibration ticks on clicks, keys, and X-Ray actions.",
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
                    subtitle = "Keeps terminal & workstation connections alive while switching apps.",
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
