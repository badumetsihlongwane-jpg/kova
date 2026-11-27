package com.kova.workstation

import com.kova.workstation.ui.stage.DoubleTapSnapper
import com.kova.workstation.ui.stage.PointerCurve
import com.kova.workstation.ui.stage.ScrollAccumulator
import com.kova.workstation.ui.stage.StageProjection
import com.kova.workstation.ui.stage.TrackpadEngine
import com.kova.workstation.ui.stage.computeLiveImeDiff
import com.kova.workstation.ui.stage.computePinchPanRoi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PointerEngineTest {

    @Test
    fun stageProjectionPreservesAspectRatioAndRoundTripsCoordinates() {
        // 1920x1080 landscape Windows desktop rendered inside 1080x2200 portrait phone viewport
        val proj = StageProjection.compute(
            boxWidth = 1080f,
            boxHeight = 2200f,
            sourceWidth = 1920,
            sourceHeight = 1080,
            originX = 0,
            originY = 0
        )
        assertEquals(1080f, proj.fittedWidth, 0.01f)
        assertEquals(607.5f, proj.fittedHeight, 0.01f)
        assertEquals(0f, proj.offsetX, 0.01f)
        assertEquals((2200f - 607.5f) / 2f, proj.offsetY, 0.01f)

        // Top-left corner of fitted frame -> (0, 0)
        assertEquals(0 to 0, proj.viewportToScreen(proj.offsetX, proj.offsetY))
        // Bottom-right corner of fitted frame -> (1919, 1079)
        assertEquals(1919 to 1079, proj.viewportToScreen(proj.offsetX + proj.fittedWidth, proj.offsetY + proj.fittedHeight))

        // Arbitrary point round-trips within ±1px
        val vp = proj.screenToViewport(960, 540)
        assertNotNull(vp)
        val back = proj.viewportToScreen(vp!!.first, vp.second)
        assertTrue(abs(back.first - 960) <= 1)
        assertTrue(abs(back.second - 540) <= 1)
    }

    @Test
    fun stageProjectionHandlesWindowOriginAndRoiOffsets() {
        val proj = StageProjection.compute(
            boxWidth = 1000f,
            boxHeight = 1800f,
            sourceWidth = 500,
            sourceHeight = 900,
            originX = 320,
            originY = 140
        )
        // Exact 5:9 ratio matches 1000x1800 cleanly
        assertEquals(1000f, proj.fittedWidth, 0.01f)
        assertEquals(1800f, proj.fittedHeight, 0.01f)
        assertEquals(320 to 140, proj.viewportToScreen(0f, 0f))
        assertEquals(320 + 499 to 140 + 899, proj.viewportToScreen(1000f, 1800f))
    }

    @Test
    fun trackpadSubPixelAccumulationNeverDropsMicroDrags() {
        val engine = TrackpadEngine()
        engine.syncFromHost(500, 500, force = true)
        engine.beginGesture(500, 500)

        // 20 tiny 0.25dp nudges (each < 1 Windows px) must still accumulate cleanly and move cursor
        for (i in 0 until 20) {
            engine.applyDeltaDp(
                dxDp = 0.25f,
                dyDp = 0.25f,
                dtMs = 16L,
                sensitivity = 1.0f,
                curve = PointerCurve.LINEAR,
                referenceWidthPx = 1080,
                minX = 0,
                minY = 0,
                maxX = 1919,
                maxY = 1079
            )
        }
        assertTrue("Expected cursor to advance cleanly on micro-steps, got ${engine.currentX}", engine.currentX > 510)
        assertEquals(engine.currentX, engine.currentY)
        assertTrue(engine.seq > 0L)
    }

    @Test
    fun trackpadVelocityCurveIsSmoothMonotonicAndBounded() {
        // Linear must be constant 1.0 across all speeds
        assertEquals(1.0f, PointerEngineGain(10f, PointerCurve.LINEAR), 0.001f)
        assertEquals(1.0f, PointerEngineGain(800f, PointerCurve.LINEAR), 0.001f)

        // Balanced must increase monotonically with speed and never overshoot cap (1.85f)
        var prev = PointerEngineGain(0f, PointerCurve.BALANCED)
        for (v in 20..1200 step 20) {
            val g = PointerEngineGain(v.toFloat(), PointerCurve.BALANCED)
            assertTrue("Gain must be monotonic at v=$v ($g < $prev)", g >= prev - 1e-5f)
            assertTrue("Gain must remain bounded <= 1.85f", g <= 1.8501f)
            prev = g
        }
        assertTrue(PointerEngineGain(20f, PointerCurve.BALANCED) < 0.85f)
        assertEquals(1.85f, PointerEngineGain(1000f, PointerCurve.BALANCED), 0.001f)
    }

    @Test
    fun trackpadClampsAndReversesWithoutDriftInLinearMode() {
        val engine = TrackpadEngine()
        engine.syncFromHost(400, 300, force = true)
        engine.beginGesture(400, 300)

        // Sweep right then exact inverse sweep left -> zero drift
        repeat(15) {
            engine.applyDeltaDp(6f, -4f, 16L, 1.2f, PointerCurve.LINEAR, 1280, 0, 0, 1279, 719)
        }
        repeat(15) {
            engine.applyDeltaDp(-6f, 4f, 16L, 1.2f, PointerCurve.LINEAR, 1280, 0, 0, 1279, 719)
        }
        assertEquals(400, engine.currentX)
        assertEquals(300, engine.currentY)
    }

    @Test
    fun doubleTapSnapperEliminatesFingerWobble() {
        val snapper = DoubleTapSnapper()
        val first = snapper.resolveTap(nowMs = 1000L, touchXDp = 120f, touchYDp = 240f, mappedWinX = 640, mappedWinY = 480)
        assertFalse(first.isSecondOfDoubleTap)
        assertEquals(640, first.winX)

        // 2nd tap 150ms later, shifted 14dp on phone (which would normally miss Windows's 4px double-click box!)
        val second = snapper.resolveTap(nowMs = 1150L, touchXDp = 132f, touchYDp = 247f, mappedWinX = 678, mappedWinY = 502)
        assertTrue(second.isSecondOfDoubleTap)
        assertEquals(640, second.winX)
        assertEquals(480, second.winY)

        // Distant tap after timeout does not snap
        val third = snapper.resolveTap(nowMs = 2000L, touchXDp = 200f, touchYDp = 200f, mappedWinX = 900, mappedWinY = 400)
        assertFalse(third.isSecondOfDoubleTap)
        assertEquals(900, third.winX)
    }

    @Test
    fun scrollAccumulatorRespectsNaturalAndClassicDirections() {
        val acc = ScrollAccumulator()
        // Dragging finger UP (dyDp = -20dp) under natural scroll scrolls page DOWN (negative Win32 WHEEL)
        val (_, wheelNatural) = acc.accumulate(0f, -20f, sensitivity = 1.0f, naturalScroll = true)
        assertTrue("Expected negative Win32 wheel on upward natural drag, got $wheelNatural", wheelNatural < -80)

        acc.reset()
        // Under classic scroll, dragging UP produces positive wheel
        val (_, wheelClassic) = acc.accumulate(0f, -20f, sensitivity = 1.0f, naturalScroll = false)
        assertTrue("Expected positive Win32 wheel on upward classic drag, got $wheelClassic", wheelClassic > 80)
    }

    @Test
    fun pinchZoomAnchorsAroundMidpointAndResetsAtFullView() {
        val zoomed = computePinchPanRoi(0f, 0f, 1f, 1f, zoomFactor = 2.0f, panNormDx = 0f, panNormDy = 0f, anchorNormX = 0.5f, anchorNormY = 0.5f)
        assertNotNull(zoomed)
        assertEquals(0.25f, zoomed!![0], 0.01f)
        assertEquals(0.25f, zoomed[1], 0.01f)
        assertEquals(0.50f, zoomed[2], 0.01f)
        assertEquals(0.50f, zoomed[3], 0.01f)

        val unzoomed = computePinchPanRoi(0.25f, 0.25f, 0.5f, 0.5f, zoomFactor = 0.5f, panNormDx = 0f, panNormDy = 0f, anchorNormX = 0.5f, anchorNormY = 0.5f)
        assertNull(unzoomed)
    }

    @Test
    fun stageProjectionFillScreenSpansFullWidthAndHeight() {
        val proj = StageProjection.compute(
            boxWidth = 1080f,
            boxHeight = 2400f,
            sourceWidth = 720,
            sourceHeight = 1540,
            originX = 0,
            originY = 0,
            fillScreen = true
        )
        assertEquals(1080f, proj.fittedWidth, 0.01f)
        assertEquals(2400f, proj.fittedHeight, 0.01f)
        assertEquals(0f, proj.offsetX, 0.01f)
        assertEquals(0f, proj.offsetY, 0.01f)
        assertEquals(719 to 1539, proj.viewportToScreen(1080f, 2400f))
    }

    @Test
    fun liveImeDiffHandlesTypingAutocorrectSwipeAndBackspaces() {
        val p1 = computeLiveImeDiff("", "hello")
        assertEquals(0, p1.backspaces)
        assertEquals("hello", p1.insertText)

        // Autocorrect replacing tail "wrld" -> "world"
        val p2 = computeLiveImeDiff("hello wrld", "hello world")
        assertEquals(3, p2.backspaces)
        assertEquals("orld", p2.insertText)

        // Pure backspace
        val p3 = computeLiveImeDiff("hello", "hel")
        assertEquals(2, p3.backspaces)
        assertEquals("", p3.insertText)

        // No change
        assertTrue(computeLiveImeDiff("same", "same").isEmpty)
    }

    private fun PointerEngineGain(v: Float, curve: PointerCurve): Float =
        TrackpadEngine.computeVelocityGain(v, curve)
}
