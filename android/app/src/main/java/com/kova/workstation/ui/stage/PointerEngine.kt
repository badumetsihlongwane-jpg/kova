package com.kova.workstation.ui.stage

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Velocity-response curve for Trackpad pointer movement.
 *
 * - LINEAR: Pure 1:1 speed-independent scaling for maximum muscle-memory predictability.
 * - BALANCED: Smooth C¹ Hermite ramp from 0.78× (micro-aim) to 1.85× (fast flick), capped cleanly.
 * - DYNAMIC: Wider C¹ ramp from 0.70× to 2.50× for large multi-monitor or high-res desktops.
 */
enum class PointerCurve(val id: String, val label: String) {
    LINEAR("LINEAR", "Linear 1:1"),
    BALANCED("BALANCED", "Balanced"),
    DYNAMIC("DYNAMIC", "Dynamic");

    companion object {
        fun fromId(id: String?): PointerCurve =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: BALANCED
    }
}

/**
 * Single authoritative aspect-ratio & coordinate projection shared across:
 * 1. Remote frame rendering bounds
 * 2. Direct Touch / Loupe -> Windows screen coordinates
 * 3. Virtual cursor reticle placement
 * 4. X-Ray semantic hit-target overlays
 * 5. Touch ripple & long-press ring visuals
 *
 * Keeping all five consumers bound to ONE pure projection guarantees ±0px alignment across
 * any phone aspect ratio, orientation, remote monitor resolution, or zoomed ROI.
 */
