package com.lotus.ambilighttv.model

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Zero-allocation, immutable RGB color model represented as a 24-bit packed integer.
 *
 * Internal layout:
 * - Bits 23..16: Red channel (0..255)
 * - Bits 15..8:  Green channel (0..255)
 * - Bits 7..0:   Blue channel (0..255)
 *
 * As a `@JvmInline value class`, this provides compile-time type safety with zero runtime
 * allocation overhead on the JVM/ART heap, making it ideal for the 25-30 FPS Ambilight video loop.
 */
@JvmInline
value class RgbColor(val packed: Int) {

    /** Red channel component in range 0..255. */
    val r: Int get() = (packed shr 16) and 0xFF

    /** Green channel component in range 0..255. */
    val g: Int get() = (packed shr 8) and 0xFF

    /** Blue channel component in range 0..255. */
    val b: Int get() = packed and 0xFF

    /** Semantic alias for [r]. */
    val red: Int get() = r

    /** Semantic alias for [g]. */
    val green: Int get() = g

    /** Semantic alias for [b]. */
    val blue: Int get() = b

    /** Maximum component value among R, G, B channels (0..255). */
    val maxComponent: Int get() = maxOf(r, g, b)

    /** Minimum component value among R, G, B channels (0..255). */
    val minComponent: Int get() = minOf(r, g, b)

    /**
     * Perceived photometric luminance (Rec. 601 standard):
     * Y = 0.299 * R + 0.587 * G + 0.114 * B
     */
    val luma: Int get() = (0.299f * r + 0.587f * g + 0.114f * b).roundToInt().coerceIn(0, 255)

    /**
     * Checks if all color components are at or below a black luma threshold.
     */
    fun isBlack(threshold: Int = 10): Boolean = maxComponent <= threshold

    /**
     * Extracts the Hue angle in degrees (0.0f..360.0f) without heap allocations.
     */
    fun hue(): Float {
        val maxC = maxComponent
        val minC = minComponent
        val delta = maxC - minC
        if (delta == 0) return 0f

        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val maxF = maxC / 255f
        val deltaF = delta / 255f

        val h = when (maxF) {
            rf -> 60f * (((gf - bf) / deltaF) % 6f)
            gf -> 60f * (((bf - rf) / deltaF) + 2f)
            else -> 60f * (((rf - gf) / deltaF) + 4f)
        }
        return if (h < 0f) h + 360f else h
    }

    /**
     * Extracts the HSV Saturation (0.0f..1.0f) without heap allocations.
     */
    fun saturation(): Float {
        val maxC = maxComponent
        if (maxC == 0) return 0f
        return (maxC - minComponent).toFloat() / maxC.toFloat()
    }

    /**
     * Extracts the HSV Value / Brightness (0.0f..1.0f) without heap allocations.
     */
    fun value(): Float = maxComponent / 255f

    /**
     * Scales each channel by a floating-point multiplier (>= 0.0f).
     * Channels are clamped to 0..255.
     */
    fun scaleBrightness(scale: Float): RgbColor {
        val s = scale.coerceAtLeast(0f)
        return fromRgb(
            (r * s).roundToInt(),
            (g * s).roundToInt(),
            (b * s).roundToInt()
        )
    }

    /**
     * Scales each channel by a percentage (0..100%).
     */
    fun scaleBrightness(percent: Int): RgbColor = scaleBrightness(percent / 100f)

    /**
     * Performs linear interpolation (lerp) towards [target] by [fraction] (0.0f..1.0f).
     * Ideal for smooth temporal Exponential Moving Average (EMA) transitions between frames.
     */
    fun lerp(target: RgbColor, fraction: Float): RgbColor {
        val t = fraction.coerceIn(0f, 1f)
        return fromRgb(
            (r + (target.r - r) * t).roundToInt(),
            (g + (target.g - g) * t).roundToInt(),
            (b + (target.b - b) * t).roundToInt()
        )
    }

    /**
     * Manhattan distance (sum of absolute channel differences) between this and [other].
     * Used for rate throttling and minimum change thresholds before BLE transmission.
     */
    fun deltaTo(other: RgbColor): Int =
        abs(r - other.r) + abs(g - other.g) + abs(b - other.b)

    /**
     * Converts to a 32-bit ARGB packed integer (0xAARRGGBB), compatible with Android Views and Canvas.
     */
    fun toArgb(alpha: Int = 0xFF): Int =
        (alpha.coerceIn(0, 255) shl 24) or (packed and 0x00FFFFFF)

    /**
     * Formats this color as a standard uppercase CSS hex string, e.g. "#FF6E1E".
     */
    fun toHexString(): String = "#%02X%02X%02X".format(r, g, b)

    override fun toString(): String = "RgbColor(r=$r, g=$g, b=$b, hex=${toHexString()})"

    companion object {
        // --- Common Presets ---
        val OFF = RgbColor(0x000000)
        val BLACK = RgbColor(0x000000)
        val WHITE = RgbColor(0xFFFFFF)
        val WARM_AMBER = RgbColor((255 shl 16) or (140 shl 8) or 40)    // #FF8C28
        val CINEMA_WARM = RgbColor((210 shl 16) or (115 shl 8) or 45)   // #D2732D
        val GAMING_CYAN = RgbColor((0 shl 16) or (229 shl 8) or 255)    // #00E5FF

        // --- Standard Primary Presets ---
        val RED = RgbColor(0xFF0000)
        val GREEN = RgbColor(0x00FF00)
        val BLUE = RgbColor(0x0000FF)

        /**
         * Creates an [RgbColor] from integer RGB components in range 0..255.
         * Values outside 0..255 are automatically clamped.
         */
        fun fromRgb(r: Int, g: Int, b: Int): RgbColor {
            val rc = r.coerceIn(0, 255)
            val gc = g.coerceIn(0, 255)
            val bc = b.coerceIn(0, 255)
            return RgbColor((rc shl 16) or (gc shl 8) or bc)
        }

        /**
         * Creates an [RgbColor] from a 32-bit ARGB or RGB packed integer.
         * The alpha byte is ignored.
         */
        fun fromArgb(argb: Int): RgbColor =
            fromRgb((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

        /**
         * Creates an [RgbColor] from Hue (0..360), Saturation (0..1), and Value (0..1).
         * Executed with zero heap allocations (pure primitive local variables).
         */
        fun fromHsv(h: Float, s: Float, v: Float): RgbColor {
            val hNorm = (h % 360f).let { if (it < 0f) it + 360f else it }
            val sNorm = s.coerceIn(0f, 1f)
            val vNorm = v.coerceIn(0f, 1f)
            val c = vNorm * sNorm
            val hSector = hNorm / 60f
            val x = c * (1f - abs((hSector % 2f) - 1f))
            val m = vNorm - c

            var r1 = 0f
            var g1 = 0f
            var b1 = 0f
            when {
                hSector < 1f -> { r1 = c; g1 = x; b1 = 0f }
                hSector < 2f -> { r1 = x; g1 = c; b1 = 0f }
                hSector < 3f -> { r1 = 0f; g1 = c; b1 = x }
                hSector < 4f -> { r1 = 0f; g1 = x; b1 = c }
                hSector < 5f -> { r1 = x; g1 = 0f; b1 = c }
                else -> { r1 = c; g1 = 0f; b1 = x }
            }

            val red = ((r1 + m) * 255f).roundToInt().coerceIn(0, 255)
            val green = ((g1 + m) * 255f).roundToInt().coerceIn(0, 255)
            val blue = ((b1 + m) * 255f).roundToInt().coerceIn(0, 255)
            return fromRgb(red, green, blue)
        }

        /**
         * Parses a 6-character hex string (with or without leading '#'), e.g. "#FF6E1E" or "FF6E1E".
         * @throws IllegalArgumentException if the format is invalid.
         */
        fun fromHex(hex: String): RgbColor {
            val clean = hex.trim().removePrefix("#")
            require(clean.length == 6) { "Hex color must have 6 hex digits, got: '$hex'" }
            val parsed = clean.toInt(16)
            return fromRgb((parsed shr 16) and 0xFF, (parsed shr 8) and 0xFF, parsed and 0xFF)
        }

        /**
         * Convenience static interpolator between two colors.
         */
        fun lerp(start: RgbColor, stop: RgbColor, fraction: Float): RgbColor =
            start.lerp(stop, fraction)
    }
}
