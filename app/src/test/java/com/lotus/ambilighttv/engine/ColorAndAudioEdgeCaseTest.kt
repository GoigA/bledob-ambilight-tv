package com.lotus.ambilighttv.engine

import com.lotus.ambilighttv.model.RgbColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * Empirical Challenge and Stress Test Suite for Milestone M2.
 *
 * Verifies resilience, numerical stability, boundary safety, and zero-exception contracts
 * for [AudioFallbackEngine] and [VividEdgeColorEngine].
 */
class ColorAndAudioEdgeCaseTest {

    // =========================================================================
    // SECTION 1: AUDIO FALLBACK ENGINE ADVERSARIAL STRESS TESTS
    // =========================================================================

    @Test
    fun `challenge audio fallback - 100 consecutive frames with Visualizer failure produces zero exceptions and clean breathing`() {
        var visualizerAttempts = 0
        val engine = AudioFallbackEngine(
            visualizerProvider = {
                visualizerAttempts++
                throw SecurityException("SecurityException: RECORD_AUDIO permission missing on Android TV target")
            }
        )

        // 100 consecutive frames simulated at ~30 FPS
        val observedColors = mutableListOf<RgbColor>()
        for (frame in 0 until 100) {
            try {
                engine.start()
            } catch (t: Throwable) {
                fail("Frame $frame threw unhandled exception: ${t.javaClass.simpleName} - ${t.message}")
            }

            assertEquals("Engine status must remain RUNNING_WARM_LOUNGE", AudioEngineStatus.RUNNING_WARM_LOUNGE, engine.status)
            assertTrue("Visualizer must be marked blocked", engine.isVisualizerBlocked)

            val color = engine.nextAmbientRgb()
            assertNotNull("nextAmbientRgb() must never return null", color)
            assertTrue("Red channel out of bounds: ${color.r}", color.r in 0..255)
            assertTrue("Green channel out of bounds: ${color.g}", color.g in 0..255)
            assertTrue("Blue channel out of bounds: ${color.b}", color.b in 0..255)

            // Warm lounge breathing characteristic: Red is warm and dominant, blue is minimal
            assertTrue("Warm lounge must have warm color temperature (R >= G)", color.r >= color.g)
            assertTrue("Warm lounge must have warm color temperature (G >= B)", color.g >= color.b)
            observedColors.add(color)
        }

        // Hardware visualizer must only be attempted ONCE, completely eliminating exception storms
        assertEquals("Visualizer constructor must only be attempted on the initial failure", 1, visualizerAttempts)

        // Verify breathing dynamics over 100 frames (smoothness check)
        for (i in 1 until observedColors.size) {
            val delta = observedColors[i].deltaTo(observedColors[i - 1])
            assertTrue("Frame-to-frame breathing delta should be gentle and smooth (got delta=$delta)", delta <= 6)
        }
    }

    @Test
    fun `challenge audio fallback - nextAmbientRgb produces valid bounded colors across full phase sweep`() {
        val engine = AudioFallbackEngine()

        // Test quiet lounge mode across 2,000 steps (multiple sinusoidal cycles)
        engine.resetEnergiesForTesting()
        repeat(2000) { step ->
            val color = engine.nextAmbientRgb()
            assertTrue("Step $step: r (${color.r}) out of bounds", color.r in 0..255)
            assertTrue("Step $step: g (${color.g}) out of bounds", color.g in 0..255)
            assertTrue("Step $step: b (${color.b}) out of bounds", color.b in 0..255)
        }

        // Test audio-reactive mode across 2,000 steps with random energies
        val rng = Random(42)
        repeat(2000) { step ->
            // Simulate random incoming FFT energies
            val mockFft = ByteArray(64)
            rng.nextBytes(mockFft)
            engine.processFftData(mockFft)

            val reactiveColor = engine.nextAmbientRgb()
            assertTrue("Step $step reactive: r (${reactiveColor.r}) out of bounds", reactiveColor.r in 0..255)
            assertTrue("Step $step reactive: g (${reactiveColor.g}) out of bounds", reactiveColor.g in 0..255)
            assertTrue("Step $step reactive: b (${reactiveColor.b}) out of bounds", reactiveColor.b in 0..255)
        }
    }

