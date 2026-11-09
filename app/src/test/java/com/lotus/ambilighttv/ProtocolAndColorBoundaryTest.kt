package com.lotus.ambilighttv

import com.lotus.ambilighttv.ble.BleDobProtocol
import com.lotus.ambilighttv.model.BleMacAddress
import com.lotus.ambilighttv.model.RgbColor
import com.lotus.ambilighttv.state.AmbilightUiState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Empirical Adversarial Challenger Test Suite for Milestone M1.
 *
 * Stress-tests domain invariants, boundary conditions, arithmetic division-by-zero,
 * buffer mutation isolation, channel permutations, and absence of nonsense/hardcoded data.
 */
class ProtocolAndColorBoundaryTest {

    // =========================================================================
    // 1. RgbColor Adversarial Challenges
    // =========================================================================

    @Test
    fun `RgbColor fromRgb clamps extreme underflow and overflow inputs to 0 and 255`() {
        val color = RgbColor.fromRgb(-50, 300, 1000)
        assertEquals("Red channel must clamp -50 to 0", 0, color.r)
        assertEquals("Green channel must clamp 300 to 255", 255, color.g)
        assertEquals("Blue channel must clamp 1000 to 255", 255, color.b)
        assertEquals("Packed representation must match clamped components", 0x00FFFF, color.packed)

        val extremes = RgbColor.fromRgb(Int.MIN_VALUE, 0, Int.MAX_VALUE)
        assertEquals(0, extremes.r)
        assertEquals(0, extremes.g)
        assertEquals(255, extremes.b)
    }

    @Test
    fun `RgbColor pure greys avoid division by zero and return 0 hue and 0 saturation`() {
        val greys = listOf(
            RgbColor.fromRgb(0, 0, 0),
            RgbColor.fromRgb(1, 1, 1),
            RgbColor.fromRgb(128, 128, 128),
            RgbColor.fromRgb(254, 254, 254),
            RgbColor.fromRgb(255, 255, 255)
        )

        for (grey in greys) {
            val h = grey.hue()
            val s = grey.saturation()
            val v = grey.value()

            assertFalse("Hue must not be NaN for grey $grey", h.isNaN())
            assertFalse("Hue must not be Infinite for grey $grey", h.isInfinite())
            assertEquals("Hue for grey must be 0.0f", 0f, h, 0.0001f)

            assertFalse("Saturation must not be NaN for grey $grey", s.isNaN())
            assertFalse("Saturation must not be Infinite for grey $grey", s.isInfinite())
            assertEquals("Saturation for grey must be 0.0f", 0f, s, 0.0001f)

            assertTrue("Value must be within [0.0, 1.0]", v in 0f..1f)
        }
    }

    @Test
    fun `RgbColor scaleBrightness handles negative and extreme scale factors safely`() {
        val base = RgbColor.fromRgb(100, 150, 200)

        // Negative scale factors must clamp to 0 (resulting in BLACK)
        val negative = base.scaleBrightness(-1.0f)
        assertEquals(0, negative.r)
        assertEquals(0, negative.g)
        assertEquals(0, negative.b)
        assertEquals(RgbColor.BLACK, negative)

        val largeNegative = base.scaleBrightness(-999.0f)
        assertEquals(RgbColor.BLACK, largeNegative)

        // Extreme scale factors (> 10.0f) must clamp individual channels to 255
        val extreme = base.scaleBrightness(10.0f)
        assertEquals(255, extreme.r)
        assertEquals(255, extreme.g)
        assertEquals(255, extreme.b)
        assertEquals(RgbColor.WHITE, extreme)

        val superExtreme = base.scaleBrightness(1000.0f)
        assertEquals(RgbColor.WHITE, superExtreme)

        // Integer percentage scale
        val negativePercent = base.scaleBrightness(-50)
        assertEquals(RgbColor.BLACK, negativePercent)

        val extremePercent = base.scaleBrightness(500)
        assertEquals(RgbColor.WHITE, extremePercent)
    }

