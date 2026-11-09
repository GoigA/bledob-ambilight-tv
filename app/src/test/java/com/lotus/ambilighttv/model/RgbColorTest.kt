package com.lotus.ambilighttv.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RgbColorTest {

    @Test
    fun `channel extractors extract accurate R, G, B components`() {
        val color = RgbColor.fromRgb(255, 110, 30)
        assertEquals(255, color.r)
        assertEquals(110, color.g)
        assertEquals(30, color.b)
        assertEquals(255, color.red)
        assertEquals(110, color.green)
        assertEquals(30, color.blue)
        assertEquals(255, color.maxComponent)
        assertEquals(30, color.minComponent)
    }

    @Test
    fun `out-of-range RGB values are clamped to 0 and 255`() {
        val underflow = RgbColor.fromRgb(-50, -1, 100)
        assertEquals(0, underflow.r)
        assertEquals(0, underflow.g)
        assertEquals(100, underflow.b)

        val overflow = RgbColor.fromRgb(300, 256, 128)
        assertEquals(255, overflow.r)
        assertEquals(255, overflow.g)
        assertEquals(128, overflow.b)
    }

    @Test
    fun `fromArgb extracts RGB channels and ignores alpha`() {
        val argb = (0x80 shl 24) or (200 shl 16) or (150 shl 8) or 50
        val color = RgbColor.fromArgb(argb)
        assertEquals(200, color.r)
        assertEquals(150, color.g)
        assertEquals(50, color.b)
    }

    @Test
    fun `presets match expected channel definitions`() {
        assertEquals(0, RgbColor.OFF.r)
        assertEquals(0, RgbColor.OFF.g)
        assertEquals(0, RgbColor.OFF.b)

        assertEquals(0, RgbColor.BLACK.r)
        assertEquals(0, RgbColor.BLACK.g)
        assertEquals(0, RgbColor.BLACK.b)

        assertEquals(255, RgbColor.WHITE.r)
        assertEquals(255, RgbColor.WHITE.g)
        assertEquals(255, RgbColor.WHITE.b)

        assertEquals(255, RgbColor.WARM_AMBER.r)
        assertEquals(140, RgbColor.WARM_AMBER.g)
        assertEquals(40, RgbColor.WARM_AMBER.b)

        assertEquals(210, RgbColor.CINEMA_WARM.r)
        assertEquals(115, RgbColor.CINEMA_WARM.g)
        assertEquals(45, RgbColor.CINEMA_WARM.b)

        assertEquals(0, RgbColor.GAMING_CYAN.r)
        assertEquals(229, RgbColor.GAMING_CYAN.g)
        assertEquals(255, RgbColor.GAMING_CYAN.b)

        assertEquals(255, RgbColor.RED.r)
        assertEquals(0, RgbColor.RED.g)
        assertEquals(0, RgbColor.RED.b)

        assertEquals(0, RgbColor.GREEN.r)
        assertEquals(255, RgbColor.GREEN.g)
        assertEquals(0, RgbColor.GREEN.b)

        assertEquals(0, RgbColor.BLUE.r)
        assertEquals(0, RgbColor.BLUE.g)
        assertEquals(255, RgbColor.BLUE.b)
    }

    @Test
    fun `HSV conversion produces accurate RGB channels for primary and secondary hues`() {
        val red = RgbColor.fromHsv(0f, 1f, 1f)
        assertEquals(255, red.r)
        assertEquals(0, red.g)
        assertEquals(0, red.b)

        val yellow = RgbColor.fromHsv(60f, 1f, 1f)
        assertEquals(255, yellow.r)
        assertEquals(255, yellow.g)
        assertEquals(0, yellow.b)

        val green = RgbColor.fromHsv(120f, 1f, 1f)
        assertEquals(0, green.r)
        assertEquals(255, green.g)
        assertEquals(0, green.b)

        val cyan = RgbColor.fromHsv(180f, 1f, 1f)
        assertEquals(0, cyan.r)
        assertEquals(255, cyan.g)
        assertEquals(255, cyan.b)

        val blue = RgbColor.fromHsv(240f, 1f, 1f)
        assertEquals(0, blue.r)
        assertEquals(0, blue.g)
        assertEquals(255, blue.b)

        val magenta = RgbColor.fromHsv(300f, 1f, 1f)
        assertEquals(255, magenta.r)
        assertEquals(0, magenta.g)
        assertEquals(255, magenta.b)
    }

    @Test
    fun `HSV angle wrapping and greyscale values work cleanly`() {
        val wrappedRed = RgbColor.fromHsv(720f, 1f, 1f)
        assertEquals(255, wrappedRed.r)
        assertEquals(0, wrappedRed.g)
        assertEquals(0, wrappedRed.b)

        val black = RgbColor.fromHsv(0f, 0f, 0f)
        assertEquals(0, black.r)
        assertEquals(0, black.g)
        assertEquals(0, black.b)

        val white = RgbColor.fromHsv(0f, 0f, 1f)
        assertEquals(255, white.r)
        assertEquals(255, white.g)
        assertEquals(255, white.b)
    }

    @Test
    fun `hue, saturation, and value extractors return correct parameters`() {
        val pureRed = RgbColor.RED
        assertEquals(0f, pureRed.hue(), 0.1f)
        assertEquals(1f, pureRed.saturation(), 0.01f)
        assertEquals(1f, pureRed.value(), 0.01f)

        val pureBlue = RgbColor.BLUE
        assertEquals(240f, pureBlue.hue(), 0.1f)
        assertEquals(1f, pureBlue.saturation(), 0.01f)
        assertEquals(1f, pureBlue.value(), 0.01f)
    }

    @Test
    fun `scaleBrightness scales channels proportionally`() {
        val base = RgbColor.fromRgb(200, 100, 50)
        val half = base.scaleBrightness(0.5f)
        assertEquals(100, half.r)
        assertEquals(50, half.g)
        assertEquals(25, half.b)

        val zero = base.scaleBrightness(0.0f)
        assertEquals(0, zero.r)
        assertEquals(0, zero.g)
        assertEquals(0, zero.b)

        val scaledPercent = base.scaleBrightness(50)
        assertEquals(100, scaledPercent.r)
        assertEquals(50, scaledPercent.g)
        assertEquals(25, scaledPercent.b)
    }

    @Test
    fun `lerp smoothly interpolates between two colors`() {
        val start = RgbColor.fromRgb(0, 0, 0)
        val stop = RgbColor.fromRgb(100, 200, 50)

        val mid = start.lerp(stop, 0.5f)
        assertEquals(50, mid.r)
        assertEquals(100, mid.g)
        assertEquals(25, mid.b)

        assertEquals(start, start.lerp(stop, 0.0f))
        assertEquals(stop, start.lerp(stop, 1.0f))
    }

    @Test
    fun `deltaTo calculates Manhattan channel distance accurately`() {
        val c1 = RgbColor.fromRgb(100, 150, 200)
        val c2 = RgbColor.fromRgb(110, 140, 220)
        // |100-110| + |150-140| + |200-220| = 10 + 10 + 20 = 40
        assertEquals(40, c1.deltaTo(c2))
        assertEquals(40, c2.deltaTo(c1))
        assertEquals(0, c1.deltaTo(c1))
    }

    @Test
    fun `toArgb, toHexString and fromHex roundtrip`() {
        val color = RgbColor.fromRgb(255, 110, 30)
        assertEquals("#FF6E1E", color.toHexString())
        assertEquals(color, RgbColor.fromHex("#FF6E1E"))
        assertEquals(color, RgbColor.fromHex("FF6E1E"))

        val argb = color.toArgb(alpha = 0xFF)
        assertEquals((0xFF shl 24) or (255 shl 16) or (110 shl 8) or 30, argb)
    }

    @Test
    fun `isBlack identifies black and dark frames`() {
        assertTrue(RgbColor.BLACK.isBlack(threshold = 10))
        assertTrue(RgbColor.fromRgb(5, 8, 10).isBlack(threshold = 10))
        assertFalse(RgbColor.fromRgb(11, 0, 0).isBlack(threshold = 10))
        assertFalse(RgbColor.WARM_AMBER.isBlack())
    }
}
