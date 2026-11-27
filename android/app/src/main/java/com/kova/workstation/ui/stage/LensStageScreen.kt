package com.kova.workstation.ui.stage

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kova.workstation.data.model.StageFrameMeta
import com.kova.workstation.data.model.UiaElement
import com.kova.workstation.data.network.StageFrame
import com.kova.workstation.ui.StageInputMode
import com.kova.workstation.ui.StageRoi
import com.kova.workstation.ui.WorkstationUiState
import com.kova.workstation.ui.theme.KovaPalette
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private enum class TouchPhase {
    IDLE,
    PENDING,
    SCROLLING,
    HOLD_ARMED,
    DRAGGING,
    MULTI
}

private data class TouchRipple(
    val x: Float,
    val y: Float,
    val color: Color,
    val createdMs: Long = System.currentTimeMillis()
)

/**
 * Low-latency, edge-to-edge Windows Stage:
 * - Reads `stageFrameFlow` & `stageCursorFlow` locally so 24-30 FPS video & 60 Hz pointer moves
 *   NEVER trigger outer Scaffold/NavigationBar recompositions.
 * - True full-width & full-height fullscreen + optional Fit/Fill scaling.
 * - Collapsible HUD collapses ALL toolbars into one unobtrusive translucent corner orb.
 * - Non-blocking `LiveKeyboardBar` sits flush above Gboard with ZERO dark scrim so you can
 *   always see and interact with Windows while typing!
 */
