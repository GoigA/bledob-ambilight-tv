package com.lotus.ambilighttv.engine

import android.media.audiofx.Visualizer
import com.lotus.ambilighttv.model.RgbColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt

class AudioFallbackEngineTest {

    // =========================================================================
    // 1. STATE MACHINE & EXCEPTION STORM PREVENTION
    // =========================================================================

    @Test
    fun `engine starts in IDLE status with zero energy and zero phase`() {
        val engine = AudioFallbackEngine()
        assertEquals(AudioEngineStatus.IDLE, engine.status)
        assertFalse(engine.isVisualizerBlocked)
        assertEquals(0f, engine.bassEnergy, 0.0001f)
        assertEquals(0f, engine.midEnergy, 0.0001f)
        assertEquals(0f, engine.rawBass, 0.0001f)
        assertEquals(0f, engine.rawMid, 0.0001f)
        assertEquals(0.0, engine.currentPhase, 0.0001)
    }

    @Test
    fun `visualizer failure triggers clean transition to RUNNING_WARM_LOUNGE without throwing`() {
        var providerInvocationCount = 0
        val engine = AudioFallbackEngine(
            visualizerProvider = {
                providerInvocationCount++
                throw SecurityException("RECORD_AUDIO permission denied by Android TV policy")
            }
        )

        // Initial start triggers provider, catches exception, and transitions to warm lounge
        engine.start()

        assertEquals(1, providerInvocationCount)
        assertEquals(AudioEngineStatus.RUNNING_WARM_LOUNGE, engine.status)
        assertTrue(engine.isVisualizerBlocked)
    }

    @Test
    fun `repeated start calls in 30 Hz loop produce zero exceptions and zero repeated provider invocations`() {
        var providerInvocationCount = 0
        val engine = AudioFallbackEngine(
            visualizerProvider = {
                providerInvocationCount++
                throw RuntimeException("Cannot initialize visualizer engine, error: -1 (OEM session 0 blocked)")
            }
        )

        // Simulate 100 consecutive frames at 30 FPS (~3.3 seconds of DRM playback)
        repeat(100) { frameIndex ->
            try {
                engine.start()
            } catch (t: Throwable) {
                fail("Frame $frameIndex threw exception: ${t.message}. Exception storm must be eliminated!")
            }
        }

        // Provider must have been called exactly ONCE on the very first frame
        assertEquals("Visualizer constructor must only be attempted once", 1, providerInvocationCount)
        assertEquals(AudioEngineStatus.RUNNING_WARM_LOUNGE, engine.status)
        assertTrue(engine.isVisualizerBlocked)
    }

    @Test
    fun `blocked visualizer persists across stop and start cycles without exception storm`() {
        var providerInvocationCount = 0
        val engine = AudioFallbackEngine(
            visualizerProvider = {
                providerInvocationCount++
                throw SecurityException("Permission missing")
            }
        )

        // First DRM session
        engine.start()
        assertEquals(1, providerInvocationCount)
        assertEquals(AudioEngineStatus.RUNNING_WARM_LOUNGE, engine.status)

        // Non-DRM video arrives, service stops audio engine
        engine.stop()
        assertEquals(AudioEngineStatus.IDLE, engine.status)
        assertTrue("Blocked flag must remain remembered across sessions", engine.isVisualizerBlocked)

        // Second DRM session arrives later
        engine.start()
        // Must immediately resume warm lounge without retrying broken visualizer
        assertEquals(1, providerInvocationCount)
        assertEquals(AudioEngineStatus.RUNNING_WARM_LOUNGE, engine.status)
    }

    @Test
    fun `forceRetry allows re-probing visualizer when permissions are granted`() {
        var attempts = 0
        var shouldSucceed = false
        val engine = AudioFallbackEngine(
            visualizerProvider = {
                attempts++
                if (!shouldSucceed) {
                    throw SecurityException("Permission not yet granted")
                }
                Visualizer(0)
            }
        )

        engine.start()
        assertEquals(1, attempts)
        assertEquals(AudioEngineStatus.RUNNING_WARM_LOUNGE, engine.status)

        // User grants permission in system settings -> forceRetry
        shouldSucceed = true
        engine.stop()
        engine.start(forceRetry = true)

        assertEquals(2, attempts)
    }

