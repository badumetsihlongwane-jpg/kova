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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.Send
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
 * Slim, non-blocking inline keyboard bar anchored flush above Gboard via `imePadding()`.
 *
 * ZERO dark modal scrim and ZERO bottom-sheet popup covering the Windows screen:
 * the entire live Windows desktop above the keyboard stays 100% visible, bright, and tappable
 * while you type, swipe, or voice-dictate!
 *
 * Supports two 1-tap modes:
 * - Live (Bolt): streams typed/swiped words, autocorrect patches, Backspace, and Enter
 *   directly into the focused Windows app in real time.
 * - Draft (EditNote): compose locally in the slim bar while watching Windows above, then
 *   inject (`→`) or submit (`↵`).
 */
@Composable
fun LiveKeyboardBar(
    hapticsEnabled: Boolean,
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

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = KovaPalette.SlateDeep.copy(alpha = 0.94f),
        border = BorderStroke(
            1.dp,
            if (liveMode) KovaPalette.Cyan.copy(alpha = 0.7f) else KovaPalette.Emerald.copy(alpha = 0.7f)
        ),
        shadowElevation = 10.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 1-tap Live ⚡ / Draft mode switch
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (liveMode) KovaPalette.Cyan.copy(alpha = 0.18f) else KovaPalette.Emerald.copy(alpha = 0.18f),
                modifier = Modifier.clickable {
                    if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    liveMode = !liveMode
                    // Reset sync buffer cleanly when switching modes so nothing double-types
                    text = ""
                    lastSyncedText = ""
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (liveMode) Icons.Rounded.Bolt else Icons.Rounded.EditNote,
                        contentDescription = if (liveMode) "Live typing mode" else "Draft mode",
                        tint = if (liveMode) KovaPalette.Cyan else KovaPalette.Emerald,
                        modifier = Modifier.size(15.dp)
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

            // Ultra-compact inline text field (max 2 lines so it NEVER blocks Windows!)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 34.dp, max = 58.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(KovaPalette.SlateCard)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = if (liveMode) "Type live into Windows..." else "Draft message, then tap send...",
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
                    keyboardOptions = KeyboardOptions(
                        imeAction = if (liveMode) ImeAction.Send else ImeAction.Send
                    ),
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
                // Dedicated Backspace (works even when local bar is empty!)
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(KovaPalette.SlateCard)
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
                    Icon(Icons.Rounded.Backspace, "Backspace", tint = KovaPalette.InkSecondary, modifier = Modifier.size(16.dp))
                }

                // Direct Enter
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(KovaPalette.Cyan.copy(alpha = 0.22f))
                        .clickable {
                            if (hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSendKeys(listOf("enter"))
                            text = ""
                            lastSyncedText = ""
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.KeyboardReturn, "Enter", tint = KovaPalette.Cyan, modifier = Modifier.size(17.dp))
                }
            } else {
                // Draft Inject (without Enter)
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(KovaPalette.SlateCard)
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
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Draft Send + Enter
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (text.isNotEmpty()) KovaPalette.Emerald else KovaPalette.SlateCard)
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
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            // Dismiss keyboard bar
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable {
                        keyboardController?.hide()
                        onDismiss()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Close, "Close Keyboard", tint = KovaPalette.InkSecondary, modifier = Modifier.size(17.dp))
            }
        }
    }
}