    @Test
    fun `RgbColor lerp clamps fraction below 0 and above 1`() {
        val start = RgbColor.fromRgb(20, 40, 60)
        val target = RgbColor.fromRgb(100, 150, 200)

        // Fraction < 0.0f should clamp to 0.0f (returning start)
        val underflow = start.lerp(target, -0.5f)
        assertEquals("Lerp with negative fraction must return start color", start, underflow)

        val severeUnderflow = start.lerp(target, -100.0f)
        assertEquals(start, severeUnderflow)

        // Fraction > 1.0f should clamp to 1.0f (returning target)
        val overflow = start.lerp(target, 1.5f)
        assertEquals("Lerp with fraction > 1.0 must return target color", target, overflow)

        val severeOverflow = start.lerp(target, 100.0f)
        assertEquals(target, severeOverflow)

        // Self interpolation
        assertEquals(start, start.lerp(start, 0.5f))
    }

    @Test
    fun `RgbColor deltaTo between extremes equals exactly 765 and obeys metric axioms`() {
        val distance = RgbColor.BLACK.deltaTo(RgbColor.WHITE)
        assertEquals("Manhattan distance between BLACK and WHITE must be 765", 765, distance)

        val reverseDistance = RgbColor.WHITE.deltaTo(RgbColor.BLACK)
        assertEquals("Distance must be symmetric", distance, reverseDistance)

        // Identity of indiscernibles
        assertEquals(0, RgbColor.BLACK.deltaTo(RgbColor.BLACK))
        assertEquals(0, RgbColor.WHITE.deltaTo(RgbColor.WHITE))
        assertEquals(0, RgbColor.WARM_AMBER.deltaTo(RgbColor.WARM_AMBER))
    }

    @Test
    fun `RgbColor toArgb clamps alpha channel appropriately`() {
        val color = RgbColor.fromRgb(10, 20, 30)

        val clampedLow = color.toArgb(alpha = -50)
        assertEquals(0x000A141E, clampedLow)

        val clampedHigh = color.toArgb(alpha = 300)
        assertEquals(0xFF0A141E.toInt(), clampedHigh)
    }

    // =========================================================================
    // 2. BleMacAddress Adversarial Challenges
    // =========================================================================

    @Test
    fun `BleMacAddress rejects all forms of malformed inputs`() {
        val malformedCandidates = listOf(
            "",                                  // empty string
            "   ",                               // whitespace only
            "00:11:22:33:44",                    // 5 octets
            "00:11:22:33:44:55:66",              // 7 octets
            "0:11:22:33:44:55",                  // 1-digit octet
            "000:11:22:33:44:55",                // 3-digit octet
            "GG:HH:II:JJ:KK:LL",                // non-hex letters
            "00:11:22:33:44:5G",                // single invalid char
            "00:11:22:33:44:5!",                // punctuation in octet
            "00.11.22.33.44.55",                // period delimiters
            "00_11_22_33_44_55",                // underscore delimiters
            "001122334455",                      // no delimiters
            "BE:27:08:00:49:E2\u0000"           // null terminator suffix
        )

        for (cand in malformedCandidates) {
            assertFalse("Candidate '$cand' must be invalid", BleMacAddress.isValid(cand))
            assertNull("fromOrNull for '$cand' must return null", BleMacAddress.fromOrNull(cand))
            assertThrows(IllegalArgumentException::class.java) {
                BleMacAddress.parse(cand)
            }
        }
    }

    @Test
    fun `BleMacAddress normalizes lowercase, hyphens, and mixed delimiters to standard uppercase colons`() {
        val variations = listOf(
            "be:27:08:00:49:e2",
            "BE-27-08-00-49-E2",
            "be-27-08-00-49-e2",
            "BE:27-08:00-49:E2",
            "   be-27-08-00-49-e2   \n"
        )

        for (v in variations) {
            assertTrue("Variation '$v' must be valid", BleMacAddress.isValid(v))
            val parsed = BleMacAddress.parse(v)
            assertEquals("Normalized address must be uppercase colons", "BE:27:08:00:49:E2", parsed.address)
            assertEquals("BE:27:08:00:49:E2", parsed.formatted)
            assertTrue(parsed.isConfigured)
        }
    }

