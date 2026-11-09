package com.lotus.ambilighttv.engine

import com.lotus.ambilighttv.model.RgbColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VividEdgeColorEngineTest {

    private lateinit var engine: VividEdgeColorEngine

    @Before
    fun setUp() {
        engine = VividEdgeColorEngine(VividEdgeColorEngine.AmbilightProfile.DYNAMIC)
    }

    @Test
    fun `letterbox detection crops top and bottom black bars correctly`() {
        val width = 16
        val height = 12
        val frame = IntArray(width * height)

        // Top 2 rows and bottom 2 rows are pure black; rows 2..9 are bright amber
        val amberArgb = (0xFF shl 24) or (255 shl 16) or (140 shl 8) or 40
        for (y in 2 until 10) {
            for (x in 0 until width) {
                frame[y * width + x] = amberArgb
            }
        }

        val result = engine.processFrame(frame, width, height)
        assertFalse(result.isBlackFrame)
        assertFalse(result.isDrmFallbackActive)
        assertEquals(2, result.activeTopRow)
        assertEquals(9, result.activeBottomRow)
        assertTrue("Red channel should be dominant", result.r > 200)
        assertTrue("Amber red > green", result.r > result.g)
        assertTrue("Amber green > blue", result.g > result.b)
    }

    @Test
    fun `letterbox detection tolerates single noisy pixel in black bars`() {
        val width = 16
        val height = 12
        val frame = IntArray(width * height)

        // Put a single noisy pixel in row 0 (noise ceiling is width / 16 = 1)
        frame[0 * width + 5] = (0xFF shl 24) or (150 shl 16) or (150 shl 8) or 150

        // Active video rows 2..9
        val blueArgb = (0xFF shl 24) or (10 shl 16) or (80 shl 8) or 240
        for (y in 2 until 10) {
            for (x in 0 until width) {
                frame[y * width + x] = blueArgb
            }
        }

        val result = engine.processFrame(frame, width, height)
        assertEquals(2, result.activeTopRow)
        assertEquals(9, result.activeBottomRow)
        assertTrue("Blue channel should be dominant", result.b > result.r)
    }

    @Test
    fun `entirely black frame triggers handleBlackFrame and returns black color`() {
        val width = 16
        val height = 9
        val blackFrame = IntArray(width * height) // all 0

        val result = engine.processFrame(blackFrame, width, height)
        assertTrue(result.isBlackFrame)
        assertFalse(result.isDrmFallbackActive)
        assertEquals(0, result.r)
        assertEquals(0, result.g)
        assertEquals(0, result.b)
        assertEquals(RgbColor.BLACK, result.color)
    }

    @Test
    fun `consecutive black frames trigger DRM fallback at exact configured threshold`() {
        val width = 16
        val height = 9
        val blackFrame = IntArray(width * height)

        val triggerCount = VividEdgeColorEngine.DRM_BLACK_FRAME_TRIGGER_COUNT

        // Frames 1 until triggerCount - 1
        repeat(triggerCount - 1) { count ->
            val result = engine.processFrame(blackFrame, width, height)
            assertTrue(result.isBlackFrame)
            assertFalse(
                "DRM fallback should NOT be active at frame count ${count + 1}",
                result.isDrmFallbackActive
            )
            assertEquals(count + 1, engine.currentConsecutiveBlackFrames)
        }

        // Exact trigger frame
        val triggerResult = engine.processFrame(blackFrame, width, height)
        assertTrue(triggerResult.isBlackFrame)
        assertTrue(
            "DRM fallback MUST become active at frame count $triggerCount",
            triggerResult.isDrmFallbackActive
        )
        assertEquals(triggerCount, engine.currentConsecutiveBlackFrames)

        // Subsequent frames remain in DRM fallback
        val nextResult = engine.processFrame(blackFrame, width, height)
        assertTrue(nextResult.isDrmFallbackActive)
        assertEquals(triggerCount + 1, engine.currentConsecutiveBlackFrames)
    }

    @Test
    fun `bright frame immediately recovers from DRM fallback and clears black frame counter`() {
        val width = 16
        val height = 9
        val blackFrame = IntArray(width * height)

        repeat(VividEdgeColorEngine.DRM_BLACK_FRAME_TRIGGER_COUNT + 5) {
            engine.processFrame(blackFrame, width, height)
        }
        assertTrue(engine.currentConsecutiveBlackFrames > 25)

        // Non-black frame arrives
        val brightFrame = IntArray(width * height) { (0xFF shl 24) or (30 shl 16) or (230 shl 8) or 50 }
        val recovered = engine.processFrame(brightFrame, width, height)

        assertFalse(recovered.isBlackFrame)
        assertFalse(recovered.isDrmFallbackActive)
        assertEquals(0, engine.currentConsecutiveBlackFrames)
        assertTrue("Green should be dominant", recovered.g > 200)
    }

    @Test
    fun `black frame fades previous colors exponentially with fade decay`() {
        val width = 16
        val height = 9
        val redFrame = IntArray(width * height) { (0xFF shl 24) or (255 shl 16) or (0 shl 8) or 0 }

        val firstResult = engine.processFrame(redFrame, width, height)
        val initialRed = firstResult.r
        assertTrue("Initial red should be high", initialRed > 200)

        val blackFrame = IntArray(width * height)
        val fadeResult1 = engine.processFrame(blackFrame, width, height)
        assertTrue(fadeResult1.isBlackFrame)
        assertTrue("Red channel should decay on black frame", fadeResult1.r < initialRed)

        val fadeResult2 = engine.processFrame(blackFrame, width, height)
        assertTrue("Red channel should decay further", fadeResult2.r < fadeResult1.r)
    }

    @Test
    fun `saturation boost amplifies pastel muted colors without shifting dominant hue`() {
        // Pastel orange: R=210, G=140, B=90
        val boosted = engine.applySaturationBoost(210f, 140f, 90f, boost = 1.5f)

        assertTrue("Red channel remains dominant", boosted.r > boosted.g)
        assertTrue("Green channel remains greater than blue", boosted.g > boosted.b)
        // Saturation boosted should widen gap between max and min channel
        val originalDelta = 210 - 90
        val boostedDelta = boosted.r - boosted.b
        assertTrue("Boosted delta ($boostedDelta) should exceed original delta ($originalDelta)", boostedDelta >= originalDelta)
    }

    @Test
    fun `saturation boost preserves neutral gray and black without color tinting`() {
        val gray = engine.applySaturationBoost(128f, 128f, 128f, boost = 1.5f)
        assertEquals(128, gray.r)
        assertEquals(128, gray.g)
        assertEquals(128, gray.b)

        val black = engine.applySaturationBoost(0f, 0f, 0f, boost = 1.5f)
        assertEquals(0, black.r)
        assertEquals(0, black.g)
        assertEquals(0, black.b)

        val darkGray = engine.applySaturationBoost(5f, 5f, 5f, boost = 1.5f)
        assertEquals(5, darkGray.r)
        assertEquals(5, darkGray.g)
        assertEquals(5, darkGray.b)
    }

    @Test
    fun `saturation boost overload accepting RgbColor value class functions identically`() {
        val color = RgbColor.fromRgb(220, 60, 30)
        val boostedFromColor = engine.applySaturationBoost(color, 1.45f)
        val boostedFromFloats = engine.applySaturationBoost(220f, 60f, 30f, 1.45f)

        assertEquals(boostedFromFloats, boostedFromColor)
    }

    @Test
    fun `temporal smoothing cinema profile adapts more gently than gaming profile`() {
        val cinemaEngine = VividEdgeColorEngine(VividEdgeColorEngine.AmbilightProfile.CINEMA)
        val gamingEngine = VividEdgeColorEngine(VividEdgeColorEngine.AmbilightProfile.GAMING)

        val width = 16
        val height = 9
        val redFrame = IntArray(width * height) { (0xFF shl 24) or (255 shl 16) or (0 shl 8) or 0 }
        val blueFrame = IntArray(width * height) { (0xFF shl 24) or (0 shl 16) or (0 shl 8) or 255 }

        // Step 1: Initialize both engines with Red frame
        cinemaEngine.processFrame(redFrame, width, height)
        gamingEngine.processFrame(redFrame, width, height)

        // Step 2: Feed a Blue frame to both
        val cinemaResult = cinemaEngine.processFrame(blueFrame, width, height)
        val gamingResult = gamingEngine.processFrame(blueFrame, width, height)

        // Cinema (alpha = 0.22) retains much more of previous red than Gaming (alpha = 0.78)
        assertTrue(
            "Cinema red (${cinemaResult.r}) should be greater than Gaming red (${gamingResult.r})",
            cinemaResult.r > gamingResult.r
        )
        // Gaming adopts new blue much faster than Cinema
        assertTrue(
            "Gaming blue (${gamingResult.b}) should be greater than Cinema blue (${cinemaResult.b})",
            gamingResult.b > cinemaResult.b
        )
    }

    @Test
    fun `first frame initializes EMA state immediately without startup lag`() {
        assertFalse(engine.isInitialized)

        val width = 16
        val height = 9
        val greenFrame = IntArray(width * height) { (0xFF shl 24) or (0 shl 16) or (240 shl 8) or 0 }
        val result = engine.processFrame(greenFrame, width, height)

        assertTrue(engine.isInitialized)
        assertTrue("Green channel initializes immediately to high value", result.g > 200)
        assertEquals(0, result.r)
        assertEquals(0, result.b)
    }

    @Test
    fun `reset clears initialized state and black frame tracking`() {
        val width = 16
        val height = 9
        val greenFrame = IntArray(width * height) { (0xFF shl 24) or (0 shl 16) or (240 shl 8) or 0 }
        engine.processFrame(greenFrame, width, height)
        assertTrue(engine.isInitialized)

        val blackFrame = IntArray(width * height)
        repeat(5) { engine.processFrame(blackFrame, width, height) }
        assertEquals(5, engine.currentConsecutiveBlackFrames)

        engine.reset()

        assertFalse(engine.isInitialized)
        assertEquals(0, engine.currentConsecutiveBlackFrames)
    }

    @Test
    fun `spatial edge weighting favors border pixels over center content`() {
        val width = 8
        val height = 8
        val frame = IntArray(width * height)

        // Outer border (25% border = 2 rows/cols) filled with pure Cyan
        // Center (rows 2..5, cols 2..5) filled with dark Red
        val cyanArgb = (0xFF shl 24) or (0 shl 16) or (220 shl 8) or 255
        val darkRedArgb = (0xFF shl 24) or (160 shl 16) or (20 shl 8) or 20

        for (y in 0 until height) {
            val isBorderY = y < 2 || y >= 6
            for (x in 0 until width) {
                val isBorderX = x < 2 || x >= 6
                frame[y * width + x] = if (isBorderX || isBorderY) cyanArgb else darkRedArgb
            }
        }

        val result = engine.processFrame(frame, width, height)
        assertTrue("Cyan border should dominate over center dark red (G > R)", result.g > result.r)
        assertTrue("Cyan border should dominate over center dark red (B > R)", result.b > result.r)
    }

    @Test
    fun `defensive boundary checks handle invalid inputs safely`() {
        // Zero width
        val r1 = engine.processFrame(IntArray(10), 0, 10)
        assertTrue(r1.isBlackFrame)
        assertTrue(r1.isDrmFallbackActive)
        assertEquals(RgbColor.BLACK, r1.color)

        // Negative height
        val r2 = engine.processFrame(IntArray(10), 10, -5)
        assertTrue(r2.isBlackFrame)
        assertTrue(r2.isDrmFallbackActive)

        // Undersized pixel array
        val r3 = engine.processFrame(IntArray(5), 10, 10)
        assertTrue(r3.isBlackFrame)
        assertTrue(r3.isDrmFallbackActive)
    }

    @Test
    fun `FrameAnalysisResult provides zero-allocation color and backward compatible channel getters`() {
        val color = RgbColor.fromRgb(200, 150, 75)
        val result = VividEdgeColorEngine.FrameAnalysisResult(
            color = color,
            isBlackFrame = false,
            isDrmFallbackActive = false,
            activeTopRow = 2,
            activeBottomRow = 10
        )

        assertEquals(200, result.r)
        assertEquals(150, result.g)
        assertEquals(75, result.b)
        assertEquals(color, result.color)

        // Test backward-compatible secondary constructor
        val legacyResult = VividEdgeColorEngine.FrameAnalysisResult(
            r = 100,
            g = 50,
            b = 25,
            isBlackFrame = true,
            isDrmFallbackActive = true,
            activeTopRow = 0,
            activeBottomRow = 0
        )
        assertEquals(100, legacyResult.r)
        assertEquals(50, legacyResult.g)
        assertEquals(25, legacyResult.b)
        assertEquals(100, legacyResult.color.r)
        assertEquals(50, legacyResult.color.g)
        assertEquals(25, legacyResult.color.b)
    }

    @Test
    fun `custom VividColorConfig overrides default parameters properly`() {
        val customConfig = VividColorConfig(
            drmBlackFrameTriggerCount = 5,
            blackFrameFadeDecay = 0.50f
        )
        val customEngine = VividEdgeColorEngine(config = customConfig)

        val width = 16
        val height = 9
        val blackFrame = IntArray(width * height)

        repeat(4) {
            val res = customEngine.processFrame(blackFrame, width, height)
            assertFalse(res.isDrmFallbackActive)
        }

        // At 5th frame, DRM triggers due to custom threshold
        val triggered = customEngine.processFrame(blackFrame, width, height)
        assertTrue(triggered.isDrmFallbackActive)
        assertEquals(5, customEngine.currentConsecutiveBlackFrames)
    }
}