    @Test
    fun `markPermanentFailure halts execution and safely emits fallback color`() {
        val engine = AudioFallbackEngine()
        engine.markPermanentFailure()

        assertEquals(AudioEngineStatus.FAILED_PERMANENTLY, engine.status)

        // start() must have no effect
        engine.start()
        assertEquals(AudioEngineStatus.FAILED_PERMANENTLY, engine.status)

        // nextAmbientRgb() safely returns CINEMA_WARM
        val color = engine.nextAmbientRgb()
        assertEquals(RgbColor.CINEMA_WARM, color)

        // resetPermanentFailure restores IDLE
        engine.resetPermanentFailure()
        assertEquals(AudioEngineStatus.IDLE, engine.status)
    }

    // =========================================================================
    // 2. DETERMINISTIC WARM AMBIENT LOUNGE BREATHING
    // =========================================================================

    @Test
    fun `warm lounge breathing follows deterministic sinusoidal curve at baseline phase`() {
        val engine = AudioFallbackEngine()
        engine.resetEnergiesForTesting()
        engine.setPhaseForTesting(0.0)

        // At phase = 0.0:
        // breath = 0.85 + 0.15 * sin(0.0) = 0.85
        // r = round(210 * 0.85) = 179
        // g = round(115 * 0.85) = 98
        // b = round(45 * 0.85) = 38
        val color = engine.nextAmbientRgb()

        assertEquals(179, color.r)
        assertEquals(98, color.g)
        assertEquals(38, color.b)
        assertTrue("Red channel must dominate green", color.r > color.g)
        assertTrue("Green channel must dominate blue", color.g > color.b)
    }

    @Test
    fun `warm lounge breathing reaches peak brightness at positive quarter cycle`() {
        val engine = AudioFallbackEngine()
        engine.resetEnergiesForTesting()

        // Peak occurs when phase * 0.4 = PI / 2 => phase = (PI / 2) / 0.4 = 1.25 * PI
        val peakPhase = (PI / 2.0) / 0.4
        // Engine advances phase by phaseStepBase (0.06) before computing, so pre-set:
        engine.setPhaseForTesting(peakPhase - 0.06)

        // At peak: breath = 0.85 + 0.15 * 1.0 = 1.0
        // r = 210, g = 115, b = 45 (matches RgbColor.CINEMA_WARM)
        val color = engine.nextAmbientRgb()

        assertEquals(210, color.r)
        assertEquals(115, color.g)
        assertEquals(45, color.b)
        assertEquals(RgbColor.CINEMA_WARM, color)
    }

    @Test
    fun `warm lounge breathing reaches trough brightness at negative quarter cycle`() {
        val engine = AudioFallbackEngine()
        engine.resetEnergiesForTesting()

        // Trough occurs when phase * 0.4 = 3 * PI / 2 => phase = (1.5 * PI) / 0.4 = 3.75 * PI
        val troughPhase = (1.5 * PI) / 0.4
        engine.setPhaseForTesting(troughPhase - 0.06)

        // At trough: breath = 0.85 + 0.15 * (-1.0) = 0.70
        // r = round(210 * 0.70) = 147
        // g = round(115 * 0.70) = 81
        // b = round(45 * 0.70) = 32
        val color = engine.nextAmbientRgb()

        assertEquals(147, color.r)
        assertEquals(81, color.g)
        assertEquals(31, color.b)
        assertTrue("Warm lounge never drops below gentle ambient glow", color.r >= 140)
    }

    @Test
    fun `continuous breathing phase advances deterministically over 300 frames without jumps`() {
        val engine = AudioFallbackEngine()
        engine.resetEnergiesForTesting()
        engine.setPhaseForTesting(0.0)

        var previousR = 0
        var maxR = 0
        var minR = 255

        repeat(300) {
            val color = engine.nextAmbientRgb()
            assertTrue("r must remain within [147, 210], got ${color.r}", color.r in 147..210)
            assertTrue("g must remain within [80, 115], got ${color.g}", color.g in 80..115)
            assertTrue("b must remain within [31, 45], got ${color.b}", color.b in 31..45)

            if (previousR > 0) {
                // Smooth frame-to-frame change: maximum delta cannot exceed 3 in 1 frame
                val delta = kotlin.math.abs(color.r - previousR)
                assertTrue("Breathing change must be smooth, delta was $delta", delta <= 3)
            }
            previousR = color.r
            maxR = maxOf(maxR, color.r)
            minR = minOf(minR, color.r)
        }

        assertEquals("Observed maximum must hit peak", 210, maxR)
        assertEquals("Observed minimum must hit trough", 147, minR)
    }

