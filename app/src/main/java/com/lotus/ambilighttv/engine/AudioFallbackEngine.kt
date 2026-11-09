package com.lotus.ambilighttv.engine

import android.media.audiofx.Visualizer
import com.lotus.ambilighttv.model.RgbColor
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Operational state of the [AudioFallbackEngine].
 */
enum class AudioEngineStatus {
    /** Engine is inactive and audio hardware resources are released. */
    IDLE,

    /** Active capture from hardware Visualizer (session 0). */
    RUNNING_VISUALIZER,

    /** Visualizer unavailable, permission denied, or quiet playback; emitting relaxing warm ambient glow. */
    RUNNING_WARM_LOUNGE,

    /** Fatal or explicit permanent failure; audio fallback loop is permanently bypassed. */
    FAILED_PERMANENTLY
}

/**
 * Backward compatibility typealias for PROJECT.md interface contract.
 */
typealias EngineStatus = AudioEngineStatus

/**
 * Acoustic FFT boundaries, normalization denominators, harmonic parameters,
 * and warm lounge breathing constants.
 */
data class AudioEngineConfig(
    // Acoustic FFT Bin Parameters
    val minCaptureSize: Int = AudioFallbackEngine.DEFAULT_MIN_CAPTURE_SIZE,
    val minFftSize: Int = AudioFallbackEngine.DEFAULT_MIN_FFT_SIZE,
    val bassBinStartByte: Int = AudioFallbackEngine.BASS_BIN_START_BYTE,
    val bassBinEndByte: Int = AudioFallbackEngine.BASS_BIN_END_BYTE,
    val bassBinCount: Int = AudioFallbackEngine.BASS_BIN_COUNT,
    val midBinStartByte: Int = AudioFallbackEngine.MID_BIN_START_BYTE,
    val midBinEndByte: Int = AudioFallbackEngine.MID_BIN_END_BYTE,
    val midBinCount: Int = AudioFallbackEngine.MID_BIN_COUNT,
    val maxByteMagnitude: Float = AudioFallbackEngine.MAX_BYTE_MAGNITUDE,
    val bassNormalizationDenominator: Float = AudioFallbackEngine.BASS_NORMALIZATION_DENOMINATOR,
    val midNormalizationDenominator: Float = AudioFallbackEngine.MID_NORMALIZATION_DENOMINATOR,

    // Temporal Smoothing & Energy Thresholds
    val energySmoothingFactor: Float = AudioFallbackEngine.DEFAULT_ENERGY_SMOOTHING_FACTOR,
    val audioEnergyThreshold: Float = AudioFallbackEngine.DEFAULT_AUDIO_ENERGY_THRESHOLD,

    // Phase Evolution
    val phaseStepBase: Double = AudioFallbackEngine.DEFAULT_PHASE_STEP_BASE,
    val phaseStepBassScale: Double = AudioFallbackEngine.DEFAULT_PHASE_STEP_BASS_SCALE,

    // Harmonic Audio-Reactive Synthesis Parameters
    val intensityBase: Float = AudioFallbackEngine.DEFAULT_INTENSITY_BASE,
    val intensityBassFactor: Float = AudioFallbackEngine.DEFAULT_INTENSITY_BASS_FACTOR,
    val intensityMin: Float = AudioFallbackEngine.DEFAULT_INTENSITY_MIN,
    val intensityMax: Float = AudioFallbackEngine.DEFAULT_INTENSITY_MAX,

    val redBase: Float = AudioFallbackEngine.DEFAULT_RED_BASE,
    val redAmplitude: Float = AudioFallbackEngine.DEFAULT_RED_AMPLITUDE,
    val redPhaseFreq: Double = AudioFallbackEngine.DEFAULT_RED_PHASE_FREQ,
    val redMin: Int = AudioFallbackEngine.DEFAULT_RED_MIN,
    val redMax: Int = AudioFallbackEngine.DEFAULT_RED_MAX,

    val greenBase: Float = AudioFallbackEngine.DEFAULT_GREEN_BASE,
    val greenMidFactor: Float = AudioFallbackEngine.DEFAULT_GREEN_MID_FACTOR,
    val greenAmplitude: Float = AudioFallbackEngine.DEFAULT_GREEN_AMPLITUDE,
    val greenPhaseFreq: Double = AudioFallbackEngine.DEFAULT_GREEN_PHASE_FREQ,
    val greenMin: Int = AudioFallbackEngine.DEFAULT_GREEN_MIN,
    val greenMax: Int = AudioFallbackEngine.DEFAULT_GREEN_MAX,

    val blueBase: Float = AudioFallbackEngine.DEFAULT_BLUE_BASE,
    val blueBassFactor: Float = AudioFallbackEngine.DEFAULT_BLUE_BASS_FACTOR,
    val blueMin: Int = AudioFallbackEngine.DEFAULT_BLUE_MIN,
    val blueMax: Int = AudioFallbackEngine.DEFAULT_BLUE_MAX,

    // Warm Ambient Lounge Breathing (Quiet / Fallback Mode)
    val breathBase: Double = AudioFallbackEngine.DEFAULT_BREATH_BASE,
    val breathAmplitude: Double = AudioFallbackEngine.DEFAULT_BREATH_AMPLITUDE,
    val breathPhaseFreq: Double = AudioFallbackEngine.DEFAULT_BREATH_PHASE_FREQ,
    val loungeRedBase: Int = AudioFallbackEngine.DEFAULT_LOUNGE_RED_BASE,
    val loungeGreenBase: Int = AudioFallbackEngine.DEFAULT_LOUNGE_GREEN_BASE,
    val loungeBlueBase: Int = AudioFallbackEngine.DEFAULT_LOUNGE_BLUE_BASE
)

