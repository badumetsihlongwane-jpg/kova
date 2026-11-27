package com.kova.workstation.ui.stage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kova.workstation.ui.theme.KovaPalette

/**
 * Slim frosted-glass inline keyboard bar docked directly BELOW the live Stage viewport
 * (inside a `Column(imePadding())`), so Android's keyboard NEVER covers the bottom of Windows!
 *
 * Includes:
 * - 1-tap `Live` (real-time streaming + Gboard autocorrect diff) vs `Draft` mode
 * - 1-tap `Focus` (pans tapped/bottom input cleanly above keyboard at full width) vs `Fit` (full screen above keyboard)
 */
@Composable
fun LiveKeyboardBar(
    hapticsEnabled: Boolean,
    fitWholeScreenAboveIme: Boolean,
    onToggleFitAboveIme: () -> Unit,
    onImePatch: (backspaces: Int, text: String) -> Unit,
    onSendKeys: (List<String>) -> Unit,
    onDispatchDraft: (text: String, pressEnter: Boolean, mode: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    var liveMode by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf("") }
    var lastSyncedText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    val glassBg = Brush.verticalGradient(
        listOf(Color(0xEA172236), Color(0xF40B1220))
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 5.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        border = BorderStroke(
            1.dp,
            if (liveMode) KovaPalette.Cyan.copy(alpha = 0.55f) else KovaPalette.Emerald.copy(alpha = 0.55f)
        ),
        shadowElevation = 10.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(glassBg)
                .padding(horizontal = 7.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            // 1-tap Live ⚡ / Draft mode switch
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (liveMode) KovaPalette.Cyan.copy(alpha = 0.18f) else KovaPalette.Emerald.copy(alpha = 0.18f),
                modifier = Modifier.clickable {
                    if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    liveMode = !liveMode
                    text = ""
                    lastSyncedText = ""
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (liveMode) Icons.Rounded.Bolt else Icons.Rounded.EditNote,
                        contentDescription = if (liveMode) "Live typing mode" else "Draft mode",
                        tint = if (liveMode) KovaPalette.Cyan else KovaPalette.Emerald,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = if (liveMode) "Live" else "Draft",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (liveMode) KovaPalette.Cyan else KovaPalette.Emerald
                    )
                }
            }

            // 1-tap Focus-Input vs Fit-Whole-Screen above keyboard
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable {
                        if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onToggleFitAboveIme()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (fitWholeScreenAboveIme) Icons.Rounded.FitScreen else Icons.Rounded.VerticalAlignBottom,
                    contentDescription = if (fitWholeScreenAboveIme) "Fit whole screen above keyboard" else "Focus input above keyboard",
                    tint = KovaPalette.Amber,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Compact inline text input
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 34.dp, max = 56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0E1729))
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = if (liveMode) "Type live..." else "Draft message...",
                        style = MaterialTheme.typography.bodySmall,
                        color = KovaPalette.InkMuted,
                        maxLines = 1
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { updated ->
                        if (liveMode) {
                            if (updated.endsWith("\n")) {
                                val stripped = updated.dropLast(1)
                                val patch = computeLiveImeDiff(lastSyncedText, stripped)
                                if (!patch.isEmpty) {
                                    onImePatch(patch.backspaces, patch.insertText)
                                }
                                onSendKeys(listOf("enter"))
                                text = ""
                                lastSyncedText = ""
                            } else {
                                val patch = computeLiveImeDiff(lastSyncedText, updated)
                                if (!patch.isEmpty) {
                                    onImePatch(patch.backspaces, patch.insertText)
                                }
                                text = updated
                                lastSyncedText = updated
                            }
                        } else {
                            text = updated
                        }
                    },
                    textStyle = TextStyle(
                        color = KovaPalette.InkPrimary,
                        fontSize = 14.sp
                    ),
                    cursorBrush = SolidColor(KovaPalette.Cyan),
                    maxLines = 2,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (liveMode) {
                                onSendKeys(listOf("enter"))
                                text = ""
                                lastSyncedText = ""
                            } else if (text.isNotEmpty()) {
                                onDispatchDraft(text, true, "paste")
                                text = ""
                            }
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            }

            if (liveMode) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable {
                            if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (text.isNotEmpty()) {
                                text = text.dropLast(1)
                                lastSyncedText = text
                            }
                            onSendKeys(listOf("backspace"))
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Backspace, "Backspace", tint = KovaPalette.InkSecondary, modifier = Modifier.size(15.dp))
                }

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(KovaPalette.Cyan.copy(alpha = 0.24f))
                        .clickable {
                            if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSendKeys(listOf("enter"))
                            text = ""
                            lastSyncedText = ""
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.KeyboardReturn, "Enter", tint = KovaPalette.Cyan, modifier = Modifier.size(16.dp))
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable(enabled = text.isNotEmpty()) {
                            if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDispatchDraft(text, false, "paste")
                            text = ""
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Send,
                        "Inject text",
                        tint = if (text.isNotEmpty()) KovaPalette.Emerald else KovaPalette.InkMuted,
                        modifier = Modifier.size(15.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (text.isNotEmpty()) KovaPalette.Emerald else Color.White.copy(alpha = 0.08f))
                        .clickable(enabled = text.isNotEmpty()) {
                            if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDispatchDraft(text, true, "paste")
                            text = ""
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.KeyboardReturn,
                        "Send + Enter",
                        tint = if (text.isNotEmpty()) KovaPalette.Obsidian else KovaPalette.InkMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable {
                        keyboardController?.hide()
                        onDismiss()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Close, "Close Keyboard", tint = KovaPalette.InkSecondary, modifier = Modifier.size(16.dp))
            }
        }
    }
}