    // =========================================================================
    // 3. FFT EXTRACTION & ACOUSTIC CONSTANTS
    // =========================================================================

    @Test
    fun `processFftData safely ignores null or undersized arrays`() {
        val engine = AudioFallbackEngine()

        engine.processFftData(null)
        assertEquals(0f, engine.rawBass, 0.0001f)
        assertEquals(0f, engine.rawMid, 0.0001f)

        engine.processFftData(ByteArray(0))
        assertEquals(0f, engine.rawBass, 0.0001f)

        engine.processFftData(ByteArray(15)) // Less than minFftSize (16)
        assertEquals(0f, engine.rawBass, 0.0001f)
    }

    @Test
    fun `processFftData extracts bass bins 1 to 3 accurately`() {
        val config = AudioEngineConfig(energySmoothingFactor = 1.0f) // Instantaneous for testing
        val engine = AudioFallbackEngine(config)

        // Create 32-byte FFT array
        val fft = ByteArray(32)
        // Bass bins are indices 2, 3 (bin 1), 4, 5 (bin 2), 6, 7 (bin 3)
        // Set real part to 127 for all 3 bins: hypot(127, 0) = 127
        fft[2] = 127; fft[3] = 0
        fft[4] = 127; fft[5] = 0
        fft[6] = 127; fft[7] = 0

        engine.processFftData(fft)

        // 3 bins * 127 = 381. Normalization = 3 * 128 = 384. Energy = 381 / 384 = ~0.9922
        val expectedBass = (3f * 127f) / 384f
        assertEquals(expectedBass, engine.rawBass, 0.001f)
        assertEquals(0f, engine.rawMid, 0.0001f)
    }

    @Test
    fun `processFftData extracts mid bins 4 to 11 accurately`() {
        val config = AudioEngineConfig(energySmoothingFactor = 1.0f)
        val engine = AudioFallbackEngine(config)

        val fft = ByteArray(32)
        // Mid bins are indices 8..23 (8 bins)
        // Set real part to 64 for all 8 mid bins: hypot(64, 0) = 64
        for (i in 8 until 24 step 2) {
            fft[i] = 64
            fft[i + 1] = 0
        }

        engine.processFftData(fft)

        // 8 bins * 64 = 512. Normalization = 8 * 128 = 1024. Energy = 512 / 1024 = 0.5f
        assertEquals(0.5f, engine.rawMid, 0.001f)
        assertEquals(0f, engine.rawBass, 0.0001f)
    }

    @Test
    fun `processFftData handles signed negative byte magnitudes without overflow`() {
        val config = AudioEngineConfig(energySmoothingFactor = 1.0f)
        val engine = AudioFallbackEngine(config)

        val fft = ByteArray(32)
        // In signed byte, -128 is a valid minimum. hypot(-128, 0) = 128
        fft[2] = (-128).toByte(); fft[3] = 0
        fft[4] = (-128).toByte(); fft[5] = 0
        fft[6] = (-128).toByte(); fft[7] = 0

        engine.processFftData(fft)

        // 3 bins * 128 = 384. Normalization = 384. Energy = 1.0f
        assertEquals(1.0f, engine.rawBass, 0.0001f)
    }

    // =========================================================================
    // 4. TEMPORAL ENERGY SMOOTHING (EMA)
    // =========================================================================

    @Test
    fun `energy smoothing applies exponential moving average filter correctly`() {
        val smoothingFactor = 0.4f
        val config = AudioEngineConfig(energySmoothingFactor = smoothingFactor)
        val engine = AudioFallbackEngine(config)

        val fullBassFft = ByteArray(32).apply {
            fftValue(this, 2, -128); fftValue(this, 4, -128); fftValue(this, 6, -128)
        }
        val zeroFft = ByteArray(32)

        // Frame 1: impulse of 1.0f -> smoothed = 0.0 * 0.6 + 1.0 * 0.4 = 0.4f
        engine.processFftData(fullBassFft)
        assertEquals(1.0f, engine.rawBass, 0.001f)
        assertEquals(0.4f, engine.bassEnergy, 0.001f)

        // Frame 2: sustained impulse -> smoothed = 0.4 * 0.6 + 1.0 * 0.4 = 0.64f
        engine.processFftData(fullBassFft)
        assertEquals(0.64f, engine.bassEnergy, 0.001f)

        // Frame 3: sustained impulse -> smoothed = 0.64 * 0.6 + 1.0 * 0.4 = 0.784f
        engine.processFftData(fullBassFft)
        assertEquals(0.784f, engine.bassEnergy, 0.001f)

        // Frame 4: impulse drops to zero -> smoothed = 0.784 * 0.6 + 0.0 * 0.4 = 0.4704f
        engine.processFftData(zeroFft)
        assertEquals(0f, engine.rawBass, 0.0001f)
        assertEquals(0.4704f, engine.bassEnergy, 0.001f)
    }