    @Test
    fun `BleMacAddress UNCONFIGURED state invariants`() {
        val unconfigured = BleMacAddress.UNCONFIGURED
        assertFalse("UNCONFIGURED.isConfigured must be false", unconfigured.isConfigured)
        assertEquals("", unconfigured.address)
        assertEquals("", unconfigured.formatted)
        assertFalse(BleMacAddress.isValid(unconfigured.address))

        assertThrows(IllegalStateException::class.java) {
            unconfigured.toByteArray()
        }
    }

    @Test
    fun `BleMacAddress fromBytes requires exactly 6 bytes and preserves byte values`() {
        assertThrows(IllegalArgumentException::class.java) {
            BleMacAddress.fromBytes(ByteArray(5))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BleMacAddress.fromBytes(ByteArray(7))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BleMacAddress.fromBytes(ByteArray(0))
        }

        // Test with negative signed byte values (e.g. 0xFF, 0xFE)
        val bytes = byteArrayOf(
            0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte(),
            0xFC.toByte(), 0xFB.toByte(), 0xFA.toByte()
        )
        val mac = BleMacAddress.fromBytes(bytes)
        assertEquals("FF:FE:FD:FC:FB:FA", mac.address)
        assertArrayEquals(bytes, mac.toByteArray())
    }

    // =========================================================================
    // 3. BleDobProtocol Framing & Buffer Isolation Challenges
    // =========================================================================

    @Test
    fun `BleDobProtocol framing invariants are strictly preserved`() {
        assertEquals("Packet length must be exactly 9 bytes", 9, BleDobProtocol.PACKET_LENGTH)
        assertEquals(0x7E.toByte(), BleDobProtocol.FRAME_HEADER)
        assertEquals(0xEF.toByte(), BleDobProtocol.FRAME_TRAILER)

        // Verify writeColorPacket framing
        val buffer = ByteArray(BleDobProtocol.PACKET_LENGTH)
        BleDobProtocol.writeColorPacket(buffer, 10, 20, 30)
        assertEquals("Frame header must be 0x7E", 0x7E.toByte(), buffer[0])
        assertEquals("Model must be 0x07", 0x07.toByte(), buffer[1])
        assertEquals("Command must be 0x05 (COLOR)", 0x05.toByte(), buffer[2])
        assertEquals("Sub-command must be 0x03", 0x03.toByte(), buffer[3])
        assertEquals("Frame trailer must be 0xEF", 0xEF.toByte(), buffer[8])

        // Verify writeBrightnessPacket framing
        BleDobProtocol.writeBrightnessPacket(buffer, 80)
        assertEquals(0x7E.toByte(), buffer[0])
        assertEquals(0x07.toByte(), buffer[1])
        assertEquals(0x01.toByte(), buffer[2]) // CMD_BRIGHTNESS
        assertEquals(80.toByte(), buffer[3])
        assertEquals(0xFF.toByte(), buffer[4])
        assertEquals(0xFF.toByte(), buffer[5])
        assertEquals(0xFF.toByte(), buffer[6])
        assertEquals(0x00.toByte(), buffer[7])
        assertEquals(0xEF.toByte(), buffer[8])
    }

    @Test
    fun `BleDobProtocol writeColorPacket strictly isolates buffer mutation and preserves outer indices`() {
        val canary: Byte = 0xA5.toByte()
        val totalSize = 32
        val buffer = ByteArray(totalSize) { canary }

        BleDobProtocol.writeColorPacket(
            outBuffer = buffer,
            r = 255,
            g = 128,
            b = 64,
            brightnessScale = 1.0f,
            channelOrder = BleDobProtocol.ChannelOrder.RGB,
            variant = BleDobProtocol.ProtocolVariant.BLEDOB_07
        )

        // Indices 0..8 must be mutated with protocol packet
        assertEquals(0x7E.toByte(), buffer[0])
        assertEquals(0x07.toByte(), buffer[1])
        assertEquals(0x05.toByte(), buffer[2])
        assertEquals(0x03.toByte(), buffer[3])
        assertEquals(255.toByte(), buffer[4])
        assertEquals(128.toByte(), buffer[5])
        assertEquals(64.toByte(), buffer[6])
        assertEquals(0x10.toByte(), buffer[7])
        assertEquals(0xEF.toByte(), buffer[8])

        // Indices 9..31 must remain untouched canary value
        for (i in 9 until totalSize) {
            assertEquals("Index $i beyond packet must remain unmutated", canary, buffer[i])
        }
    }

