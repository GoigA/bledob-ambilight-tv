package com.lotus.ambilighttv.engine

import com.lotus.ambilighttv.model.RgbColor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Configuration parameters for the vivid edge color extraction and processing engine.
 *
 * All parameters default to carefully tuned, human-tested production values that eliminate
 * magic numbers throughout the color extraction pipeline.
 */
data class VividColorConfig(
    val edgeBorderFraction: Float = DEFAULT_EDGE_BORDER_FRACTION,
    val spatialEdgeMultiplier: Double = DEFAULT_SPATIAL_EDGE_MULTIPLIER,
    val saturationExponent: Double = DEFAULT_SATURATION_EXPONENT,
    val vividMultiplier: Double = DEFAULT_VIVID_MULTIPLIER,
    val minVividWeight: Double = DEFAULT_MIN_VIVID_WEIGHT,
    val vividBaseOffset: Double = DEFAULT_VIVID_BASE_OFFSET,
    val blackFrameFadeDecay: Float = DEFAULT_BLACK_FRAME_FADE_DECAY,
    val gammaLiftExponent: Double = DEFAULT_GAMMA_LIFT_EXPONENT,
    val blackLumaThreshold: Int = DEFAULT_BLACK_LUMA_THRESHOLD,
    val drmBlackFrameTriggerCount: Int = DEFAULT_DRM_BLACK_FRAME_TRIGGER_COUNT,
    val maxLetterboxScanFraction: Float = DEFAULT_MAX_LETTERBOX_SCAN_FRACTION
) {
    companion object {
        /** Fraction of active frame height/width treated as outer border (25%). */
        const val DEFAULT_EDGE_BORDER_FRACTION = 0.25f

        /** Multiplier applied to outer border pixels to prioritize room edge illumination. */
        const val DEFAULT_SPATIAL_EDGE_MULTIPLIER = 2.5

        /** Exponent shaping the non-linear saturation curve. */
        const val DEFAULT_SATURATION_EXPONENT = 1.4

        /** Multiplier boosting colorful/saturated pixels over dull gray/white backgrounds. */
        const val DEFAULT_VIVID_MULTIPLIER = 2.2

        /** Minimum weight floor to guarantee dull pixels contribute without collapsing. */
        const val DEFAULT_MIN_VIVID_WEIGHT = 0.20

        /** Linear baseline value component added to vivid weight calculation. */
        const val DEFAULT_VIVID_BASE_OFFSET = 0.15

        /** Exponential decay factor applied to smoothed RGB values during black frames. */
        const val DEFAULT_BLACK_FRAME_FADE_DECAY = 0.75f

        /** Gamma lift exponent (< 1.0) to brighten mid-tones for vibrant physical LED glow. */
        const val DEFAULT_GAMMA_LIFT_EXPONENT = 0.88

        /** Luminance noise ceiling below which a pixel is considered pitch black. */
        const val DEFAULT_BLACK_LUMA_THRESHOLD = 10

        /** Number of consecutive black frames (~1.0s at 25 FPS) before triggering DRM audio fallback. */
        const val DEFAULT_DRM_BLACK_FRAME_TRIGGER_COUNT = 25

        /** Maximum fraction of frame height scanned from top or bottom for black bars (1/3). */
        const val DEFAULT_MAX_LETTERBOX_SCAN_FRACTION = 0.33f
    }
}

/**
 * Vivid Edge + Exponential Moving Average (EMA) Ambilight Color Engine.
 *
 * Features:
 * 1. Automatic letterbox (top/bottom black bars) detection and cropping.
 * 2. Edge-weighted spatial sampling (outer 25% border of active video receives 2.5x weight).
 * 3. Saturation-weighted accumulation + zero-allocation HSV saturation boost.
 * 4. Temporal EMA smoothing across frames with selectable profiles (CINEMA, DYNAMIC, GAMING).
 * 5. Consecutive black-frame detection for DRM (Widevine) automatic fallback.
 * 6. Zero allocations in the high-frequency loop (25-30 FPS) using [RgbColor] value class.
 */