    @Test
    fun `challenge audio fallback - FFT data edge cases null empty odd-sized and extreme signed bytes`() {
        val engine = AudioFallbackEngine(AudioEngineConfig(energySmoothingFactor = 1.0f))

        // Case 1: Null array
        engine.processFftData(null)
        assertEquals(0f, engine.rawBass, 0.0001f)
        assertEquals(0f, engine.rawMid, 0.0001f)

        // Case 2: Empty array
        engine.processFftData(ByteArray(0))
        assertEquals(0f, engine.rawBass, 0.0001f)
        assertEquals(0f, engine.rawMid, 0.0001f)

        // Case 3: Truncated arrays of varying sizes below minFftSize (1..15)
        for (size in 1 until 16) {
            val truncated = ByteArray(size) { 127 }
            engine.processFftData(truncated)
            assertEquals("Size $size should be safely ignored", 0f, engine.rawBass, 0.0001f)
            assertEquals("Size $size should be safely ignored", 0f, engine.rawMid, 0.0001f)
        }

        // Case 4: Odd-sized arrays (e.g. 17, 23, 31, 63)
        for (oddSize in listOf(17, 19, 23, 27, 31, 33, 63)) {
            val oddArray = ByteArray(oddSize) { -64 }
            try {
                engine.processFftData(oddArray)
                assertTrue("Odd array size $oddSize rawBass must be in 0..1", engine.rawBass in 0f..1f)
                assertTrue("Odd array size $oddSize rawMid must be in 0..1", engine.rawMid in 0f..1f)
            } catch (t: Throwable) {
                fail("Odd array size $oddSize caused crash: ${t.message}")
            }
        }

        // Case 5: All signed negative minimum bytes (-128)
        val allMinBytes = ByteArray(128) { (-128).toByte() }
        engine.processFftData(allMinBytes)
        assertTrue("rawBass must be clamped to 1.0f", engine.rawBass in 0f..1f)
        assertTrue("rawMid must be clamped to 1.0f", engine.rawMid in 0f..1f)

        // Case 6: All signed maximum bytes (127)
        val allMaxBytes = ByteArray(128) { 127.toByte() }
        engine.processFftData(allMaxBytes)
        assertTrue("rawBass must be in 0..1", engine.rawBass in 0f..1f)
        assertTrue("rawMid must be in 0..1", engine.rawMid in 0f..1f)

        // Case 7: Alternating extremes: -128, 127, -128, 127...
        val alternating = ByteArray(128) { i -> if (i % 2 == 0) (-128).toByte() else 127.toByte() }
        engine.processFftData(alternating)
        assertTrue("rawBass must be clamped to 1.0f", engine.rawBass in 0f..1f)
        assertTrue("rawMid must be clamped to 1.0f", engine.rawMid in 0f..1f)
    }

    @Test
    fun `challenge audio fallback - in-place getAmbientColor buffer safety`() {
        val engine = AudioFallbackEngine()

        // Exactly 3 elements
        val out3 = IntArray(3)
        val returned3 = engine.getAmbientColor(out3)
        assertTrue("Must return exact reference", out3 === returned3)
        assertTrue("r in 0..255", out3[0] in 0..255)
        assertTrue("g in 0..255", out3[1] in 0..255)
        assertTrue("b in 0..255", out3[2] in 0..255)

        // Large buffer (10 elements) - must only mutate first 3
        val out10 = IntArray(10) { 999 }
        engine.getAmbientColor(out10)
        assertTrue("r in 0..255", out10[0] in 0..255)
        assertTrue("g in 0..255", out10[1] in 0..255)
        assertTrue("b in 0..255", out10[2] in 0..255)
        for (i in 3 until 10) {
            assertEquals("Index $i must remain untouched", 999, out10[i])
        }
    }

    // =========================================================================
    // SECTION 2: VIVID EDGE COLOR ENGINE ADVERSARIAL STRESS TESTS
    // =========================================================================