    @Test
    fun `BleDobProtocol writeBrightnessPacket strictly isolates buffer mutation and preserves outer indices`() {
        val canary: Byte = 0x5A.toByte()
        val totalSize = 25
        val buffer = ByteArray(totalSize) { canary }

        BleDobProtocol.writeBrightnessPacket(
            outBuffer = buffer,
            percent = 75,
            variant = BleDobProtocol.ProtocolVariant.BLEDOM_04
        )

        assertEquals(0x7E.toByte(), buffer[0])
        assertEquals(0x04.toByte(), buffer[1]) // BLEDOM model
        assertEquals(0x01.toByte(), buffer[2])
        assertEquals(75.toByte(), buffer[3])
        assertEquals(0xFF.toByte(), buffer[4])
        assertEquals(0xFF.toByte(), buffer[5])
        assertEquals(0xFF.toByte(), buffer[6])
        assertEquals(0x00.toByte(), buffer[7])
        assertEquals(0xEF.toByte(), buffer[8])

        for (i in 9 until totalSize) {
            assertEquals("Index $i beyond packet must remain unmutated", canary, buffer[i])
        }
    }

    @Test
    fun `BleDobProtocol verifies all 6 ChannelOrder permutations produce exact byte sequences`() {
        val r: Byte = 0x11.toByte()
        val g: Byte = 0x22.toByte()
        val b: Byte = 0x33.toByte()

        val expectedMappings = mapOf(
            BleDobProtocol.ChannelOrder.RGB to byteArrayOf(r, g, b),
            BleDobProtocol.ChannelOrder.GRB to byteArrayOf(g, r, b),
            BleDobProtocol.ChannelOrder.BRG to byteArrayOf(b, r, g),
            BleDobProtocol.ChannelOrder.RBG to byteArrayOf(r, b, g),
            BleDobProtocol.ChannelOrder.GBR to byteArrayOf(g, b, r),
            BleDobProtocol.ChannelOrder.BGR to byteArrayOf(b, g, r)
        )

        for ((order, expected) in expectedMappings) {
            // Test in-place writeChannels
            val out = ByteArray(9)
            order.writeChannels(r, g, b, out, offset = 4)
            assertEquals("Order $order failed at offset 4", expected[0], out[4])
            assertEquals("Order $order failed at offset 5", expected[1], out[5])
            assertEquals("Order $order failed at offset 6", expected[2], out[6])

            // Test legacy mapChannels
            val mapped = order.mapChannels(0x11, 0x22, 0x33)
            assertEquals(expected[0].toInt(), mapped[0])
            assertEquals(expected[1].toInt(), mapped[1])
            assertEquals(expected[2].toInt(), mapped[2])

            // Test full writeColorPacket
            val fullBuffer = ByteArray(9)
            BleDobProtocol.writeColorPacket(
                outBuffer = fullBuffer,
                r = 0x11,
                g = 0x22,
                b = 0x33,
                channelOrder = order
            )
            assertEquals(expected[0], fullBuffer[4])
            assertEquals(expected[1], fullBuffer[5])
            assertEquals(expected[2], fullBuffer[6])
        }
    }

    @Test
    fun `BleDobProtocol writeColorPacket clamps scale and channels safely`() {
        val buffer = ByteArray(9)

        // Negative brightness scale clamps to 0.0f
        BleDobProtocol.writeColorPacket(
            outBuffer = buffer,
            r = 255,
            g = 255,
            b = 255,
            brightnessScale = -0.5f
        )
        assertEquals(0.toByte(), buffer[4])
        assertEquals(0.toByte(), buffer[5])
        assertEquals(0.toByte(), buffer[6])

        // Excessive brightness scale clamps to 1.0f
        BleDobProtocol.writeColorPacket(
            outBuffer = buffer,
            r = 100,
            g = 150,
            b = 200,
            brightnessScale = 5.0f
        )
        assertEquals(100.toByte(), buffer[4])
        assertEquals(150.toByte(), buffer[5])
        assertEquals(200.toByte(), buffer[6])
    }