class VividEdgeColorEngine(
    initialProfile: AmbilightProfile = AmbilightProfile.DYNAMIC,
    val config: VividColorConfig = VividColorConfig()
) {
    enum class AmbilightProfile(
        val label: String,
        val emaAlpha: Float,
        val saturationBoost: Float,
        val minColorDelta: Int,
        val targetFps: Int
    ) {
        CINEMA(
            label = "Cinema (Suave)",
            emaAlpha = 0.22f,
            saturationBoost = 1.25f,
            minColorDelta = 3,
            targetFps = 20
        ),
        DYNAMIC(
            label = "Dinâmico (Padrão)",
            emaAlpha = 0.42f,
            saturationBoost = 1.45f,
            minColorDelta = 2,
            targetFps = 25
        ),
        GAMING(
            label = "Jogo (Ultra Rápido)",
            emaAlpha = 0.78f,
            saturationBoost = 1.55f,
            minColorDelta = 1,
            targetFps = 30
        )
    }

    /**
     * Analysis result of a single video frame.
     *
     * Provides [color] as a zero-allocation [RgbColor] value class, while retaining [r], [g], [b]
     * channel getters for 100% backward compatibility with existing callers.
     */
    data class FrameAnalysisResult(
        val color: RgbColor,
        val isBlackFrame: Boolean,
        val isDrmFallbackActive: Boolean,
        val activeTopRow: Int,
        val activeBottomRow: Int
    ) {
        /** Red channel component (0..255). */
        val r: Int get() = color.r

        /** Green channel component (0..255). */
        val g: Int get() = color.g

        /** Blue channel component (0..255). */
        val b: Int get() = color.b

        /**
         * Backward-compatible secondary constructor accepting individual [r], [g], [b] channels.
         */
        constructor(
            r: Int,
            g: Int,
            b: Int,
            isBlackFrame: Boolean,
            isDrmFallbackActive: Boolean,
            activeTopRow: Int,
            activeBottomRow: Int
        ) : this(
            color = RgbColor.fromRgb(r, g, b),
            isBlackFrame = isBlackFrame,
            isDrmFallbackActive = isDrmFallbackActive,
            activeTopRow = activeTopRow,
            activeBottomRow = activeBottomRow
        )
    }

    @Volatile
    var profile: AmbilightProfile = initialProfile

    private var smoothedR: Float = 0f
    private var smoothedG: Float = 0f
    private var smoothedB: Float = 0f
    private var initialized: Boolean = false
    private var consecutiveBlackFrames: Int = 0

    /** The number of consecutive pure-black frames currently tracked by the engine. */
    val currentConsecutiveBlackFrames: Int get() = consecutiveBlackFrames

    /** Whether the temporal smoothing filter has processed at least one non-black frame. */
    val isInitialized: Boolean get() = initialized

    companion object {
        const val BLACK_LUMA_THRESHOLD = VividColorConfig.DEFAULT_BLACK_LUMA_THRESHOLD
        const val DRM_BLACK_FRAME_TRIGGER_COUNT = VividColorConfig.DEFAULT_DRM_BLACK_FRAME_TRIGGER_COUNT
    }

    fun reset() {
        initialized = false
        consecutiveBlackFrames = 0
        smoothedR = 0f
        smoothedG = 0f
        smoothedB = 0f
    }

    /**
     * Processes a downsampled frame of packed ARGB_8888 ints (width x height).
     *
     * Operates without heap allocations during the hot color math path by using [RgbColor].
     */
    fun processFrame(
        argbPixels: IntArray,
        width: Int,
        height: Int
    ): FrameAnalysisResult {
        if (width <= 0 || height <= 0 || argbPixels.size < width * height) {
            return FrameAnalysisResult(
                color = RgbColor.BLACK,
                isBlackFrame = true,
                isDrmFallbackActive = true,
                activeTopRow = 0,
                activeBottomRow = 0
            )
        }

        // 1. Detect top and bottom letterbox bars
        var topRow = 0
        val maxScanRows = (height * config.maxLetterboxScanFraction).toInt()
        while (topRow < maxScanRows && isRowBlack(argbPixels, width, topRow)) {
            topRow++
        }

        var bottomRow = height - 1
        val minBottomRow = height - 1 - maxScanRows
        while (bottomRow > minBottomRow && isRowBlack(argbPixels, width, bottomRow)) {
            bottomRow--
        }

        val activeHeight = bottomRow - topRow + 1
        if (activeHeight <= 2) {
            return handleBlackFrame(topRow, bottomRow)
        }

        // 2. Accumulate Vivid Edge weighted RGB
        var weightedR = 0.0
        var weightedG = 0.0
        var weightedB = 0.0
        var totalWeight = 0.0
        var nonBlackPixels = 0

        val borderThicknessY = max(1, (activeHeight * config.edgeBorderFraction).toInt())
        val borderThicknessX = max(1, (width * config.edgeBorderFraction).toInt())

        for (y in topRow..bottomRow) {
            val isEdgeY = (y - topRow) < borderThicknessY || (bottomRow - y) < borderThicknessY
            val rowOffset = y * width
            for (x in 0 until width) {
                val pixel = argbPixels[rowOffset + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                val maxC = max(r, max(g, b))
                if (maxC <= config.blackLumaThreshold) continue

                nonBlackPixels++
                val minC = min(r, min(g, b))
                val delta = (maxC - minC).toFloat()
                val saturation = if (maxC == 0) 0f else delta / maxC.toFloat()
                val value = maxC / 255f

                val isEdgeX = x < borderThicknessX || (width - 1 - x) < borderThicknessX
                val spatialWeight = if (isEdgeX || isEdgeY) config.spatialEdgeMultiplier else 1.0

                // Vivid weight boosts colorful pixels over dull gray/white backgrounds
                val vividWeight = config.minVividWeight +
                    (saturation.toDouble().pow(config.saturationExponent) * config.vividMultiplier + config.vividBaseOffset) * value
                val weight = spatialWeight * vividWeight

                weightedR += r * weight
                weightedG += g * weight
                weightedB += b * weight
                totalWeight += weight
            }
        }

        if (nonBlackPixels == 0 || totalWeight <= 0.0001) {
            return handleBlackFrame(topRow, bottomRow)
        }

        // Non-black frame observed -> clear DRM black-frame counter
        consecutiveBlackFrames = 0

        val avgR = (weightedR / totalWeight).toFloat()
        val avgG = (weightedG / totalWeight).toFloat()
        val avgB = (weightedB / totalWeight).toFloat()

        // 3. Apply Saturation Boost in HSV space (zero allocations via RgbColor value class)
        val boosted = applySaturationBoost(avgR, avgG, avgB, profile.saturationBoost)

        // 4. Apply Temporal EMA Smoothing
        val alpha = profile.emaAlpha.coerceIn(0.05f, 1.0f)
        val boostedR = boosted.r.toFloat()
        val boostedG = boosted.g.toFloat()
        val boostedB = boosted.b.toFloat()

        if (!initialized) {
            smoothedR = boostedR
            smoothedG = boostedG
            smoothedB = boostedB
            initialized = true
        } else {
            smoothedR += alpha * (boostedR - smoothedR)
            smoothedG += alpha * (boostedG - smoothedG)
            smoothedB += alpha * (boostedB - smoothedB)
        }

        val resultColor = RgbColor.fromRgb(
            smoothedR.roundToInt().coerceIn(0, 255),
            smoothedG.roundToInt().coerceIn(0, 255),
            smoothedB.roundToInt().coerceIn(0, 255)
        )

        return FrameAnalysisResult(
            color = resultColor,
            isBlackFrame = false,
            isDrmFallbackActive = false,
            activeTopRow = topRow,
            activeBottomRow = bottomRow
        )
    }

    private fun handleBlackFrame(topRow: Int, bottomRow: Int): FrameAnalysisResult {
        consecutiveBlackFrames++
        val isDrm = consecutiveBlackFrames >= config.drmBlackFrameTriggerCount
        smoothedR *= config.blackFrameFadeDecay
        smoothedG *= config.blackFrameFadeDecay
        smoothedB *= config.blackFrameFadeDecay
        val fadedColor = RgbColor.fromRgb(
            smoothedR.roundToInt().coerceIn(0, 255),
            smoothedG.roundToInt().coerceIn(0, 255),
            smoothedB.roundToInt().coerceIn(0, 255)
        )
        return FrameAnalysisResult(
            color = fadedColor,
            isBlackFrame = true,
            isDrmFallbackActive = isDrm,
            activeTopRow = topRow,
            activeBottomRow = bottomRow
        )
    }

    private fun isRowBlack(pixels: IntArray, width: Int, row: Int): Boolean {
        val offset = row * width
        var brightPixels = 0
        val maxToleratedNoise = width / 16
        for (x in 0 until width) {
            val p = pixels[offset + x]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (max(r, max(g, b)) > config.blackLumaThreshold) {
                brightPixels++
                if (brightPixels > maxToleratedNoise) return false
            }
        }
        return true
    }

    /**
     * Applies saturation boost in HSV color space with gamma lift.
     *
     * Eliminates heap allocations completely by using primitive floats and returning
     * the zero-allocation [RgbColor] value class via [RgbColor.fromHsv].
     */
    fun applySaturationBoost(r: Float, g: Float, b: Float, boost: Float): RgbColor {
        val rf = (r / 255f).coerceIn(0f, 1f)
        val gf = (g / 255f).coerceIn(0f, 1f)
        val bf = (b / 255f).coerceIn(0f, 1f)

        val maxC = max(rf, max(gf, bf))
        val minC = min(rf, min(gf, bf))
        val delta = maxC - minC

        if (maxC <= 0.001f || delta <= 0.001f) {
            return RgbColor.fromRgb(r.roundToInt(), g.roundToInt(), b.roundToInt())
        }

        val hue = when (maxC) {
            rf -> 60f * (((gf - bf) / delta) % 6f)
            gf -> 60f * (((bf - rf) / delta) + 2f)
            else -> 60f * (((rf - gf) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }

        val sat = ((delta / maxC) * boost).coerceIn(0f, 1f)
        // Gamma lift for mid-tone glow on physical RGB LED strips
        val value = maxC.toDouble().pow(config.gammaLiftExponent).toFloat().coerceIn(0f, 1f)

        return RgbColor.fromHsv(hue, sat, value)
    }

    /**
     * Overload accepting an [RgbColor] value class directly.
     */
    fun applySaturationBoost(color: RgbColor, boost: Float): RgbColor =
        applySaturationBoost(color.r.toFloat(), color.g.toFloat(), color.b.toFloat(), boost)
}