    @Test
    fun `challenge vivid engine - applySaturationBoost output channels are strictly within 0 to 255 for extreme inputs`() {
        val engine = VividEdgeColorEngine()

        // Matrix of extreme float inputs
        val testInputs = listOf(
            Triple(0f, 0f, 0f),
            Triple(255f, 255f, 255f),
            Triple(255f, 0f, 0f),
            Triple(0f, 255f, 0f),
            Triple(0f, 0f, 255f),
            Triple(255f, 255f, 0f),
            Triple(0f, 255f, 255f),
            Triple(255f, 0f, 255f),
            Triple(128f, 128f, 128f),
            Triple(1f, 1f, 1f),
            Triple(0.001f, 0.001f, 0.001f),
            Triple(0.0005f, 0.0005f, 0.0005f),
            // Out of bounds / adversarial floats
            Triple(-100f, 300f, 500f),
            Triple(-500f, -500f, -500f),
            Triple(1000f, 2000f, 3000f)
        )

        val testBoosts = listOf(0.0f, 0.5f, 1.0f, 1.25f, 1.45f, 2.0f, 5.0f, 10.0f, -1.0f)

        for ((r, g, b) in testInputs) {
            for (boost in testBoosts) {
                val color = engine.applySaturationBoost(r, g, b, boost)
                assertTrue("Input ($r,$g,$b, boost=$boost) produced R=${color.r} outside 0..255", color.r in 0..255)
                assertTrue("Input ($r,$g,$b, boost=$boost) produced G=${color.g} outside 0..255", color.g in 0..255)
                assertTrue("Input ($r,$g,$b, boost=$boost) produced B=${color.b} outside 0..255", color.b in 0..255)
            }
        }
    }

    @Test
    fun `challenge vivid engine - pure black frames and noise floor rejection`() {
        val engine = VividEdgeColorEngine()
        val width = 64
        val height = 36

        // Case 1: Pure black ARGB 0x00000000 and 0xFF000000
        val pureBlackZero = IntArray(width * height) { 0x00000000 }
        val r1 = engine.processFrame(pureBlackZero, width, height)
        assertTrue(r1.isBlackFrame)
        assertEquals(RgbColor.BLACK, r1.color)

        val pureBlackOpaque = IntArray(width * height) { (0xFF shl 24) }
        val r2 = engine.processFrame(pureBlackOpaque, width, height)
        assertTrue(r2.isBlackFrame)
        assertEquals(RgbColor.BLACK, r2.color)

        // Case 2: Noise below blackLumaThreshold (<= 10) everywhere
        val noiseBelowFloor = IntArray(width * height) { (0xFF shl 24) or (10 shl 16) or (8 shl 8) or 10 }
        val r3 = engine.processFrame(noiseBelowFloor, width, height)
        assertTrue("Sub-threshold noise must be treated as black frame", r3.isBlackFrame)
        assertEquals(RgbColor.BLACK, r3.color)

        // Case 3: Exactly 1 pixel above threshold - should still be treated as black frame because totalWeight <= 0.0001 or noise threshold
        val singleDimPixel = IntArray(width * height) { 0 }
        singleDimPixel[width * 5 + 5] = (0xFF shl 24) or (12 shl 16) or (12 shl 8) or 12
        val r4 = engine.processFrame(singleDimPixel, width, height)
        assertTrue(r4.isBlackFrame)
    }

    @Test
    fun `challenge vivid engine - stress test 1000 consecutive black frames does not underflow or NaN`() {
        val engine = VividEdgeColorEngine()
        val width = 16
        val height = 9

        // Initialize with a bright red frame first
        val redFrame = IntArray(width * height) { (0xFF shl 24) or (255 shl 16) }
        val init = engine.processFrame(redFrame, width, height)
        assertTrue(init.r > 200)

        // Feed 1000 black frames
        val blackFrame = IntArray(width * height)
        for (i in 1..1000) {
            val result = engine.processFrame(blackFrame, width, height)
            assertTrue(result.isBlackFrame)
            assertTrue("r=${result.r} must be in 0..255", result.r in 0..255)
            assertTrue("g=${result.g} must be in 0..255", result.g in 0..255)
            assertTrue("b=${result.b} must be in 0..255", result.b in 0..255)
            if (i >= VividEdgeColorEngine.DRM_BLACK_FRAME_TRIGGER_COUNT) {
                assertTrue("DRM fallback must be active at frame $i", result.isDrmFallbackActive)
            } else {
                assertFalse("DRM fallback must not be active before threshold at frame $i", result.isDrmFallbackActive)
            }
            if (i >= 30) {
                // Exponential decay (0.75^30 * 255 < 0.05) fully decays to 0
                assertEquals("Color should fully decay to 0 at frame $i", 0, result.r)
                assertEquals("Color should fully decay to 0 at frame $i", 0, result.g)
                assertEquals("Color should fully decay to 0 at frame $i", 0, result.b)
            }
        }
    }