@Composable
fun LensStageScreen(
    state: WorkstationUiState,
    stageFrameFlow: StateFlow<StageFrame?>,
    stageCursorFlow: StateFlow<Pair<Int, Int>>,
    onOpenSwitcher: () -> Unit,
    onSwitchAdjacentWindow: (Int) -> Unit,
    onSelectSource: (String, Long?) -> Unit,
    onWindowAction: (Long, String) -> Unit,
    onModeChange: (StageInputMode) -> Unit,
    onSetRoi: (StageRoi?) -> Unit,
    onSetFullscreen: (Boolean) -> Unit,
    onSetControlsCollapsed: (Boolean) -> Unit,
    onToggleFillScreen: () -> Unit,
    onPointerMoveAbs: (Int, Int, Long?) -> Unit,
    onPointerDown: (Int, Int, String) -> Unit,
    onPointerUp: (Int, Int, String) -> Unit,
    onMouseClick: (Int?, Int?, String, Int, Boolean) -> Unit,
    onScroll: (Int, Int, Int?, Int?) -> Unit,
    onToggleDragLock: () -> Unit,
    onToggleXRay: () -> Unit,
    onOpenControls: () -> Unit,
    onSetLiveKeyboardVisible: (Boolean) -> Unit,
    onImePatch: (Int, String) -> Unit,
    onDispatchDraft: (String, Boolean, String) -> Unit,
    onDismissFocusedHint: () -> Unit,
    onSendKeys: (List<String>) -> Unit,
    onInvokeUia: (UiaElement, String, String) -> Unit,
    onActivateDisplay: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val frame by stageFrameFlow.collectAsState()
    val cursorPos by stageCursorFlow.collectAsState()
    val bmp = frame?.bitmap
    val meta = frame?.meta ?: StageFrameMeta()
    val cursorX = cursorPos.first
    val cursorY = cursorPos.second

    // Persistent input engines (never reset across frames!)
    val trackpadEngine = remember { TrackpadEngine() }
    val doubleTapSnapper = remember { DoubleTapSnapper() }
    val scrollAccumulator = remember { ScrollAccumulator() }

    // Visual gesture feedback overlays
    var loupeActive by remember { mutableStateOf(false) }
    var loupeNormX by remember { mutableFloatStateOf(0.5f) }
    var loupeNormY by remember { mutableFloatStateOf(0.5f) }
    var loupeTouchPx by remember { mutableStateOf(Offset.Zero) }

    var holdRingPos by remember { mutableStateOf<Offset?>(null) }
    var dragRingPos by remember { mutableStateOf<Offset?>(null) }
    var tapRipple by remember { mutableStateOf<TouchRipple?>(null) }
    var rippleCleanupTick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(tapRipple) {
        val r = tapRipple ?: return@LaunchedEffect
        delay(240)
        if (tapRipple?.createdMs == r.createdMs) {
            tapRipple = null
            rippleCleanupTick++
        }
    }

    LaunchedEffect(cursorX, cursorY) {
        trackpadEngine.syncFromHost(cursorX, cursorY, force = false)
    }

    val activeWindow = state.windows.firstOrNull { it.hwnd == state.stageHwnd }
    val sourceLabel = when {
        state.stageMode == "window" && activeWindow != null -> activeWindow.title
        state.stageMode == "window" -> "Focused Window"
        else -> "Full Desktop"
    }

    val controlsVisible = !state.stageControlsCollapsed

    // Fixed full-bleed BoxWithConstraints: floating UI overlays NEVER change canvas dimensions!
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val boxW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val boxH = constraints.maxHeight.toFloat().coerceAtLeast(1f)

        // Single canonical projection shared across rendering, Touch, Trackpad, Reticle, Loupe, X-Ray
        val projection = remember(
            boxW,
            boxH,
            meta.sourceWidth,
            meta.sourceHeight,
            meta.originX,
            meta.originY,
            bmp,
            state.preferences.stageFillScreen
        ) {
            val srcW = if (meta.sourceWidth > 1) meta.sourceWidth else (bmp?.width ?: 1)
            val srcH = if (meta.sourceHeight > 1) meta.sourceHeight else (bmp?.height ?: 1)
            StageProjection.compute(
                boxWidth = boxW,
                boxHeight = boxH,
                sourceWidth = srcW,
                sourceHeight = srcH,
                originX = meta.originX,
                originY = meta.originY,
                fillScreen = state.preferences.stageFillScreen
            )
        }

        // Latest reactive values captured cleanly without EVER restarting pointerInput mid-gesture!
        val latestState by rememberUpdatedState(state)
        val latestMeta by rememberUpdatedState(meta)
        val latestCursor by rememberUpdatedState(cursorPos)
        val latestProj by rememberUpdatedState(projection)
        val latestDensity by rememberUpdatedState(density.density.coerceAtLeast(1f))
        val latestOnPointerMoveAbs by rememberUpdatedState(onPointerMoveAbs)
        val latestOnPointerDown by rememberUpdatedState(onPointerDown)
        val latestOnPointerUp by rememberUpdatedState(onPointerUp)
        val latestOnMouseClick by rememberUpdatedState(onMouseClick)
        val latestOnScroll by rememberUpdatedState(onScroll)
        val latestOnSetRoi by rememberUpdatedState(onSetRoi)

        if (bmp == null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(color = KovaPalette.Cyan, strokeWidth = 2.5.dp)
                Text(
                    "Connecting live Windows display...",
                    style = MaterialTheme.typography.bodySmall,
                    color = KovaPalette.InkSecondary
                )
            }
        } else {
            val imageBitmap = remember(bmp) { bmp.asImageBitmap() }

            // Direct aspect-locked/fill Canvas rendering + zero-restart gesture recognizer
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val firstDown = awaitFirstDown(requireUnconsumed = false)
                            val st = latestState
                            val mode = st.stageInputMode
                            val prefs = st.preferences
                            val d = latestDensity
                            val proj = latestProj
                            val curCur = latestCursor

                            val downTime = System.currentTimeMillis()
                            val startPos = firstDown.position
                            var lastPos = startPos
                            var lastMoveMs = downTime
                            var lastNetSendMs = 0L
                            var lastRoiSendMs = 0L
                            var totalDragDp = 0f
                            var maxPointers = 1

                            scrollAccumulator.reset()

                            var touchPhase = TouchPhase.PENDING
                            val touchStartWin = proj.viewportToScreen(startPos.x, startPos.y)
                            var pinchAccum = 1f
                            var multiPanAccum = Offset.Zero
                            var multiAnchorNorm = proj.viewportToNormalized(startPos.x, startPos.y)
                            var holdTimerJob: Job? = null

                            if (mode == StageInputMode.TRACKPAD) {
                                trackpadEngine.syncFromHost(curCur.first, curCur.second, force = false)
                                trackpadEngine.beginGesture(curCur.first, curCur.second)
                            } else if (mode == StageInputMode.LOUPE) {
                                loupeActive = true
                                loupeTouchPx = startPos
                                val (nx, ny) = proj.viewportToNormalized(startPos.x, startPos.y)
                                loupeNormX = nx
                                loupeNormY = ny
                                latestOnPointerMoveAbs(touchStartWin.first, touchStartWin.second, null)
                            } else if (mode == StageInputMode.TOUCH) {
                                val holdDelay = prefs.longPressMs.coerceIn(240, 750).toLong()
                                holdTimerJob = scope.launch {
                                    delay(holdDelay)
                                    if (touchPhase == TouchPhase.PENDING) {
                                        touchPhase = TouchPhase.HOLD_ARMED
                                        holdRingPos = startPos
                                        if (latestState.preferences.hapticsEnabled) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                    }
                                }
                            }

                            var trackpadLastX = trackpadEngine.currentX
                            var trackpadLastY = trackpadEngine.currentY
                            var trackpadPendingFlush = false

                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val active = event.changes.filter { it.pressed }
                                    if (active.size > maxPointers) maxPointers = active.size
                                    if (active.isEmpty()) break

                                    val nowMs = System.currentTimeMillis()
                                    val curState = latestState
                                    val curPrefs = curState.preferences
                                    val curMeta = latestMeta
                                    val curProj = latestProj

                                    if (active.size >= 2) {
                                        holdTimerJob?.cancel()
                                        holdRingPos = null
                                        if (touchPhase == TouchPhase.DRAGGING) {
                                            val curWin = curProj.viewportToScreen(lastPos.x, lastPos.y)
                                            latestOnPointerUp(curWin.first, curWin.second, "left")
                                            dragRingPos = null
                                        }
                                        touchPhase = TouchPhase.MULTI

                                        val zoom = event.calculateZoom()
                                        val centroid = event.calculateCentroid(useCurrent = true)
                                        val prevCentroid = event.calculateCentroid(useCurrent = false)
                                        val panPx = centroid - prevCentroid
                                        val panDp = panPx / d
                                        totalDragDp += hypot(panDp.x.toDouble(), panDp.y.toDouble()).toFloat()

                                        when (mode) {
                                            StageInputMode.TRACKPAD -> {
                                                val (wx, wy) = scrollAccumulator.accumulate(
                                                    dxDp = panDp.x,
                                                    dyDp = panDp.y,
                                                    sensitivity = curPrefs.scrollSensitivity,
                                                    naturalScroll = curPrefs.naturalScroll
                                                )
                                                if (wx != 0 || wy != 0) {
                                                    latestOnScroll(wx, wy, null, null)
                                                }
                                            }
                                            StageInputMode.TOUCH -> {
                                                pinchAccum *= zoom
                                                multiPanAccum += panPx
                                                multiAnchorNorm = curProj.viewportToNormalized(centroid.x, centroid.y)

                                                val hasZoom = abs(pinchAccum - 1f) > 0.08f
                                                val hasPan = curState.stageRoi != null && multiPanAccum.getDistance() > (12f * d)
                                                if ((hasZoom || hasPan) && (nowMs - lastRoiSendMs) >= 90L) {
                                                    val curRoi = curState.stageRoi ?: StageRoi(0f, 0f, 1f, 1f)
                                                    val updated = computePinchPanRoi(
                                                        curX = curRoi.x,
                                                        curY = curRoi.y,
                                                        curW = curRoi.w,
                                                        curH = curRoi.h,
                                                        zoomFactor = pinchAccum,
                                                        panNormDx = multiPanAccum.x / curProj.fittedWidth.coerceAtLeast(1f),
                                                        panNormDy = multiPanAccum.y / curProj.fittedHeight.coerceAtLeast(1f),
                                                        anchorNormX = multiAnchorNorm.first,
                                                        anchorNormY = multiAnchorNorm.second
                                                    )
                                                    latestOnSetRoi(
                                                        updated?.let { StageRoi(it[0], it[1], it[2], it[3]) }
                                                    )
                                                    pinchAccum = 1f
                                                    multiPanAccum = Offset.Zero
                                                    lastRoiSendMs = nowMs
                                                }
                                            }
                                            StageInputMode.LOUPE -> Unit
                                        }
                                    } else {
                                        val c = active.first()
                                        val deltaPx = c.position - lastPos
                                        val dtMs = (nowMs - lastMoveMs).coerceAtLeast(4L)
                                        lastPos = c.position
                                        lastMoveMs = nowMs

                                        val dxDp = deltaPx.x / d
                                        val dyDp = deltaPx.y / d
                                        val stepDp = hypot(dxDp.toDouble(), dyDp.toDouble()).toFloat()
                                        totalDragDp += stepDp

                                        when (mode) {
                                            StageInputMode.TOUCH -> {
                                                val distFromStartDp = (c.position - startPos).getDistance() / d
                                                when (touchPhase) {
                                                    TouchPhase.PENDING -> {
                                                        if (distFromStartDp > 10f) {
                                                            holdTimerJob?.cancel()
                                                            touchPhase = TouchPhase.SCROLLING
                                                            val (wx, wy) = scrollAccumulator.accumulate(
                                                                dxDp = dxDp,
                                                                dyDp = dyDp,
                                                                sensitivity = curPrefs.scrollSensitivity,
                                                                naturalScroll = curPrefs.naturalScroll
                                                            )
                                                            if (wx != 0 || wy != 0) {
                                                                latestOnScroll(wx, wy, touchStartWin.first, touchStartWin.second)
                                                            }
                                                        }
                                                    }
                                                    TouchPhase.SCROLLING -> {
                                                        val (wx, wy) = scrollAccumulator.accumulate(
                                                            dxDp = dxDp,
                                                            dyDp = dyDp,
                                                            sensitivity = curPrefs.scrollSensitivity,
                                                            naturalScroll = curPrefs.naturalScroll
                                                        )
                                                        if (wx != 0 || wy != 0) {
                                                            latestOnScroll(wx, wy, touchStartWin.first, touchStartWin.second)
                                                        }
                                                    }
                                                    TouchPhase.HOLD_ARMED -> {
                                                        if (distFromStartDp > 7f) {
                                                            touchPhase = TouchPhase.DRAGGING
                                                            holdRingPos = null
                                                            dragRingPos = c.position
                                                            latestOnPointerDown(touchStartWin.first, touchStartWin.second, "left")
                                                            val curWin = curProj.viewportToScreen(c.position.x, c.position.y)
                                                            latestOnPointerMoveAbs(curWin.first, curWin.second, null)
                                                            lastNetSendMs = nowMs
                                                        }
                                                    }
                                                    TouchPhase.DRAGGING -> {
                                                        dragRingPos = c.position
                                                        if ((nowMs - lastNetSendMs) >= 16L) {
                                                            val curWin = curProj.viewportToScreen(c.position.x, c.position.y)
                                                            latestOnPointerMoveAbs(curWin.first, curWin.second, null)
                                                            lastNetSendMs = nowMs
                                                        }
                                                    }
                                                    else -> Unit
                                                }
                                            }

                                            StageInputMode.TRACKPAD -> {
                                                val minX = curMeta.fullX
                                                val minY = curMeta.fullY
                                                val maxX = curMeta.fullX + (curMeta.fullWidth - 1).coerceAtLeast(1)
                                                val maxY = curMeta.fullY + (curMeta.fullHeight - 1).coerceAtLeast(1)
                                                val upd = trackpadEngine.applyDeltaDp(
                                                    dxDp = dxDp,
                                                    dyDp = dyDp,
                                                    dtMs = dtMs,
                                                    sensitivity = curPrefs.touchpadSensitivity,
                                                    curve = PointerCurve.fromId(curPrefs.pointerCurve),
                                                    referenceWidthPx = curMeta.sourceWidth.coerceAtLeast(360),
                                                    minX = minX,
                                                    minY = minY,
                                                    maxX = maxX,
                                                    maxY = maxY
                                                )
                                                if (upd.moved) {
                                                    trackpadLastX = upd.x
                                                    trackpadLastY = upd.y
                                                    if ((nowMs - lastNetSendMs) >= 16L) {
                                                        latestOnPointerMoveAbs(upd.x, upd.y, upd.seq)
                                                        lastNetSendMs = nowMs
                                                        trackpadPendingFlush = false
                                                    } else {
                                                        trackpadPendingFlush = true
                                                    }
                                                }
                                            }

                                            StageInputMode.LOUPE -> {
                                                loupeTouchPx = c.position
                                                val (nx, ny) = curProj.viewportToNormalized(c.position.x, c.position.y)
                                                loupeNormX = nx
                                                loupeNormY = ny
                                                if ((nowMs - lastNetSendMs) >= 16L) {
                                                    val curWin = curProj.viewportToScreen(c.position.x, c.position.y)
                                                    latestOnPointerMoveAbs(curWin.first, curWin.second, null)
                                                    lastNetSendMs = nowMs
                                                }
                                            }
                                        }
                                    }

                                    event.changes.forEach { it.consume() }
                                }
                            } finally {
                                holdTimerJob?.cancel()
                                holdRingPos = null
                            }

                            val curState = latestState
                            val curPrefs = curState.preferences
                            val curProj = latestProj
                            val elapsed = System.currentTimeMillis() - downTime

                            when (mode) {
                                StageInputMode.TOUCH -> {
                                    when (touchPhase) {
                                        TouchPhase.DRAGGING -> {
                                            val endWin = curProj.viewportToScreen(lastPos.x, lastPos.y)
                                            latestOnPointerUp(endWin.first, endWin.second, "left")
                                            dragRingPos = null
                                        }
                                        TouchPhase.HOLD_ARMED -> {
                                            tapRipple = TouchRipple(startPos.x, startPos.y, KovaPalette.Amber)
                                            latestOnMouseClick(touchStartWin.first, touchStartWin.second, "right", 1, false)
                                        }
                                        TouchPhase.PENDING -> {
                                            if (curProj.containsViewportPoint(startPos.x, startPos.y, padPx = 8f * d)) {
                                                val rawWin = curProj.viewportToScreen(startPos.x, startPos.y)
                                                val resolved = doubleTapSnapper.resolveTap(
                                                    nowMs = System.currentTimeMillis(),
                                                    touchXDp = startPos.x / d,
                                                    touchYDp = startPos.y / d,
                                                    mappedWinX = rawWin.first,
                                                    mappedWinY = rawWin.second
                                                )
                                                tapRipple = TouchRipple(startPos.x, startPos.y, KovaPalette.Cyan)
                                                if (curPrefs.hapticsEnabled) {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                                latestOnMouseClick(resolved.winX, resolved.winY, "left", 1, !resolved.isSecondOfDoubleTap)
                                            }
                                        }
                                        TouchPhase.MULTI -> {
                                            if (totalDragDp < 18f && abs(pinchAccum - 1f) < 0.10f && elapsed < 340L) {
                                                tapRipple = TouchRipple(startPos.x, startPos.y, KovaPalette.Violet)
                                                if (curPrefs.hapticsEnabled) {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                }
                                                latestOnMouseClick(touchStartWin.first, touchStartWin.second, "right", 1, false)
                                            }
                                        }
                                        TouchPhase.SCROLLING, TouchPhase.IDLE -> Unit
                                    }
                                }

                                StageInputMode.TRACKPAD -> {
                                    if (trackpadPendingFlush) {
                                        latestOnPointerMoveAbs(trackpadLastX, trackpadLastY, trackpadEngine.seq)
                                    }
                                    if (totalDragDp < 9f && elapsed < 300L) {
                                        if (curPrefs.hapticsEnabled) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                        if (maxPointers >= 2) {
                                            latestOnMouseClick(trackpadEngine.currentX, trackpadEngine.currentY, "right", 1, false)
                                        } else {
                                            latestOnMouseClick(trackpadEngine.currentX, trackpadEngine.currentY, "left", 1, true)
                                        }
                                    }
                                }

                                StageInputMode.LOUPE -> {
                                    loupeActive = false
                                    val targetWin = curProj.viewportToScreen(loupeTouchPx.x, loupeTouchPx.y)
                                    tapRipple = TouchRipple(loupeTouchPx.x, loupeTouchPx.y, KovaPalette.Cyan)
                                    if (curPrefs.hapticsEnabled) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    latestOnMouseClick(targetWin.first, targetWin.second, "left", 1, true)
                                }
                            }
                        }
                    }
            ) {
                drawImage(
                    image = imageBitmap,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(imageBitmap.width, imageBitmap.height),
                    dstOffset = IntOffset(projection.offsetX.roundToInt(), projection.offsetY.roundToInt()),
                    dstSize = IntSize(
                        projection.fittedWidth.roundToInt().coerceAtLeast(1),
                        projection.fittedHeight.roundToInt().coerceAtLeast(1)
                    ),
                    filterQuality = FilterQuality.Low
                )

                if (
                    state.preferences.showCursorReticle &&
                    (state.stageInputMode == StageInputMode.TRACKPAD || state.stageDragLocked) &&
                    (cursorX > 0 || cursorY > 0)
                ) {
                    val vp = projection.screenToViewport(cursorX, cursorY)
                    if (vp != null) {
                        val c = Offset(vp.first, vp.second)
                        val reticleColor = if (state.stageDragLocked) KovaPalette.Rose else KovaPalette.Cyan
                        drawCircle(color = reticleColor.copy(alpha = 0.22f), radius = 11.dp.toPx(), center = c)
                        drawCircle(color = reticleColor, radius = 8.dp.toPx(), center = c, style = Stroke(width = 1.8.dp.toPx()))
                        drawCircle(color = Color.White, radius = 2.4.dp.toPx(), center = c)
                    }
                }

                tapRipple?.let { r ->
                    val center = Offset(r.x, r.y)
                    drawCircle(color = r.color.copy(alpha = 0.26f), radius = 22.dp.toPx(), center = center)
                    drawCircle(color = r.color, radius = 14.dp.toPx(), center = center, style = Stroke(width = 2.dp.toPx()))
                }
                holdRingPos?.let { pos ->
                    drawCircle(color = KovaPalette.Amber.copy(alpha = 0.24f), radius = 30.dp.toPx(), center = pos)
                    drawCircle(color = KovaPalette.Amber, radius = 24.dp.toPx(), center = pos, style = Stroke(width = 2.5.dp.toPx()))
                }
                dragRingPos?.let { pos ->
                    drawCircle(color = KovaPalette.Rose.copy(alpha = 0.24f), radius = 26.dp.toPx(), center = pos)
                    drawCircle(color = KovaPalette.Rose, radius = 18.dp.toPx(), center = pos, style = Stroke(width = 2.2.dp.toPx()))
                }
            }

            // Semantic X-Ray Hit-Target Chips aligned via exact StageProjection
            if (state.stageXRayEnabled && state.uiaData != null) {
                val elements = state.uiaData.elements.filter { it.enabled }.take(40)
                elements.forEach { el ->
                    val vp = projection.screenToViewport(el.centerX, el.centerY)
                    if (vp != null) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (el.isEditable) KovaPalette.Emerald.copy(alpha = 0.92f)
                            else KovaPalette.Amber.copy(alpha = 0.92f),
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset { IntOffset(vp.first.roundToInt() - 28, vp.second.roundToInt() - 14) }
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

            // Precision 2.5× Magnifying Loupe
            if (loupeActive) {
                val loupeSizePx = with(density) { 132.dp.toPx() }.roundToInt()
                val lx = (loupeTouchPx.x - loupeSizePx / 2f).coerceIn(12f, boxW - loupeSizePx - 12f).roundToInt()
                val ly = (loupeTouchPx.y - loupeSizePx - 78f).coerceAtLeast(12f).roundToInt()
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset { IntOffset(lx, ly) }
                        .size(132.dp)
                        .clip(CircleShape)
                        .border(2.dp, KovaPalette.Cyan, CircleShape)
                        .background(Color.Black)
                ) {
                    LoupeCanvas(bitmap = bmp, normX = loupeNormX, normY = loupeNormY)
                }
            }
        }

        // ---------- Floating Glass Top Workspace Pill (Collapses cleanly when not needed!) ----------
        androidx.compose.animation.AnimatedVisibility(
            visible = controlsVisible && !state.showLiveKeyboard,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = if (state.isFullscreen) 14.dp else 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = KovaPalette.SlateDeep.copy(alpha = 0.86f),
                    border = BorderStroke(1.dp, KovaPalette.BorderDark.copy(alpha = 0.8f)),
                    shadowElevation = 6.dp,
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable { onSwitchAdjacentWindow(-1) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.ChevronLeft, "Previous Window", tint = KovaPalette.InkSecondary, modifier = Modifier.size(18.dp))
                        }

                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onOpenSwitcher() }
                                .padding(horizontal = 4.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (state.stageMode == "desktop") Icons.Rounded.DesktopWindows else Icons.Rounded.Layers,
                                contentDescription = null,
                                tint = KovaPalette.Cyan,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = sourceLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = KovaPalette.InkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (state.windows.isNotEmpty()) {
                                Spacer(Modifier.width(4.dp))
                                Surface(
                                    shape = RoundedCornerShape(7.dp),
                                    color = KovaPalette.Cyan.copy(alpha = 0.16f)
                                ) {
                                    Text(
                                        text = "${state.windows.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = KovaPalette.Cyan,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable { onSwitchAdjacentWindow(1) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.ChevronRight, "Next Window", tint = KovaPalette.InkSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                // 1-tap Fit / Fill full-width-height toggle
                val isFill = state.preferences.stageFillScreen
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isFill) KovaPalette.Cyan.copy(alpha = 0.22f) else KovaPalette.SlateDeep.copy(alpha = 0.86f),
                    border = BorderStroke(1.dp, if (isFill) KovaPalette.Cyan else KovaPalette.BorderDark),
                    modifier = Modifier.clickable { onToggleFillScreen() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.AspectRatio,
                            "Fit or Fill Screen",
                            tint = if (isFill) KovaPalette.Cyan else KovaPalette.InkSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            if (isFill) "Fill" else "Fit",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isFill) KovaPalette.Cyan else KovaPalette.InkPrimary
                        )
                    }
                }

                // Mobile-Fit Reflow toggle when staging a single window
                if (state.stageMode == "window" && state.stageHwnd != null) {
                    val isFit = activeWindow?.mobileFit == true
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isFit) KovaPalette.Emerald.copy(alpha = 0.24f) else KovaPalette.SlateDeep.copy(alpha = 0.86f),
                        border = BorderStroke(1.dp, if (isFit) KovaPalette.Emerald else KovaPalette.BorderDark),
                        modifier = Modifier.clickable {
                            onWindowAction(state.stageHwnd, if (isFit) "restore_fit" else "mobile_fit")
                        }
                    ) {
                        Icon(
                            Icons.Rounded.PhoneAndroid,
                            "Mobile-Fit Reflow",
                            tint = if (isFit) KovaPalette.Emerald else KovaPalette.InkPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp).size(15.dp)
                        )
                    }
                }

                // 1× Zoom Reset chip
                if (state.stageRoi != null) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = KovaPalette.Cyan.copy(alpha = 0.22f),
                        border = BorderStroke(1.dp, KovaPalette.Cyan),
                        modifier = Modifier.clickable { onSetRoi(null) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.ZoomOutMap, null, tint = KovaPalette.Cyan, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("1×", style = MaterialTheme.typography.labelSmall, color = KovaPalette.Cyan)
                        }
                    }
                }

                // Semantic X-Ray Toggle
                Surface(
                    shape = CircleShape,
                    color = if (state.stageXRayEnabled) KovaPalette.Amber.copy(alpha = 0.24f) else KovaPalette.SlateDeep.copy(alpha = 0.86f),
                    border = BorderStroke(1.dp, if (state.stageXRayEnabled) KovaPalette.Amber else KovaPalette.BorderDark),
                    modifier = Modifier
                        .size(34.dp)
                        .clickable { onToggleXRay() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.CenterFocusStrong,
                            "X-Ray Controls",
                            tint = if (state.stageXRayEnabled) KovaPalette.Amber else KovaPalette.InkSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // True Edge-to-Edge Fullscreen Toggle (hides system bars + nav bar + top bar!)
                Surface(
                    shape = CircleShape,
                    color = if (state.isFullscreen) KovaPalette.Cyan.copy(alpha = 0.24f) else KovaPalette.SlateDeep.copy(alpha = 0.86f),
                    border = BorderStroke(1.dp, if (state.isFullscreen) KovaPalette.Cyan else KovaPalette.BorderDark),
                    modifier = Modifier
                        .size(34.dp)
                        .clickable { onSetFullscreen(!state.isFullscreen) }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (state.isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                            if (state.isFullscreen) "Exit Fullscreen" else "True Fullscreen",
                            tint = if (state.isFullscreen) KovaPalette.Cyan else KovaPalette.InkSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // ---------- Ultra-Minimal Corner Pill When Controls Are Collapsed (Zero Screen Clutter!) ----------
        if (!controlsVisible && !state.showLiveKeyboard) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = if (state.isFullscreen) 14.dp else 8.dp, end = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Quick 1-tap Keyboard orb (always accessible even when collapsed!)
                Surface(
                    shape = CircleShape,
                    color = KovaPalette.SlateDeep.copy(alpha = 0.46f),
                    border = BorderStroke(1.dp, KovaPalette.BorderDark.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .size(34.dp)
                        .clickable { onSetLiveKeyboardVisible(true) }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Keyboard, "Type Live", tint = KovaPalette.Emerald.copy(alpha = 0.9f), modifier = Modifier.size(16.dp))
                    }
                }

                // Expand all controls orb
                Surface(
                    shape = CircleShape,
                    color = KovaPalette.SlateDeep.copy(alpha = 0.46f),
                    border = BorderStroke(1.dp, KovaPalette.BorderDark.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .size(34.dp)
                        .clickable { onSetControlsCollapsed(false) }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Tune, "Expand Controls", tint = KovaPalette.Cyan.copy(alpha = 0.9f), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // ---------- Window Unavailable Overlay ----------
        val notice = state.windowStateNotice
        if (notice != null) {
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = KovaPalette.SlateCard.copy(alpha = 0.96f)),
                border = BorderStroke(1.dp, KovaPalette.Cyan.copy(alpha = 0.6f))
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = if (notice.state == "minimized") "Window Minimized" else "Window Closed",
                        style = MaterialTheme.typography.titleMedium,
                        color = KovaPalette.InkPrimary
                    )
                    Text(
                        text = notice.message.ifBlank {
                            if (notice.state == "minimized") "This window is minimized on Windows. Restore it or switch to another window."
                            else "This window is no longer open. Pick another window or view Full Desktop."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = KovaPalette.InkSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        if (notice.state == "minimized" && notice.hwnd > 0L) {
                            Button(
                                onClick = { onWindowAction(notice.hwnd, "restore") },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Rounded.Restore, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("Restore")
                            }
                        }
                        FilledTonalButton(
                            onClick = onOpenSwitcher,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Switch Window")
                        }
                        OutlinedButton(
                            onClick = { onSelectSource("desktop", null) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Desktop")
                        }
                    }
                }
            }
        }

        // ---------- Floating Bottom Stack: Quick-Type Pill, Warning, Dock OR Non-Blocking LiveKeyboardBar ----------
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (state.showLiveKeyboard) {
                // Slim inline bar flush above Gboard — ZERO dark scrim, FULL live Windows visibility!
                LiveKeyboardBar(
                    hapticsEnabled = state.preferences.hapticsEnabled,
                    onImePatch = onImePatch,
                    onSendKeys = onSendKeys,
                    onDispatchDraft = onDispatchDraft,
                    onDismiss = { onSetLiveKeyboardVisible(false) }
                )
            } else {
                // Auto-Detected Text-Field Pill (opens slim non-blocking LiveKeyboardBar!)
                val focusedHint = state.focusedInputHint
                androidx.compose.animation.AnimatedVisibility(
                    visible = focusedHint != null && focusedHint.editable,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = KovaPalette.Emerald.copy(alpha = 0.92f),
                        shadowElevation = 8.dp,
                        modifier = Modifier.clickable { onSetLiveKeyboardVisible(true) }
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Keyboard, null, tint = KovaPalette.Obsidian, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(7.dp))
                            Text(
                                text = focusedHint?.name?.takeIf { it.isNotBlank() }
                                    ?.let { "Type into ${it.take(22)}" }
                                    ?: "Tap to type live",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = KovaPalette.Obsidian,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .clickable { onDismissFocusedHint() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Close, "Dismiss", tint = KovaPalette.Obsidian, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                // Actionable Blank-Frame Recovery Card
                if (!state.stageWarning.isNullOrBlank()) {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = KovaPalette.SlateCard.copy(alpha = 0.96f)),
                        border = BorderStroke(1.dp, KovaPalette.Amber.copy(alpha = 0.75f))
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(Icons.Rounded.WarningAmber, null, tint = KovaPalette.Amber, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = state.stageWarning,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = KovaPalette.InkPrimary
                                )
                            }
                            Button(
                                onClick = onActivateDisplay,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = KovaPalette.Amber,
                                    contentColor = KovaPalette.Obsidian
                                ),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Activate Live Console Display", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                // Collapsible Floating Glass Action Dock
                androidx.compose.animation.AnimatedVisibility(
                    visible = controlsVisible,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    ModifierChordBar(
                        inputMode = state.stageInputMode,
                        dragLocked = state.stageDragLocked,
                        hapticsEnabled = state.preferences.hapticsEnabled,
                        onModeChange = onModeChange,
                        onLeftClick = { onMouseClick(null, null, "left", 1, true) },
                        onRightClick = { onMouseClick(null, null, "right", 1, false) },
                        onToggleDragLock = onToggleDragLock,
                        onOpenKeyboard = { onSetLiveKeyboardVisible(true) },
                        onOpenControls = onOpenControls,
                        onCollapse = { onSetControlsCollapsed(true) },
                        onSendKeys = onSendKeys
                    )
                }
            }
        }
    }
}

@Composable
private fun LoupeCanvas(bitmap: Bitmap, normX: Float, normY: Float) {
    val img = remember(bitmap) { bitmap.asImageBitmap() }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val sampleSize = (minOf(bitmap.width, bitmap.height) * 0.22f).roundToInt().coerceIn(48, 220)
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
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.Medium
        )

        val midX = size.width / 2f
        val midY = size.height / 2f
        drawLine(KovaPalette.Cyan, Offset(midX - 18f, midY), Offset(midX + 18f, midY), strokeWidth = 2f)
        drawLine(KovaPalette.Cyan, Offset(midX, midY - 18f), Offset(midX, midY + 18f), strokeWidth = 2f)
        drawCircle(KovaPalette.Cyan, radius = 6f, center = Offset(midX, midY), style = Stroke(width = 1.8f))
    }
}