/**
 * Audio-reactive + Warm Ambient Fallback Engine when MediaProjection encounters
 * DRM-protected video (Widevine L1 / FLAG_SECURE on Netflix, Prime Video, Disney+).
 *
 * Resilience Features:
 * - Exception Storm Elimination: Implements [AudioEngineStatus] state machine. If `Visualizer(0)`
 *   initialization fails (e.g. RECORD_AUDIO permission missing or OEM restricts session 0 on Android TV),
 *   it cleanly transitions to [AudioEngineStatus.RUNNING_WARM_LOUNGE] without throwing exceptions on subsequent frames.
 * - Zero-Allocation Loop: [nextAmbientRgb] returns a stack-based [RgbColor] value class (zero heap allocation).
 *   [getAmbientColor] mutates caller-provided `IntArray(3)`.
 * - Acoustic Constants: All FFT bin boundaries, normalization constants, and harmonic parameters are extracted
 *   into [AudioEngineConfig] and documented companion constants.
 * - Temporal Smoothing: Implements exponential moving average (EMA) filter on bass and mid energy to eliminate
 *   visual strobe/flicker artifacts during audio transients.
 */
class AudioFallbackEngine(
    val config: AudioEngineConfig = AudioEngineConfig(),
    private val visualizerProvider: (() -> Visualizer)? = null
) {
    @Volatile
    var status: AudioEngineStatus = AudioEngineStatus.IDLE
        private set

    @Volatile
    var isVisualizerBlocked: Boolean = false
        private set

    @Volatile
    private var rawBassEnergy: Float = 0f

    @Volatile
    private var rawMidEnergy: Float = 0f

    @Volatile
    private var smoothedBassEnergy: Float = 0f

    @Volatile
    private var smoothedMidEnergy: Float = 0f

    private var phase: Double = 0.0
    private var visualizer: Visualizer? = null

    // Public observability accessors
    val bassEnergy: Float get() = smoothedBassEnergy
    val midEnergy: Float get() = smoothedMidEnergy
    val rawBass: Float get() = rawBassEnergy
    val rawMid: Float get() = rawMidEnergy
    val currentPhase: Double get() = phase

    /**
     * Starts the audio fallback engine.
     *
     * If the engine is already running or permanently failed, this method returns immediately without overhead.
     * If [Visualizer] session 0 was previously blocked or restricted by the OEM/permissions, it cleanly
     * transitions to [AudioEngineStatus.RUNNING_WARM_LOUNGE] without attempting hardware initialization or throwing exceptions.
     *
     * @param forceRetry If true, clears the blocked flag and attempts hardware initialization again.
     */
    fun start(forceRetry: Boolean = false) {
        if (forceRetry) {
            isVisualizerBlocked = false
        }

        if (status == AudioEngineStatus.RUNNING_VISUALIZER ||
            status == AudioEngineStatus.RUNNING_WARM_LOUNGE ||
            status == AudioEngineStatus.FAILED_PERMANENTLY
        ) {
            return
        }

        if (isVisualizerBlocked) {
            status = AudioEngineStatus.RUNNING_WARM_LOUNGE
            return
        }

        try {
            val v = visualizerProvider?.invoke() ?: Visualizer(AUDIO_SESSION_SYSTEM_OUTPUT)
            val captureRange = Visualizer.getCaptureSizeRange()
            val minCapture = if (captureRange != null && captureRange.isNotEmpty()) {
                captureRange[0].coerceAtLeast(config.minCaptureSize)
            } else {
                config.minCaptureSize
            }
            v.captureSize = minCapture
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) = Unit

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {
                        processFftData(fft)
                    }
                },
                Visualizer.getMaxCaptureRate() / CAPTURE_RATE_DIVISOR,
                false,
                true
            )
            v.enabled = true
            visualizer = v
            status = AudioEngineStatus.RUNNING_VISUALIZER
        } catch (_: Throwable) {
            // If RECORD_AUDIO is not granted or Visualizer session 0 is restricted by the OEM,
            // mark visualizer as blocked to eliminate repeated 25-30 Hz exception storms.
            isVisualizerBlocked = true
            visualizer = null
            status = AudioEngineStatus.RUNNING_WARM_LOUNGE
        }
    }

    /**
     * Stops the audio fallback engine, releases hardware resources, and resets transient energy states.
     */
    fun stop() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Throwable) {
        } finally {
            visualizer = null
            rawBassEnergy = 0f
            rawMidEnergy = 0f
            smoothedBassEnergy = 0f
            smoothedMidEnergy = 0f
            if (status != AudioEngineStatus.FAILED_PERMANENTLY) {
                status = AudioEngineStatus.IDLE
            }
        }
    }

    /**
     * Marks the engine as permanently failed, releasing hardware and halting future initialization.
     */
    fun markPermanentFailure() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Throwable) {
        } finally {
            visualizer = null
            status = AudioEngineStatus.FAILED_PERMANENTLY
        }
    }

    /**
     * Resets permanent failure and blocked status back to [AudioEngineStatus.IDLE].
     */
    fun resetPermanentFailure() {
        isVisualizerBlocked = false
        status = AudioEngineStatus.IDLE
    }

    /**
     * Resets the hardware blocked flag, allowing [start] to re-probe [Visualizer].
     */
    fun resetBlockedStatus() {
        isVisualizerBlocked = false
    }

    /**
     * Computes the ambient color for the current frame with ZERO heap allocations.
     *
     * @return Stack-based [RgbColor] value class.
     */
    fun nextAmbientRgb(): RgbColor {
        if (status == AudioEngineStatus.FAILED_PERMANENTLY) {
            return RgbColor.CINEMA_WARM
        }

        val bass = smoothedBassEnergy
        val mid = smoothedMidEnergy

        phase += config.phaseStepBase + (bass * config.phaseStepBassScale)
        if (phase >= HARMONIC_WRAP_PERIOD) {
            phase -= HARMONIC_WRAP_PERIOD
        }

        return if (bass > config.audioEnergyThreshold || mid > config.audioEnergyThreshold) {
            // Audio-reactive warm/vivid cinema pulse
            val intensity = (config.intensityBase + bass * config.intensityBassFactor)
                .coerceIn(config.intensityMin, config.intensityMax)

            val r = ((config.redBase + config.redAmplitude * sin(phase * config.redPhaseFreq)) * intensity)
                .roundToInt().coerceIn(config.redMin, config.redMax)

            val g = ((config.greenBase + config.greenMidFactor * mid + config.greenAmplitude * cos(phase * config.greenPhaseFreq)) * intensity)
                .roundToInt().coerceIn(config.greenMin, config.greenMax)

            val b = ((config.blueBase + config.blueBassFactor * bass) * intensity)
                .roundToInt().coerceIn(config.blueMin, config.blueMax)

            RgbColor.fromRgb(r, g, b)
        } else {
            // Gentle Philips Lounge Warm White / Amber glow during quiet DRM scenes
            val breath = config.breathBase + config.breathAmplitude * sin(phase * config.breathPhaseFreq)
            val r = (config.loungeRedBase * breath).roundToInt().coerceIn(0, 255)
            val g = (config.loungeGreenBase * breath).roundToInt().coerceIn(0, 255)
            val b = (config.loungeBlueBase * breath).roundToInt().coerceIn(0, 255)

            RgbColor.fromRgb(r, g, b)
        }
    }

    /**
     * Zero-allocation ambient color extraction into a pre-allocated array.
     *
     * @param out Array of length at least 3 where [r, g, b] will be written at indices 0, 1, 2.
     * @return The same [out] array for chaining.
     */
    fun getAmbientColor(out: IntArray): IntArray {
        require(out.size >= 3) { "out array must have at least 3 elements, got ${out.size}" }
        val color = nextAmbientRgb()
        out[0] = color.r
        out[1] = color.g
        out[2] = color.b
        return out
    }

    /**
     * Backward-compatible ambient color calculation.
     *
     * Note: Allocates a new 3-element [IntArray] on each call. For zero-allocation high-frequency
     * rendering loops, prefer [nextAmbientRgb] or [getAmbientColor].
     */
    fun nextAmbientColor(): IntArray {
        val color = nextAmbientRgb()
        return intArrayOf(color.r, color.g, color.b)
    }

    /**
     * Parses raw FFT byte data from the Android Visualizer, computes band energy sums,
     * and applies exponential moving average (EMA) smoothing.
     */
    internal fun processFftData(fft: ByteArray?) {
        if (fft == null || fft.size < config.minFftSize) return

        var bassSum = 0f
        val bassEnd = config.bassBinEndByte.coerceAtMost(fft.size - 1)
        for (i in config.bassBinStartByte until bassEnd step 2) {
            val re = fft[i].toFloat()
            val im = fft[i + 1].toFloat()
            bassSum += hypot(re, im)
        }

        var midSum = 0f
        val midEnd = config.midBinEndByte.coerceAtMost(fft.size - 1)
        for (i in config.midBinStartByte until midEnd step 2) {
            val re = fft[i].toFloat()
            val im = fft[i + 1].toFloat()
            midSum += hypot(re, im)
        }

        val rawBass = (bassSum / config.bassNormalizationDenominator).coerceIn(0f, 1f)
        val rawMid = (midSum / config.midNormalizationDenominator).coerceIn(0f, 1f)

        rawBassEnergy = rawBass
        rawMidEnergy = rawMid

        val alpha = config.energySmoothingFactor.coerceIn(0f, 1f)
        smoothedBassEnergy = smoothedBassEnergy * (1f - alpha) + rawBass * alpha
        smoothedMidEnergy = smoothedMidEnergy * (1f - alpha) + rawMid * alpha
    }

    /**
     * Deterministic phase override for unit testing breathing waveforms.
     */
    internal fun setPhaseForTesting(testPhase: Double) {
        phase = testPhase
    }

    /**
     * Resets energies to zero for unit testing.
     */
    internal fun resetEnergiesForTesting() {
        rawBassEnergy = 0f
        rawMidEnergy = 0f
        smoothedBassEnergy = 0f
        smoothedMidEnergy = 0f
    }

    companion object {
        /** Android TV global audio session mix identifier. */
        const val AUDIO_SESSION_SYSTEM_OUTPUT = 0

        /** Visualizer capture rate divisor: 20000 mHz / 2 = 10000 mHz (10 Hz). */
        const val CAPTURE_RATE_DIVISOR = 2

        /** Minimum FFT capture size required by Android Visualizer. */
        const val DEFAULT_MIN_CAPTURE_SIZE = 128

        /** Minimum FFT array size required to extract bass and mid bins. */
        const val DEFAULT_MIN_FFT_SIZE = 16

        // Acoustic FFT Bin Parameters:
        // Visualizer FFT format: byte pairs (real, imaginary) for frequencies k * Fs / N.
        // Bins 1..3: byte indices 2, 4, 6 (real) and 3, 5, 7 (imaginary)
        const val BASS_BIN_MIN = 1
        const val BASS_BIN_MAX = 3
        const val BASS_BIN_START_BYTE = 2
        const val BASS_BIN_END_BYTE = 8
        const val BASS_BIN_COUNT = 3

        // Bins 4..11: byte indices 8, 10, ..., 22 (real) and 9, 11, ..., 23 (imaginary)
        const val MID_BIN_MIN = 4
        const val MID_BIN_MAX = 11
        const val MID_BIN_START_BYTE = 8
        const val MID_BIN_END_BYTE = 24
        const val MID_BIN_COUNT = 8

        /** Maximum magnitude per byte component in Android Visualizer signed 8-bit format (-128..127). */
        const val MAX_BYTE_MAGNITUDE = 128f

        /** Normalization denominator for bass energy: 3 bins * 128f = 384f. */
        const val BASS_NORMALIZATION_DENOMINATOR = BASS_BIN_COUNT * MAX_BYTE_MAGNITUDE

        /** Normalization denominator for mid energy: 8 bins * 128f = 1024f. */
        const val MID_NORMALIZATION_DENOMINATOR = MID_BIN_COUNT * MAX_BYTE_MAGNITUDE

        /** Default exponential moving average (EMA) smoothing factor (0.0 = infinite inertia, 1.0 = instant). */
        const val DEFAULT_ENERGY_SMOOTHING_FACTOR = 0.4f

        /** Threshold below which audio is considered quiet and warm ambient lounge mode takes over. */
        const val DEFAULT_AUDIO_ENERGY_THRESHOLD = 0.05f

        // Phase progression parameters
        const val DEFAULT_PHASE_STEP_BASE = 0.06
        const val DEFAULT_PHASE_STEP_BASS_SCALE = 0.18

        // Audio-reactive harmonic synthesis parameters
        const val DEFAULT_INTENSITY_BASE = 0.35f
        const val DEFAULT_INTENSITY_BASS_FACTOR = 0.65f
        const val DEFAULT_INTENSITY_MIN = 0.25f
        const val DEFAULT_INTENSITY_MAX = 1.0f

        const val DEFAULT_RED_BASE = 180f
        const val DEFAULT_RED_AMPLITUDE = 75f
        const val DEFAULT_RED_PHASE_FREQ = 0.7
        const val DEFAULT_RED_MIN = 20
        const val DEFAULT_RED_MAX = 255

        const val DEFAULT_GREEN_BASE = 90f
        const val DEFAULT_GREEN_MID_FACTOR = 110f
        const val DEFAULT_GREEN_AMPLITUDE = 40f
        const val DEFAULT_GREEN_PHASE_FREQ = 0.5
        const val DEFAULT_GREEN_MIN = 10
        const val DEFAULT_GREEN_MAX = 255

        const val DEFAULT_BLUE_BASE = 140f
        const val DEFAULT_BLUE_BASS_FACTOR = 115f
        const val DEFAULT_BLUE_MIN = 15
        const val DEFAULT_BLUE_MAX = 255

        // Warm Ambient Lounge Breathing (Quiet / Fallback Mode)
        const val DEFAULT_BREATH_BASE = 0.85
        const val DEFAULT_BREATH_AMPLITUDE = 0.15
        const val DEFAULT_BREATH_PHASE_FREQ = 0.4

        // Lounge Warm Amber Base (Matches RgbColor.CINEMA_WARM #D2732D)
        const val DEFAULT_LOUNGE_RED_BASE = 210
        const val DEFAULT_LOUNGE_GREEN_BASE = 115
        const val DEFAULT_LOUNGE_BLUE_BASE = 45

        /**
         * Exact common harmonic period for frequencies 0.7, 0.5, 0.4:
         * LCM(7, 5, 4) = 140 cycles -> 140 * 2 * PI. Wrapping here guarantees zero visual phase discontinuity.
         */
        private const val HARMONIC_WRAP_PERIOD = 140.0 * (2.0 * Math.PI)
    }
}
