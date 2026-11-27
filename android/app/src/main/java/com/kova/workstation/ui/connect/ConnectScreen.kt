package com.kova.workstation.ui.connect

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kova.workstation.data.model.WorkstationProfile
import com.kova.workstation.data.network.PairingUriParser
import com.kova.workstation.ui.WorkstationUiState
import com.kova.workstation.ui.theme.KovaPalette

@Composable
fun ConnectScreen(
    state: WorkstationUiState,
    onConnect: (WorkstationProfile) -> Unit,
    onSaveAndConnect: (WorkstationProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onPairingUri: (String) -> Unit
) {
    val context = LocalContext.current
    var showQr by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(state.profiles.isEmpty()) }

    var name by remember { mutableStateOf("Windows VPS") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8765") }
    var token by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var tlsEnabled by remember { mutableStateOf(true) }

    if (showQr) {
        QrScannerDialog(
            onResult = { uri ->
                showQr = false
                val parsed = PairingUriParser.parse(uri)
                if (parsed != null) {
                    onPairingUri(uri)
                } else {
                    host = uri
                }
            },
            onDismiss = { showQr = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF0E1D34), Color(0xFF0B1324), Color(0xFF10243D))
                    )
                )
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(KovaPalette.Cyan.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Terminal,
                            contentDescription = null,
                            tint = KovaPalette.Cyan,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "KOVA",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp
                            ),
                            color = KovaPalette.InkPrimary
                        )
                        Text(
                            text = "Mobile-First Semantic Windows Workstation",
                            style = MaterialTheme.typography.bodySmall,
                            color = KovaPalette.Cyan
                        )
                    }
                }
                Text(
                    text = "Control desktop apps via UI Automation X-Ray & Mobile-Fit reflow, run native ConPTY terminals, and edit code & Git—crafted for one-thumb use.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KovaPalette.InkSecondary
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { showQr = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Rounded.QrCodeScanner, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Scan QR")
                    }
                    FilledTonalButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clip = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                            if (clip.startsWith("kova://", ignoreCase = true)) {
                                onPairingUri(clip)
                            } else {
                                val parsed = PairingUriParser.parse(clip)
                                if (parsed != null) onPairingUri(clip)
                                else host = clip.trim()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Rounded.ContentPaste, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Paste URI")
                    }
                }
            }
        }

        // Error Banner
        if (!state.connectionError.isNullOrBlank()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = KovaPalette.Rose.copy(alpha = 0.14f)),
                border = BorderStroke(1.dp, KovaPalette.Rose.copy(alpha = 0.45f)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = state.connectionError,
                    color = KovaPalette.Rose,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }

        // Saved Workstations
        if (state.profiles.isNotEmpty()) {
            Text(
                text = "Saved Workstations",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.profiles.forEach { profile ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !state.connecting) { onConnect(profile) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(KovaPalette.Cyan.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Computer, null, tint = KovaPalette.Cyan)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(profile.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "${if (profile.tlsEnabled) "wss" else "ws"}://${profile.host}:${profile.port}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (profile.tlsEnabled && profile.certFingerprint.isNotBlank()) {
                                Icon(
                                    Icons.Rounded.Lock,
                                    contentDescription = "TLS Pinned",
                                    tint = KovaPalette.Emerald,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            IconButton(onClick = {
                                name = profile.name
                                host = profile.host
                                port = profile.port.toString()
                                token = profile.token
                                fingerprint = profile.certFingerprint
                                tlsEnabled = profile.tlsEnabled
                            }) {
                                Icon(
                                    Icons.Rounded.Edit,
                                    contentDescription = "Edit",
                                    tint = KovaPalette.Cyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(onClick = { onDeleteProfile(profile.id) }) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // Manual Connection Form
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Add Windows Host Manually", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Workstation Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { raw ->
                            val clean = raw.trim()
                                .removePrefix("https://")
                                .removePrefix("http://")
                                .removePrefix("wss://")
                                .removePrefix("ws://")
                                .substringBefore("/")
                            if (clean.contains(":") && !clean.contains("[")) {
                                val parts = clean.split(":")
                                host = parts[0]
                                parts.getOrNull(1)?.filter(Char::isDigit)?.takeIf { it.isNotEmpty() }?.let { port = it }
                            } else {
                                host = clean
                            }
                        },
                        label = { Text("Host / Tailscale IP / Domain") },
                        placeholder = { Text("100.x.y.z or vps.domain.com") },
                        singleLine = true,
                        modifier = Modifier.weight(2f)
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit) },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.trim() },
                    label = { Text("Host Token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = fingerprint,
                    onValueChange = { fingerprint = it.trim().uppercase() },
                    label = { Text("TLS SHA-256 Pin (optional for CA domains)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Use TLS Encryption (HTTPS/WSS)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Keep ON for public VPS. Optional inside Tailscale.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = tlsEnabled, onCheckedChange = { tlsEnabled = it })
                }

                Button(
                    onClick = {
                        val p = WorkstationProfile(
                            name = name.ifBlank { host },
                            host = host.trim(),
                            port = port.toIntOrNull() ?: 8765,
                            token = token.trim(),
                            certFingerprint = fingerprint.trim(),
                            tlsEnabled = tlsEnabled
                        )
                        onSaveAndConnect(p)
                    },
                    enabled = host.isNotBlank() && token.isNotBlank() && !state.connecting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (state.connecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Connecting...")
                    } else {
                        Icon(Icons.Rounded.RocketLaunch, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Connect Workstation")
                    }
                }
            }
        }

        // Built-in Windows VPS Setup Guide
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showGuide = !showGuide },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("2-Minute Windows VPS Setup Guide", style = MaterialTheme.typography.titleSmall)
                    Icon(
                        if (showGuide) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null
                    )
                }
                AnimatedVisibility(visible = showGuide) {
                    Column(
                        modifier = Modifier.padding(top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "1. Extract kova-host-windows-x64.zip on your Windows VPS and double-click KovaHost.exe — a Control Center window opens immediately with a large scannable QR code.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "2. Recommended: install Tailscale on both your VPS and Android phone (zero port forwarding), or click 'Open Firewall Port 8765' inside KovaHost.exe for direct VPS IP access.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "3. Tap Scan QR above (or click Copy Pairing Link in KovaHost.exe and tap Paste URI).",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