    @Test
    fun `challenge vivid engine - heavy letterbox bars top and bottom cropping`() {
        val engine = VividEdgeColorEngine()
        val width = 64
        val height = 36 // 16:9 aspect ratio

        // 21:9 Cinemascope letterbox on 16:9 screen:
        // Active height is ~27 rows. Top 4 rows and bottom 5 rows are pure black letterbox bars.
        val frame = IntArray(width * height)
        val activeTop = 4
        val activeBottom = 31

        // Active video: vibrant neon blue
        val neonBlue = (0xFF shl 24) or (0 shl 16) or (180 shl 8) or 255
        for (y in activeTop..activeBottom) {
            for (x in 0 until width) {
                frame[y * width + x] = neonBlue
            }
        }

        val result = engine.processFrame(frame, width, height)
        assertFalse("Frame with active video is not black", result.isBlackFrame)
        assertFalse(result.isDrmFallbackActive)
        assertEquals("Top letterbox correctly identified", activeTop, result.activeTopRow)
        assertEquals("Bottom letterbox correctly identified", activeBottom, result.activeBottomRow)
        assertTrue("Blue channel must dominate", result.b > 200)
        assertTrue("Red channel must be near zero", result.r < 30)
    }

    @Test
    fun `challenge vivid engine - fully saturated primary colors preserve dominant hue and saturation`() {
        val engine = VividEdgeColorEngine()
        val width = 16
        val height = 16

        // Red
        val redFrame = IntArray(width * height) { (0xFF shl 24) or (255 shl 16) }
        val redRes = engine.processFrame(redFrame, width, height)
        assertTrue("Red dominates", redRes.r > 200 && redRes.g == 0 && redRes.b == 0)

        // Green
        engine.reset()
        val greenFrame = IntArray(width * height) { (0xFF shl 24) or (255 shl 8) }
        val greenRes = engine.processFrame(greenFrame, width, height)
        assertTrue("Green dominates", greenRes.g > 200 && greenRes.r == 0 && greenRes.b == 0)

        // Blue
        engine.reset()
        val blueFrame = IntArray(width * height) { (0xFF shl 24) or 255 }
        val blueRes = engine.processFrame(blueFrame, width, height)
        assertTrue("Blue dominates", blueRes.b > 200 && blueRes.r == 0 && blueRes.g == 0)
    }

    @Test
    fun `challenge vivid engine - minimal frame sizes (1x1, 2x2, 3x3) handle gracefully without crash`() {
        val engine = VividEdgeColorEngine()

        // 1x1 frame
        val frame1x1 = intArrayOf((0xFF shl 24) or (200 shl 16) or (100 shl 8) or 50)
        // 1x1 activeHeight <= 2 -> handleBlackFrame
        val r1x1 = engine.processFrame(frame1x1, 1, 1)
        assertTrue(r1x1.isBlackFrame)

        // 2x2 frame
        val frame2x2 = IntArray(4) { (0xFF shl 24) or (200 shl 16) }
        val r2x2 = engine.processFrame(frame2x2, 2, 2)
        assertTrue(r2x2.isBlackFrame) // activeHeight <= 2

        // 3x3 frame with active video
        val frame3x3 = IntArray(9) { (0xFF shl 24) or (220 shl 16) or (80 shl 8) or 30 }
        val r3x3 = engine.processFrame(frame3x3, 3, 3)
        assertFalse(r3x3.isBlackFrame)
        assertTrue(r3x3.r > 180)
    }

    @Test
    fun `challenge vivid engine - rapid profile switching does not destabilize EMA state`() {
        val engine = VividEdgeColorEngine()
        val width = 16
        val height = 9
        val frame = IntArray(width * height) { (0xFF shl 24) or (180 shl 16) or (120 shl 8) or 60 }

        val profiles = listOf(
            VividEdgeColorEngine.AmbilightProfile.CINEMA,
            VividEdgeColorEngine.AmbilightProfile.DYNAMIC,
            VividEdgeColorEngine.AmbilightProfile.GAMING
        )

        for (i in 0 until 90) {
            engine.profile = profiles[i % profiles.size]
            val result = engine.processFrame(frame, width, height)
            assertFalse(result.isBlackFrame)
            assertTrue(result.r in 0..255)
            assertTrue(result.g in 0..255)
            assertTrue(result.b in 0..255)
        }
    }
}