    // =========================================================================
    // 5. AUDIO-REACTIVE SYNTHESIS
    // =========================================================================

    @Test
    fun `audio reactive mode triggers when bass exceeds threshold`() {
        val config = AudioEngineConfig(energySmoothingFactor = 1.0f)
        val engine = AudioFallbackEngine(config)

        val fft = ByteArray(32).apply {
            fftValue(this, 2, 64); fftValue(this, 4, 64); fftValue(this, 6, 64)
        }
        engine.processFftData(fft)
        assertTrue(engine.bassEnergy > 0.05f)

        engine.setPhaseForTesting(0.0)
        val color = engine.nextAmbientRgb()

        // In reactive mode, blue increases with bass (140 + 115 * 0.5 = ~197.5) * intensity
        assertTrue("Reactive blue channel must activate with bass", color.b > 45)
        assertTrue("Channel values must remain within 0..255", color.r in 0..255 && color.g in 0..255 && color.b in 0..255)
    }

    // =========================================================================
    // 6. ZERO-ALLOCATION CONTRACTS & BACKWARD COMPATIBILITY
    // =========================================================================

    @Test
    fun `getAmbientColor mutates caller array in-place without heap allocations`() {
        val engine = AudioFallbackEngine()
        val buffer = IntArray(3) { -1 }

        val returned = engine.getAmbientColor(buffer)

        // Must return the exact same array reference
        assertTrue(buffer === returned)
        assertTrue("Red channel must be populated", buffer[0] >= 0)
        assertTrue("Green channel must be populated", buffer[1] >= 0)
        assertTrue("Blue channel must be populated", buffer[2] >= 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `getAmbientColor throws IllegalArgumentException on undersized buffer`() {
        val engine = AudioFallbackEngine()
        engine.getAmbientColor(IntArray(2))
    }

    @Test
    fun `nextAmbientColor returns compatible 3-element IntArray matching nextAmbientRgb`() {
        val engine = AudioFallbackEngine()
        engine.setPhaseForTesting(1.0)

        // Save phase before nextAmbientRgb to compare same frame
        val testPhase = 1.5
        engine.setPhaseForTesting(testPhase)
        val rgb = engine.nextAmbientRgb()

        engine.setPhaseForTesting(testPhase)
        val array = engine.nextAmbientColor()

        assertEquals(3, array.size)
        assertEquals(rgb.r, array[0])
        assertEquals(rgb.g, array[1])
        assertEquals(rgb.b, array[2])
    }

    @Test
    fun `AudioEngineConfig default values align with companion acoustic constants`() {
        val config = AudioEngineConfig()
        assertEquals(AudioFallbackEngine.BASS_BIN_MIN, 1)
        assertEquals(AudioFallbackEngine.BASS_BIN_MAX, 3)
        assertEquals(AudioFallbackEngine.MID_BIN_MIN, 4)
        assertEquals(AudioFallbackEngine.MID_BIN_MAX, 11)
        assertEquals(AudioFallbackEngine.BASS_NORMALIZATION_DENOMINATOR, config.bassNormalizationDenominator, 0.001f)
        assertEquals(AudioFallbackEngine.MID_NORMALIZATION_DENOMINATOR, config.midNormalizationDenominator, 0.001f)
        assertEquals(AudioFallbackEngine.DEFAULT_ENERGY_SMOOTHING_FACTOR, config.energySmoothingFactor, 0.001f)
        assertEquals(AudioFallbackEngine.DEFAULT_AUDIO_ENERGY_THRESHOLD, config.audioEnergyThreshold, 0.001f)
    }

    // Helper to set real byte in FFT array
    private fun fftValue(array: ByteArray, index: Int, value: Int) {
        array[index] = value.coerceIn(-128, 127).toByte()
        array[index + 1] = 0
    }
}