    // =========================================================================
    // 4. Nonsense Data and Personal Identifier Elimination Checks
    // =========================================================================

    @Test
    fun `Personal developer MAC and mock telemetry are completely absent from default states`() {
        val forbiddenDevMac = "BE:27:08:00:49:E2"

        // BleDobProtocol defaults
        assertEquals("", BleDobProtocol.DEFAULT_MAC_ADDRESS)
        assertEquals("", BleDobProtocol.UNCONFIGURED_MAC_ADDRESS)
        assertFalse(BleDobProtocol.DEFAULT_MAC_ADDRESS.contains(forbiddenDevMac))

        // AmbilightUiState defaults
        val defaultState = AmbilightUiState()
        assertEquals("Initial red channel must be 0", 0, defaultState.currentR)
        assertEquals("Initial green channel must be 0", 0, defaultState.currentG)
        assertEquals("Initial blue channel must be 0", 0, defaultState.currentB)
        assertEquals("Initial hex packet must be empty", "", defaultState.lastHexPacket)
        assertEquals("Initial target MAC must be unconfigured", "", defaultState.targetMacAddress)
        assertEquals("Connected device name must be unconfigured", "Nenhuma fita conectada", defaultState.connectedDeviceName)

        assertFalse("Target MAC must not leak developer hardware", defaultState.targetMacAddress.contains(forbiddenDevMac))
        assertFalse("Current color must not be mock orange (255, 110, 30)",
            defaultState.currentR == 255 && defaultState.currentG == 110 && defaultState.currentB == 30
        )
    }

    // =========================================================================
    // 5. Generative Stress Testing Loop (10,000 iterations)
    // =========================================================================

    @Test
    fun `generative stress loop verifies invariants over 10000 random inputs`() {
        val random = Random(42)
        val packetBuffer = ByteArray(9)

        for (i in 0 until 10_000) {
            val r = random.nextInt(-500, 500)
            val g = random.nextInt(-500, 500)
            val b = random.nextInt(-500, 500)

            val color = RgbColor.fromRgb(r, g, b)
            assertTrue("Clamped red channel in 0..255", color.r in 0..255)
            assertTrue("Clamped green channel in 0..255", color.g in 0..255)
            assertTrue("Clamped blue channel in 0..255", color.b in 0..255)
            assertTrue("Luma in 0..255", color.luma in 0..255)

            val h = color.hue()
            val s = color.saturation()
            val v = color.value()

            assertFalse("Hue not NaN", h.isNaN())
            assertFalse("Sat not NaN", s.isNaN())
            assertFalse("Val not NaN", v.isNaN())
            assertTrue("Hue in [0, 360]", h in 0f..360f)
            assertTrue("Saturation in [0, 1]", s in 0f..1f)
            assertTrue("Value in [0, 1]", v in 0f..1f)

            // Scaled brightness
            val scale = random.nextFloat() * 10f - 2f
            val scaled = color.scaleBrightness(scale)
            assertTrue(scaled.r in 0..255)
            assertTrue(scaled.g in 0..255)
            assertTrue(scaled.b in 0..255)

            // Lerp
            val fraction = random.nextFloat() * 4f - 1.5f
            val targetColor = RgbColor.fromRgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            val lerped = color.lerp(targetColor, fraction)
            assertTrue(lerped.r in 0..255)
            assertTrue(lerped.g in 0..255)
            assertTrue(lerped.b in 0..255)

            // Metric distance
            val dist = color.deltaTo(targetColor)
            assertTrue("Manhattan distance non-negative", dist >= 0)
            assertTrue("Manhattan distance <= 765", dist <= 765)
            assertEquals("Symmetry of distance", dist, targetColor.deltaTo(color))

            // Protocol packet generation
            val order = BleDobProtocol.ChannelOrder.values()[random.nextInt(6)]
            val variant = BleDobProtocol.ProtocolVariant.values()[random.nextInt(3)]
            BleDobProtocol.writeColorPacket(packetBuffer, color, scale, order, variant)

            assertEquals(0x7E.toByte(), packetBuffer[0])
            assertEquals(0xEF.toByte(), packetBuffer[8])
        }
    }
}