data class StageProjection(
    val boxWidth: Float,
    val boxHeight: Float,
    val fittedWidth: Float,
    val fittedHeight: Float,
    val offsetX: Float,
    val offsetY: Float,
    val originX: Int,
    val originY: Int,
    val sourceWidth: Int,
    val sourceHeight: Int
) {
    /** Convert a phone viewport pixel `(px, py)` into exact physical Windows `(x, y)`. */
    fun viewportToScreen(px: Float, py: Float): Pair<Int, Int> {
        val nx = ((px - offsetX) / fittedWidth.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val ny = ((py - offsetY) / fittedHeight.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val maxDx = (sourceWidth - 1).coerceAtLeast(0)
        val maxDy = (sourceHeight - 1).coerceAtLeast(0)
        val sx = originX + (nx * maxDx).roundToInt()
        val sy = originY + (ny * maxDy).roundToInt()
        return sx to sy
    }

    /** Convert a Windows screen pixel `(sx, sy)` into phone viewport pixel `(px, py)`, or null if outside visible frame. */
    fun screenToViewport(sx: Int, sy: Int): Pair<Float, Float>? {
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val maxDx = (sourceWidth - 1).coerceAtLeast(1).toFloat()
        val maxDy = (sourceHeight - 1).coerceAtLeast(1).toFloat()
        val nx = (sx - originX) / maxDx
        val ny = (sy - originY) / maxDy
        if (nx < -0.02f || nx > 1.02f || ny < -0.02f || ny > 1.02f) return null
        val px = offsetX + nx.coerceIn(0f, 1f) * fittedWidth
        val py = offsetY + ny.coerceIn(0f, 1f) * fittedHeight
        return px to py
    }

    /** Convert viewport pixel into normalized `[0, 1]` coordinates inside the fitted video frame. */
    fun viewportToNormalized(px: Float, py: Float): Pair<Float, Float> {
        val nx = ((px - offsetX) / fittedWidth.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val ny = ((py - offsetY) / fittedHeight.coerceAtLeast(1f)).coerceIn(0f, 1f)
        return nx to ny
    }

    /** True when `(px, py)` falls inside the rendered remote frame (plus optional edge tolerance). */
    fun containsViewportPoint(px: Float, py: Float, padPx: Float = 0f): Boolean {
        return px >= (offsetX - padPx) &&
            px <= (offsetX + fittedWidth + padPx) &&
            py >= (offsetY - padPx) &&
            py <= (offsetY + fittedHeight + padPx)
    }

    companion object {
        fun compute(
            boxWidth: Float,
            boxHeight: Float,
            sourceWidth: Int,
            sourceHeight: Int,
            originX: Int,
            originY: Int
        ): StageProjection {
            val safeBoxW = boxWidth.coerceAtLeast(1f)
            val safeBoxH = boxHeight.coerceAtLeast(1f)
            val safeSrcW = sourceWidth.coerceAtLeast(1)
            val safeSrcH = sourceHeight.coerceAtLeast(1)
            val srcAspect = safeSrcW.toFloat() / safeSrcH.toFloat()
            val boxAspect = safeBoxW / safeBoxH
            val fittedW: Float
            val fittedH: Float
            if (srcAspect > boxAspect) {
                fittedW = safeBoxW
                fittedH = safeBoxW / srcAspect
            } else {
                fittedH = safeBoxH
                fittedW = safeBoxH * srcAspect
            }
            return StageProjection(
                boxWidth = safeBoxW,
                boxHeight = safeBoxH,
                fittedWidth = fittedW,
                fittedHeight = fittedH,
                offsetX = (safeBoxW - fittedW) / 2f,
                offsetY = (safeBoxH - fittedH) / 2f,
                originX = originX,
                originY = originY,
                sourceWidth = safeSrcW,
                sourceHeight = safeSrcH
            )
        }
    }
}

data class PointerUpdate(
    val x: Int,
    val y: Int,
    val seq: Long,
    val moved: Boolean
)

/**
 * Precision sub-pixel trackpad engine with density-independent velocity transfer,
 * C¹ Hermite smoothing (no step discontinuities, no overshoot), and monotonic sequence ownership.
 */
class TrackpadEngine {
    private var cursorX: Float = 0f
    private var cursorY: Float = 0f
    private var initialized: Boolean = false
    private var smoothedVelocityDpPerSec: Float = 0f
    var seq: Long = 0L
        private set

    val currentX: Int get() = cursorX.roundToInt()
    val currentY: Int get() = cursorY.roundToInt()

    fun syncFromHost(x: Int, y: Int, force: Boolean = false) {
        if (!initialized || force) {
            cursorX = x.toFloat()
            cursorY = y.toFloat()
            initialized = true
        }
    }

    fun beginGesture(hostCursorX: Int, hostCursorY: Int) {
        if (!initialized) {
            cursorX = hostCursorX.toFloat()
            cursorY = hostCursorY.toFloat()
            initialized = true
        }
        smoothedVelocityDpPerSec = 0f
    }

    /**
     * Feed a touch delta measured in density-independent `dp` and elapsed `dtMs`.
     * Returns exact integer physical Windows coordinates clamped to `[minX..maxX, minY..maxY]`.
     */
    fun applyDeltaDp(
        dxDp: Float,
        dyDp: Float,
        dtMs: Long,
        sensitivity: Float,
        curve: PointerCurve,
        referenceWidthPx: Int,
        minX: Int,
        minY: Int,
        maxX: Int,
        maxY: Int
    ): PointerUpdate {
        val distDp = hypot(dxDp.toDouble(), dyDp.toDouble()).toFloat()
        if (distDp < 0.001f) {
            return PointerUpdate(currentX, currentY, seq, moved = false)
        }

        // Clamp dtMs to sane touch sampling interval [4ms, 50ms] to avoid velocity spikes on dropped frames
        val safeDtSec = (dtMs.coerceIn(4L, 50L)) / 1000f
        val instantVel = distDp / safeDtSec
        // Light exponential filter on velocity only (NOT on position!) prevents noisy jumps without adding lag
        smoothedVelocityDpPerSec = if (smoothedVelocityDpPerSec <= 0.01f) {
            instantVel
        } else {
            smoothedVelocityDpPerSec * 0.45f + instantVel * 0.55f
        }

        val gain = computeVelocityGain(smoothedVelocityDpPerSec, curve)
        // Adapt base pixels-per-dp gently to remote surface width so a ~320dp sweep covers the screen naturally
        val basePxPerDp = (referenceWidthPx.coerceAtLeast(360) / 340f).coerceIn(1.25f, 4.2f)
        val scale = basePxPerDp * sensitivity.coerceIn(0.4f, 3.5f) * gain

        val prevIntX = cursorX.roundToInt()
        val prevIntY = cursorY.roundToInt()

        val upperX = max(minX, maxX).toFloat()
        val upperY = max(minY, maxY).toFloat()
        cursorX = (cursorX + dxDp * scale).coerceIn(minX.toFloat(), upperX)
        cursorY = (cursorY + dyDp * scale).coerceIn(minY.toFloat(), upperY)

        val nextIntX = cursorX.roundToInt()
        val nextIntY = cursorY.roundToInt()
        val moved = (nextIntX != prevIntX || nextIntY != prevIntY)
        if (moved) seq++
        return PointerUpdate(nextIntX, nextIntY, seq, moved)
    }

    companion object {
        /** Continuous C¹ smoothstep gain function — zero step jumps, zero runaway overshoot. */
        fun computeVelocityGain(velocityDpPerSec: Float, curve: PointerCurve): Float {
            if (curve == PointerCurve.LINEAR) return 1.0f
            val (minGain, maxGain, vLow, vHigh) = when (curve) {
                PointerCurve.BALANCED -> Quad(0.76f, 1.85f, 55f, 420f)
                PointerCurve.DYNAMIC -> Quad(0.68f, 2.50f, 50f, 520f)
                PointerCurve.LINEAR -> Quad(1.0f, 1.0f, 0f, 1f)
            }
            val t = ((velocityDpPerSec - vLow) / (vHigh - vLow)).coerceIn(0f, 1f)
            val smooth = t * t * (3f - 2f * t)
            return minGain + (maxGain - minGain) * smooth
        }

        private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)
    }
}

/**
 * Ensures 100% reliable double-clicks on touchscreens despite natural finger wobble.
 * Windows requires both clicks of a double-click to land within ~4px (`SM_CXDOUBLECLK`).
 * If a second tap arrives within `maxIntervalMs` and `maxDistanceDp` of the first tap,
 * we snap its target coordinate to the EXACT Windows pixel `(x, y)` of the first tap!
 */
class DoubleTapSnapper(
    private val maxIntervalMs: Long = 340L,
    private val maxDistanceDp: Float = 28f
) {
    private var lastTapMs: Long = 0L
    private var lastTouchXDp: Float = 0f
    private var lastTouchYDp: Float = 0f
    private var lastWinX: Int = 0
    private var lastWinY: Int = 0

    data class TapResolution(val winX: Int, val winY: Int, val isSecondOfDoubleTap: Boolean)

    fun resolveTap(nowMs: Long, touchXDp: Float, touchYDp: Float, mappedWinX: Int, mappedWinY: Int): TapResolution {
        val dt = nowMs - lastTapMs
        val dist = hypot((touchXDp - lastTouchXDp).toDouble(), (touchYDp - lastTouchYDp).toDouble()).toFloat()
        return if (lastTapMs > 0L && dt in 25L..maxIntervalMs && dist <= maxDistanceDp) {
            val snappedX = lastWinX
            val snappedY = lastWinY
            // Reset so a 3rd tap starts fresh (or natural triple-click behavior if rapid)
            lastTapMs = 0L
            TapResolution(snappedX, snappedY, isSecondOfDoubleTap = true)
        } else {
            lastTapMs = nowMs
            lastTouchXDp = touchXDp
            lastTouchYDp = touchYDp
            lastWinX = mappedWinX
            lastWinY = mappedWinY
            TapResolution(mappedWinX, mappedWinY, isSecondOfDoubleTap = false)
        }
    }

    fun reset() {
        lastTapMs = 0L
    }
}

/**
 * Smooth sub-tick vertical & horizontal wheel accumulator for Touch & Trackpad scrolling.
 * Converts continuous finger `dp` movement into Win32 wheel delta units (120 = 1 notch)
 * with consistent natural/classic direction and zero jerky jumps.
 */
class ScrollAccumulator {
    private var accumX: Float = 0f
    private var accumY: Float = 0f

    fun reset() {
        accumX = 0f
        accumY = 0f
    }

    /**
     * Feed finger delta in `dp`.
     * - `naturalScroll = true`: dragging finger UP (`dyDp < 0`) scrolls content DOWN (`wheelY < 0`),
     *   matching mobile touch scrolling.
     * - `naturalScroll = false`: classic scrollbar / inverted trackpad direction.
     *
     * Returns `(wheelX, wheelY)` ready for Win32 `MOUSEEVENTF_HWHEEL / MOUSEEVENTF_WHEEL`,
     * or `(0, 0)` until enough sub-tick motion accumulates.
     */
    fun accumulate(dxDp: Float, dyDp: Float, sensitivity: Float, naturalScroll: Boolean): Pair<Int, Int> {
        val dir = if (naturalScroll) 1f else -1f
        // ~18dp per standard scroll notch (120 Win32 wheel units) at 1.0x sensitivity
        val unitsPerDp = (120f / 18f) * sensitivity.coerceIn(0.4f, 3.0f)

        // Note Win32 semantics: positive WHEEL = scroll UP, negative WHEEL = scroll DOWN.
        // Natural touch drag DOWN (dyDp > 0) pulls page DOWN -> views content higher UP -> positive WHEEL (+).
        // Natural touch drag UP (dyDp < 0) pushes page UP -> views content lower DOWN -> negative WHEEL (-).
        accumY += dyDp * unitsPerDp * dir
        // Win32 HWHEEL: positive = scroll RIGHT, negative = scroll LEFT.
        // Natural drag LEFT (dxDp < 0) reveals content to the RIGHT -> positive HWHEEL (+).
        accumX += -dxDp * unitsPerDp * dir

        val emitY = if (abs(accumY) >= 24f) {
            val out = accumY.roundToInt()
            accumY -= out
            out
        } else 0

        val emitX = if (abs(accumX) >= 32f) {
            val out = accumX.roundToInt()
            accumX -= out
            out
        } else 0

        return emitX to emitY
    }
}

/**
 * Helper to compute a smooth server-side ROI rectangle after pinch zoom + pan.
 * Returns `null` when zoomed back out to full view (>= 0.96).
 */
fun computePinchPanRoi(
    curX: Float,
    curY: Float,
    curW: Float,
    curH: Float,
    zoomFactor: Float,
    panNormDx: Float,
    panNormDy: Float,
    anchorNormX: Float,
    anchorNormY: Float
): FloatArray? {
    val safeZoom = zoomFactor.coerceIn(0.5f, 2.0f)
    val newW = (curW / safeZoom).coerceIn(0.24f, 1.0f)
    val newH = (curH / safeZoom).coerceIn(0.24f, 1.0f)
    if (newW >= 0.96f && newH >= 0.96f) return null

    // Keep the point under the user's fingers anchored during pinch!
    val anchorWorldX = curX + anchorNormX.coerceIn(0f, 1f) * curW
    val anchorWorldY = curY + anchorNormY.coerceIn(0f, 1f) * curH
    val nx = (anchorWorldX - anchorNormX * newW - panNormDx * newW).coerceIn(0f, max(0f, 1f - newW))
    val ny = (anchorWorldY - anchorNormY * newH - panNormDy * newH).coerceIn(0f, max(0f, 1f - newH))
    return floatArrayOf(nx, ny, newW, newH)
}
