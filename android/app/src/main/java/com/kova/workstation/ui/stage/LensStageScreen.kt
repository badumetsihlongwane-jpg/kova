package com.kova.workstation.ui.stage

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kova.workstation.data.model.UiaElement
import com.kova.workstation.ui.StageInputMode
import com.kova.workstation.ui.StageRoi
import com.kova.workstation.ui.WorkstationUiState
import com.kova.workstation.ui.theme.KovaPalette
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun LensStageScreen(
    state: WorkstationUiState,
    onSelectSource: (String, Long?) -> Unit,
    onWindowAction: (Long, String) -> Unit,
    onModeChange: (StageInputMode) -> Unit,
    onSetRoi: (StageRoi?) -> Unit,
    onMouseMoveAbs: (Int, Int) -> Unit,
    onMouseMoveRel: (Int, Int) -> Unit,
    onMouseClick: (Int?, Int?, String, Int) -> Unit,
    onScroll: (Int, Int) -> Unit,
    onToggleDragLock: () -> Unit,
    onToggleXRay: () -> Unit,
    onOpenControls: () -> Unit,
    onOpenComposer: () -> Unit,
    onSendKeys: (List<String>) -> Unit,
    onInvokeUia: (UiaElement, String, String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var showWindowMenu by remember { mutableStateOf(false) }

    val activeWindow = state.windows.firstOrNull { it.hwnd == state.stageHwnd }
    val sourceLabel = when {
        state.stageMode == "window" && activeWindow != null -> activeWindow.title
        state.stageMode == "window" -> "Focused Window"
        else -> "Full Desktop"
    }

    // Loupe state (pixel-precision magnifier floating above user's thumb)
    var loupeActive by remember { mutableStateOf(false) }
    var loupeNormX by remember { mutableFloatStateOf(0.5f) }
    var loupeNormY by remember { mutableFloatStateOf(0.5f) }
    var loupeTouchPx by remember { mutableStateOf(Offset.Zero) }
    var lastTapMs by remember { mutableStateOf(0L) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KovaPalette.Obsidian)
    ) {
        // 1. Compact Stage Control Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showWindowMenu = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.DesktopWindows,
                            null,
                            tint = KovaPalette.Cyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = sourceLabel,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.Rounded.ExpandMore, null, modifier = Modifier.size(16.dp))
                    }
                }
                DropdownMenu(
                    expanded = showWindowMenu,
                    onDismissRequest = { showWindowMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Full Desktop") },
                        onClick = {
                            showWindowMenu = false
                            onSelectSource("desktop", null)
                        }
                    )
                    state.windows.forEach { w ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "${w.title} (${w.process.removeSuffix(".exe")})",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            onClick = {
                                showWindowMenu = false
                                onSelectSource("window", w.hwnd)
                            }
                        )
                    }
                }
            }

            // Mobile-Fit one-tap reflow button when staging a window
            if (state.stageMode == "window" && state.stageHwnd != null) {
                val isFit = activeWindow?.mobileFit == true
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isFit) KovaPalette.Emerald.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, if (isFit) KovaPalette.Emerald else MaterialTheme.colorScheme.outline),
                    modifier = Modifier.clickable {
                        onWindowAction(state.stageHwnd, if (isFit) "restore_fit" else "mobile_fit")
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.PhoneAndroid,
                            null,
                            tint = if (isFit) KovaPalette.Emerald else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (isFit) "Fit" else "Reflow",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isFit) KovaPalette.Emerald else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // Reset server-side ROI zoom button when zoomed
            if (state.stageRoi != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = KovaPalette.Cyan.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, KovaPalette.Cyan),
                    modifier = Modifier.clickable { onSetRoi(null) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.ZoomOutMap, null, tint = KovaPalette.Cyan, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("1×", style = MaterialTheme.typography.labelSmall, color = KovaPalette.Cyan)
                    }
                }
            }

            // Semantic X-Ray Overlay Toggle
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (state.stageXRayEnabled) KovaPalette.Amber.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, if (state.stageXRayEnabled) KovaPalette.Amber else MaterialTheme.colorScheme.outline),
                modifier = Modifier.clickable { onToggleXRay() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.CenterFocusStrong,
                        null,
                        tint = if (state.stageXRayEnabled) KovaPalette.Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "X-Ray",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.stageXRayEnabled) KovaPalette.Amber else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // 2. Main Visual Viewport + Gestures + X-Ray Overlay + Loupe
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val boxW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val boxH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            val bmp = state.stageBitmap
            val meta = state.stageMeta
            val density = LocalDensity.current

            if (bmp == null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(color = KovaPalette.Cyan)
                    Text(
                        "Streaming live Windows stage...",
                        style = MaterialTheme.typography.bodySmall,
                        color = KovaPalette.InkSecondary
                    )
                }
            } else {
                // Compute exact fitted image rectangle inside the viewport for pixel-perfect coordinate mapping
                val imgAspect = bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f)
                val boxAspect = boxW / boxH
                val fittedW: Float
                val fittedH: Float
                if (imgAspect > boxAspect) {
                    fittedW = boxW
                    fittedH = boxW / imgAspect
                } else {
                    fittedH = boxH
                    fittedW = boxH * imgAspect
                }
                val offsetX = (boxW - fittedW) / 2f
                val offsetY = (boxH - fittedH) / 2f

                fun viewportToScreen(px: Float, py: Float): Pair<Int, Int> {
                    val nx = ((px - offsetX) / fittedW).coerceIn(0f, 1f)
                    val ny = ((py - offsetY) / fittedH).coerceIn(0f, 1f)
                    val sx = meta.originX + (nx * meta.sourceWidth).roundToInt()
                    val sy = meta.originY + (ny * meta.sourceHeight).roundToInt()
                    return sx to sy
                }

                val imageBitmap = remember(bmp) { bmp.asImageBitmap() }
                Image(
                    bitmap = imageBitmap,
                    contentDescription = "Windows Live Stage",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(
                            state.stageInputMode,
                            state.preferences.touchpadSensitivity,
                            meta.originX,
                            meta.originY,
                            meta.sourceWidth,
                            meta.sourceHeight,
                            state.stageRoi
                        ) {
                            awaitEachGesture {
                                val firstDown = awaitFirstDown(requireUnconsumed = false)
                                val downTime = System.currentTimeMillis()
                                val startPos = firstDown.position
                                var totalDrag = 0f
                                var maxPointers = 1
                                var lastPos = startPos
                                var scrollAccumX = 0f
                                var scrollAccumY = 0f
                                var pinchAccum = 1f

                                if (state.stageInputMode == StageInputMode.LOUPE) {
                                    loupeActive = true
                                    loupeTouchPx = startPos
                                    loupeNormX = ((startPos.x - offsetX) / fittedW).coerceIn(0f, 1f)
                                    loupeNormY = ((startPos.y - offsetY) / fittedH).coerceIn(0f, 1f)
                                }

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val active = event.changes.filter { it.pressed }
                                    if (active.size > maxPointers) maxPointers = active.size
                                    if (active.isEmpty()) break

                                    if (active.size >= 2) {
                                        val zoom = event.calculateZoom()
                                        pinchAccum *= zoom
                                        val avgDelta = active.fold(Offset.Zero) { acc, c ->
                                            acc + (c.position - c.previousPosition)
                                        } / active.size.toFloat()

                                        if (state.stageInputMode == StageInputMode.TRACKPAD) {
                                            scrollAccumY += avgDelta.y
                                            scrollAccumX += avgDelta.x
                                            if (abs(scrollAccumY) > 18f || abs(scrollAccumX) > 22f) {
                                                val wheelY = (scrollAccumY * 2.2f).roundToInt()
                                                val wheelX = (-scrollAccumX * 2.0f).roundToInt()
                                                onScroll(wheelX, wheelY)
                                                scrollAccumY = 0f
                                                scrollAccumX = 0f
                                            }
                                        }
                                        totalDrag += avgDelta.getDistance()
                                    } else {
                                        val c = active.first()
                                        val delta = c.position - lastPos
                                        lastPos = c.position
                                        totalDrag += delta.getDistance()

                                        when (state.stageInputMode) {
                                            StageInputMode.TRACKPAD -> {
                                                val speed = delta.getDistance()
                                                val accel = if (speed > 18f) 1.5f else 1.0f
                                                val sens = state.preferences.touchpadSensitivity * accel
                                                val dx = (delta.x * sens).roundToInt()
                                                val dy = (delta.y * sens).roundToInt()
                                                if (dx != 0 || dy != 0) onMouseMoveRel(dx, dy)
                                            }
                                            StageInputMode.LOUPE -> {
                                                loupeTouchPx = c.position
                                                loupeNormX = ((c.position.x - offsetX) / fittedW).coerceIn(0f, 1f)
                                                loupeNormY = ((c.position.y - offsetY) / fittedH).coerceIn(0f, 1f)
                                                val (sx, sy) = viewportToScreen(c.position.x, c.position.y)
                                                onMouseMoveAbs(sx, sy)
                                            }
                                            StageInputMode.DIRECT -> Unit
                                        }
                                    }
                                    event.changes.forEach { it.consume() }
                                }

                                val elapsed = System.currentTimeMillis() - downTime
                                when (state.stageInputMode) {
                                    StageInputMode.TRACKPAD -> {
                                        if (totalDrag < 18f && elapsed < 320L) {
                                            if (state.preferences.hapticsEnabled) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                            if (maxPointers >= 2) {
                                                onMouseClick(null, null, "right", 1)
                                            } else {
                                                val now = System.currentTimeMillis()
                                                val isDouble = (now - lastTapMs) in 40L..320L
                                                lastTapMs = if (isDouble) 0L else now
                                                onMouseClick(null, null, "left", if (isDouble) 2 else 1)
                                            }
                                        }
                                    }
                                    StageInputMode.LOUPE -> {
                                        loupeActive = false
                                        val (sx, sy) = viewportToScreen(loupeTouchPx.x, loupeTouchPx.y)
                                        if (state.preferences.hapticsEnabled) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                        onMouseClick(sx, sy, "left", 1)
                                    }
                                    StageInputMode.DIRECT -> {
                                        if (maxPointers >= 2 && (pinchAccum > 1.25f || pinchAccum < 0.8f)) {
                                            // Trigger server-side crisp ROI zoom!
                                            val curRoi = state.stageRoi ?: StageRoi(0f, 0f, 1f, 1f)
                                            val newW = (curRoi.w / pinchAccum).coerceIn(0.28f, 1f)
                                            val newH = (curRoi.h / pinchAccum).coerceIn(0.28f, 1f)
                                            if (newW >= 0.96f) {
                                                onSetRoi(null)
                                            } else {
                                                val cx = curRoi.x + curRoi.w / 2f
                                                val cy = curRoi.y + curRoi.h / 2f
                                                val nx = (cx - newW / 2f).coerceIn(0f, 1f - newW)
                                                val ny = (cy - newH / 2f).coerceIn(0f, 1f - newH)
                                                onSetRoi(StageRoi(nx, ny, newW, newH))
                                            }
                                        } else if (totalDrag < 20f && elapsed < 360L) {
                                            val (sx, sy) = viewportToScreen(startPos.x, startPos.y)
                                            if (state.preferences.hapticsEnabled) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                            onMouseClick(sx, sy, if (maxPointers >= 2) "right" else "left", 1)
                                        } else if (maxPointers == 1 && totalDrag > 24f) {
                                            // Pan ROI smoothly across high-DPI desktop
                                            val cur = state.stageRoi
                                            if (cur != null) {
                                                val delta = lastPos - startPos
                                                val shiftX = -(delta.x / fittedW) * cur.w
                                                val shiftY = -(delta.y / fittedH) * cur.h
                                                val nx = (cur.x + shiftX).coerceIn(0f, 1f - cur.w)
                                                val ny = (cur.y + shiftY).coerceIn(0f, 1f - cur.h)
                                                onSetRoi(StageRoi(nx, ny, cur.w, cur.h))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                )

                // Semantic X-Ray Hit-Target Chips overlaid directly on live Windows controls!
                if (state.stageXRayEnabled && state.uiaData != null) {
                    val elements = state.uiaData.elements.filter { it.enabled }.take(40)
                    elements.forEach { el ->
                        val relX = (el.centerX - meta.originX).toFloat() / meta.sourceWidth.coerceAtLeast(1)
                        val relY = (el.centerY - meta.originY).toFloat() / meta.sourceHeight.coerceAtLeast(1)
                        if (relX in 0.01f..0.99f && relY in 0.01f..0.99f) {
                            val chipX = (offsetX + relX * fittedW).roundToInt()
                            val chipY = (offsetY + relY * fittedH).roundToInt()
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (el.isEditable) KovaPalette.Emerald.copy(alpha = 0.88f)
                                else KovaPalette.Amber.copy(alpha = 0.9f),
                                shadowElevation = 4.dp,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset { IntOffset(chipX - 28, chipY - 14) }
                                    .clickable {
                                        if (state.preferences.hapticsEnabled) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                        if (el.isEditable) onOpenControls()
                                        else onInvokeUia(el, "invoke", "")
                                    }
                            ) {
                                Text(
                                    text = "${el.id} ${el.name.take(10)}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = KovaPalette.Obsidian,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                // Live 120Hz Virtual Cursor Indicator in Trackpad mode (crucial for Single-Window & high-latency networks!)
                if (state.stageInputMode == StageInputMode.TRACKPAD && meta.sourceWidth > 0 && meta.sourceHeight > 0) {
                    val curRelX = (meta.cursorX - meta.originX).toFloat() / meta.sourceWidth.toFloat()
                    val curRelY = (meta.cursorY - meta.originY).toFloat() / meta.sourceHeight.toFloat()
                    if (curRelX in 0f..1f && curRelY in 0f..1f) {
                        val cpx = (offsetX + curRelX * fittedW).roundToInt()
                        val cpy = (offsetY + curRelY * fittedH).roundToInt()
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset { IntOffset(cpx - 10, cpy - 10) }
                                .size(20.dp)
                                .clip(CircleShape)
                                .border(2.dp, KovaPalette.Cyan, CircleShape)
                                .background(KovaPalette.Cyan.copy(alpha = 0.22f))
                        )
                    }
                }

                // Precision 2.4x Magnifying Loupe floating cleanly above the user's thumb!
                if (loupeActive) {
                    val loupeSizePx = with(density) { 126.dp.toPx() }.roundToInt()
                    val lx = (loupeTouchPx.x - loupeSizePx / 2f).coerceIn(12f, boxW - loupeSizePx - 12f).roundToInt()
                    val ly = (loupeTouchPx.y - loupeSizePx - 74f).coerceAtLeast(12f).roundToInt()
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset { IntOffset(lx, ly) }
                            .size(126.dp)
                            .clip(CircleShape)
                            .border(2.5.dp, KovaPalette.Cyan, CircleShape)
                            .background(Color.Black)
                    ) {
                        LoupeCanvas(bitmap = bmp, normX = loupeNormX, normY = loupeNormY)
                    }
                }
            }

            // Live frame latency / dimensions pill + One-Thumb Quick Scroll pills
            if (bmp != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = KovaPalette.Obsidian.copy(alpha = 0.72f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "${meta.frameWidth}×${meta.frameHeight} · ${meta.captureMs.roundToInt()}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = KovaPalette.InkSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = KovaPalette.SlateCard.copy(alpha = 0.82f),
                        border = BorderStroke(1.dp, KovaPalette.BorderDark),
                        modifier = Modifier
                            .size(36.dp)
                            .clickable { onScroll(0, 240) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.KeyboardArrowUp, "Scroll Up", tint = KovaPalette.Cyan, modifier = Modifier.size(20.dp))
                        }
                    }
                    Surface(
                        shape = CircleShape,
                        color = KovaPalette.SlateCard.copy(alpha = 0.82f),
                        border = BorderStroke(1.dp, KovaPalette.BorderDark),
                        modifier = Modifier
                            .size(36.dp)
                            .clickable { onScroll(0, -240) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.KeyboardArrowDown, "Scroll Down", tint = KovaPalette.Cyan, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            // Actionable VPS Blank-Session Warning
            if (!state.stageWarning.isNullOrBlank()) {
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                        .fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = KovaPalette.SlateCard.copy(alpha = 0.95f)),
                    border = BorderStroke(1.dp, KovaPalette.Amber)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(Icons.Rounded.WarningAmber, null, tint = KovaPalette.Amber, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = state.stageWarning,
                            style = MaterialTheme.typography.bodySmall,
                            color = KovaPalette.InkPrimary
                        )
                    }
                }
            }
        }

        // 3. Bottom One-Thumb Controls & Shortcut Chord Bar
        ModifierChordBar(
            inputMode = state.stageInputMode,
            dragLocked = state.stageDragLocked,
            hapticsEnabled = state.preferences.hapticsEnabled,
            onModeChange = onModeChange,
            onLeftClick = { onMouseClick(null, null, "left", 1) },
            onRightClick = { onMouseClick(null, null, "right", 1) },
            onToggleDragLock = onToggleDragLock,
            onOpenComposer = onOpenComposer,
            onOpenControls = onOpenControls,
            onSendKeys = onSendKeys
        )
    }
}

@Composable
private fun LoupeCanvas(bitmap: Bitmap, normX: Float, normY: Float) {
    val img = remember(bitmap) { bitmap.asImageBitmap() }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val sampleSize = (minOf(bitmap.width, bitmap.height) * 0.24f).roundToInt().coerceIn(48, 220)
        val cx = (normX * bitmap.width).roundToInt()
        val cy = (normY * bitmap.height).roundToInt()
        val srcX = (cx - sampleSize / 2).coerceIn(0, (bitmap.width - sampleSize).coerceAtLeast(0))
        val srcY = (cy - sampleSize / 2).coerceIn(0, (bitmap.height - sampleSize).coerceAtLeast(0))
        val actualW = sampleSize.coerceAtMost(bitmap.width - srcX).coerceAtLeast(1)
        val actualH = sampleSize.coerceAtMost(bitmap.height - srcY).coerceAtLeast(1)

        drawImage(
            image = img,
            srcOffset = IntOffset(srcX, srcY),
            srcSize = IntSize(actualW, actualH),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
        )

        // Precision cyan crosshair at exact target pixel
        val midX = size.width / 2f
        val midY = size.height / 2f
        drawLine(KovaPalette.Cyan, Offset(midX - 18f, midY), Offset(midX + 18f, midY), strokeWidth = 2.5f)
        drawLine(KovaPalette.Cyan, Offset(midX, midY - 18f), Offset(midX, midY + 18f), strokeWidth = 2.5f)
        drawCircle(KovaPalette.Cyan, radius = 6f, center = Offset(midX, midY), style = Stroke(width = 2f))
    }
}
