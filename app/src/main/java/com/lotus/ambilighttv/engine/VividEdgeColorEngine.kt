package com.lotus.ambilighttv.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Vivid Edge + Exponential Moving Average (EMA) Ambilight Color Engine.
 *
 * Features:
 * 1. Automatic letterbox (top/bottom and left/right black bar) cropping.
 * 2. Edge-weighted spatial sampling (outer border of active video has 2.5x weight).
 * 3. Saturation-weighted accumulation + configurable saturation boost to avoid washed-out white/gray.
 * 4. Temporal EMA smoothing across frames with selectable profiles (CINEMA, DYNAMIC, GAMING).
 * 5. Consecutive black-frame detection for DRM (Widevine) automatic fallback.
 */
class VividEdgeColorEngine(
    initialProfile: AmbilightProfile = AmbilightProfile.DYNAMIC
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

    data class FrameAnalysisResult(
        val r: Int,
        val g: Int,
        val b: Int,
        val isBlackFrame: Boolean,
        val isDrmFallbackActive: Boolean,
        val activeTopRow: Int,
        val activeBottomRow: Int
    )

    @Volatile
    var profile: AmbilightProfile = initialProfile

    private var smoothedR: Float = 0f
    private var smoothedG: Float = 0f
    private var smoothedB: Float = 0f
    private var initialized: Boolean = false
    private var consecutiveBlackFrames: Int = 0

    companion object {
        const val BLACK_LUMA_THRESHOLD = 10
        const val DRM_BLACK_FRAME_TRIGGER_COUNT = 25 // ~1.0-1.2s of pure black frames
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
     */
    fun processFrame(
        argbPixels: IntArray,
        width: Int,
        height: Int
    ): FrameAnalysisResult {
        if (width <= 0 || height <= 0 || argbPixels.size < width * height) {
            return FrameAnalysisResult(0, 0, 0, isBlackFrame = true, isDrmFallbackActive = true, 0, 0)
        }

        // 1. Detect top and bottom letterbox bars
        var topRow = 0
        while (topRow < height / 3 && isRowBlack(argbPixels, width, topRow)) {
            topRow++
        }
        var bottomRow = height - 1
        while (bottomRow > (height * 2) / 3 && isRowBlack(argbPixels, width, bottomRow)) {
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

        val borderThicknessY = max(1, activeHeight / 4)
        val borderThicknessX = max(1, width / 4)

        for (y in topRow..bottomRow) {
            val isEdgeY = (y - topRow) < borderThicknessY || (bottomRow - y) < borderThicknessY
            val rowOffset = y * width
            for (x in 0 until width) {
                val pixel = argbPixels[rowOffset + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF

                val maxC = max(r, max(g, b))
                if (maxC <= BLACK_LUMA_THRESHOLD) continue

                nonBlackPixels++
                val minC = min(r, min(g, b))
                val delta = (maxC - minC).toFloat()
                val saturation = if (maxC == 0) 0f else delta / maxC.toFloat()
                val value = maxC / 255f

                val isEdgeX = x < borderThicknessX || (width - 1 - x) < borderThicknessX
                val spatialWeight = if (isEdgeX || isEdgeY) 2.5 else 1.0

                // Vivid weight boosts colorful pixels over dull gray/white backgrounds
                val vividWeight = 0.20 + (saturation.toDouble().pow(1.4) * 2.2 + 0.15) * value
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

        // 3. Apply Saturation Boost in HSV space
        val boosted = applySaturationBoost(avgR, avgG, avgB, profile.saturationBoost)

        // 4. Apply Temporal EMA Smoothing
        val alpha = profile.emaAlpha.coerceIn(0.05f, 1.0f)
        if (!initialized) {
            smoothedR = boosted[0]
            smoothedG = boosted[1]
            smoothedB = boosted[2]
            initialized = true
        } else {
            smoothedR += alpha * (boosted[0] - smoothedR)
            smoothedG += alpha * (boosted[1] - smoothedG)
            smoothedB += alpha * (boosted[2] - smoothedB)
        }

        return FrameAnalysisResult(
            r = smoothedR.roundToInt().coerceIn(0, 255),
            g = smoothedG.roundToInt().coerceIn(0, 255),
            b = smoothedB.roundToInt().coerceIn(0, 255),
            isBlackFrame = false,
            isDrmFallbackActive = false,
            activeTopRow = topRow,
            activeBottomRow = bottomRow
        )
    }

    private fun handleBlackFrame(topRow: Int, bottomRow: Int): FrameAnalysisResult {
        consecutiveBlackFrames++
        val isDrm = consecutiveBlackFrames >= DRM_BLACK_FRAME_TRIGGER_COUNT
        val decay = 0.75f
        smoothedR *= decay
        smoothedG *= decay
        smoothedB *= decay
        return FrameAnalysisResult(
            r = smoothedR.roundToInt().coerceIn(0, 255),
            g = smoothedG.roundToInt().coerceIn(0, 255),
            b = smoothedB.roundToInt().coerceIn(0, 255),
            isBlackFrame = true,
            isDrmFallbackActive = isDrm,
            activeTopRow = topRow,
            activeBottomRow = bottomRow
        )
    }

    private fun isRowBlack(pixels: IntArray, width: Int, row: Int): Boolean {
        val offset = row * width
        var brightPixels = 0
        for (x in 0 until width) {
            val p = pixels[offset + x]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (max(r, max(g, b)) > BLACK_LUMA_THRESHOLD) {
                brightPixels++
                if (brightPixels > width / 16) return false
            }
        }
        return true
    }

    private fun applySaturationBoost(r: Float, g: Float, b: Float, boost: Float): FloatArray {
        val rf = (r / 255f).coerceIn(0f, 1f)
        val gf = (g / 255f).coerceIn(0f, 1f)
        val bf = (b / 255f).coerceIn(0f, 1f)

        val maxC = max(rf, max(gf, bf))
        val minC = min(rf, min(gf, bf))
        val delta = maxC - minC

        if (maxC <= 0.001f || delta <= 0.001f) {
            return floatArrayOf(r, g, b)
        }

        val hue = when (maxC) {
            rf -> 60f * (((gf - bf) / delta) % 6f)
            gf -> 60f * (((bf - rf) / delta) + 2f)
            else -> 60f * (((rf - gf) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }

        val sat = ((delta / maxC) * boost).coerceIn(0f, 1f)
        // Slight gamma lift so mid-tones glow well on RGB LED strips
        val value = maxC.toDouble().pow(0.88).toFloat().coerceIn(0f, 1f)

        val c = value * sat
        val x = c * (1f - abs(((hue / 60f) % 2f) - 1f))
        val m = value - c

        val (r1, g1, b1) = when {
            hue < 60f -> Triple(c, x, 0f)
            hue < 120f -> Triple(x, c, 0f)
            hue < 180f -> Triple(0f, c, x)
            hue < 240f -> Triple(0f, x, c)
            hue < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

        return floatArrayOf(
            ((r1 + m) * 255f).coerceIn(0f, 255f),
            ((g1 + m) * 255f).coerceIn(0f, 255f),
            ((b1 + m) * 255f).coerceIn(0f, 255f)
        )
    }
}
