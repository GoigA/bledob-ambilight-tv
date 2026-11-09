package com.lotus.ambilighttv.ble

import com.lotus.ambilighttv.model.RgbColor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class BleDobProtocolTest {

    // =========================================================================
    // 1. Frame Constant & Magic Number Verification
    // =========================================================================

    @Test
    fun `protocol delimiters and packet size are properly defined`() {
        assertEquals(0x7E.toByte(), BleDobProtocol.FRAME_HEADER)
        assertEquals(0xEF.toByte(), BleDobProtocol.FRAME_TRAILER)
        assertEquals(9, BleDobProtocol.PACKET_LENGTH)
        assertEquals(0x07.toByte(), BleDobProtocol.MODEL_BLEDOB_07)
        assertEquals(0x04.toByte(), BleDobProtocol.MODEL_BLEDOM_04)
        assertEquals(0x01.toByte(), BleDobProtocol.CMD_BRIGHTNESS)
        assertEquals(0x04.toByte(), BleDobProtocol.CMD_POWER)
        assertEquals(0x05.toByte(), BleDobProtocol.CMD_COLOR)
        assertEquals(0x03.toByte(), BleDobProtocol.SUB_CMD_COLOR)
        assertEquals(0x10.toByte(), BleDobProtocol.COLOR_MODIFIER_BLEDOB)
        assertEquals(0x00.toByte(), BleDobProtocol.COLOR_MODIFIER_BLEDOM)
    }

    // =========================================================================
    // 2. Nonsense Data Elimination
    // =========================================================================

    @Test
    fun `default MAC address is unconfigured empty string and does not contain personal MAC`() {
        assertEquals("", BleDobProtocol.UNCONFIGURED_MAC_ADDRESS)
        assertEquals("", BleDobProtocol.DEFAULT_MAC_ADDRESS)
        assertEquals(BleDobProtocol.UNCONFIGURED_MAC_ADDRESS, BleDobProtocol.DEFAULT_MAC_ADDRESS)
        assertFalse(
            "BleDobProtocol must not leak author's MAC address",
            BleDobProtocol.DEFAULT_MAC_ADDRESS.contains("BE:27:08:00:49:E2")
        )
    }

    // =========================================================================
    // 3. Static Cached Power Packets Verification
    // =========================================================================

    @Test
    fun `BLEDOB power packets match hardware specification and reuse cached arrays`() {
        val on1 = BleDobProtocol.createPowerOnPacket(BleDobProtocol.ProtocolVariant.BLEDOB_07)
        val on2 = BleDobProtocol.createPowerOnPacket(BleDobProtocol.ProtocolVariant.BLEDOB_07)
        val off1 = BleDobProtocol.createPowerOffPacket(BleDobProtocol.ProtocolVariant.BLEDOB_07)
        val off2 = BleDobProtocol.createPowerOffPacket(BleDobProtocol.ProtocolVariant.BLEDOB_07)

        assertSame("createPowerOnPacket must return cached static array for zero allocations", on1, on2)
        assertSame("createPowerOffPacket must return cached static array for zero allocations", off1, off2)

        assertEquals("7E 07 04 FF 00 01 02 01 EF", BleDobProtocol.toHexString(on1))
        assertEquals("7E 07 04 00 00 00 02 01 EF", BleDobProtocol.toHexString(off1))
    }

    @Test
    fun `BLEDOM power packets match hardware specification and reuse cached arrays`() {
        val on1 = BleDobProtocol.createPowerOnPacket(BleDobProtocol.ProtocolVariant.BLEDOM_04)
        val on2 = BleDobProtocol.createPowerOnPacket(BleDobProtocol.ProtocolVariant.BLEDOM_04)
        val off1 = BleDobProtocol.createPowerOffPacket(BleDobProtocol.ProtocolVariant.BLEDOM_04)
        val off2 = BleDobProtocol.createPowerOffPacket(BleDobProtocol.ProtocolVariant.BLEDOM_04)

        assertSame(on1, on2)
        assertSame(off1, off2)

        assertEquals("7E 04 04 F0 00 01 FF 00 EF", BleDobProtocol.toHexString(on1))
        assertEquals("7E 04 04 00 00 00 FF 00 EF", BleDobProtocol.toHexString(off1))
    }

    @Test
    fun `DUAL_HYBRID power packets map to BLEDOB master frames`() {
        val on = BleDobProtocol.createPowerOnPacket(BleDobProtocol.ProtocolVariant.DUAL_HYBRID)
        val off = BleDobProtocol.createPowerOffPacket(BleDobProtocol.ProtocolVariant.DUAL_HYBRID)

        assertSame(BleDobProtocol.POWER_ON_BLEDOB, on)
        assertSame(BleDobProtocol.POWER_OFF_BLEDOB, off)
    }

    // =========================================================================
    // 4. Zero-Allocation Color Framing & In-Place Mutation
    // =========================================================================

    @Test
    fun `writeColorPacket mutates provided buffer directly with zero allocation`() {
        val buffer = ByteArray(9)
        val returned = BleDobProtocol.writeColorPacket(
            outBuffer = buffer,
            r = 255,
            g = 128,
            b = 16,
            brightnessScale = 1.0f,
            channelOrder = BleDobProtocol.ChannelOrder.RGB,
            variant = BleDobProtocol.ProtocolVariant.BLEDOB_07
        )

        assertSame("writeColorPacket must mutate and return the exact same ByteArray instance", buffer, returned)
        assertEquals("7E 07 05 03 FF 80 10 10 EF", BleDobProtocol.toHexString(buffer))
    }

    @Test
    fun `writeColorPacket with RgbColor produces identical output to primitive overload`() {
        val bufferPrimitive = ByteArray(9)
        val bufferModel = ByteArray(9)
        val color = RgbColor.fromRgb(255, 128, 16)

        BleDobProtocol.writeColorPacket(
            outBuffer = bufferPrimitive,
            r = 255,
            g = 128,
            b = 16,
            brightnessScale = 0.5f,
            channelOrder = BleDobProtocol.ChannelOrder.GRB,
            variant = BleDobProtocol.ProtocolVariant.BLEDOB_07
        )

        BleDobProtocol.writeColorPacket(
            outBuffer = bufferModel,
            color = color,
            brightnessScale = 0.5f,
            channelOrder = BleDobProtocol.ChannelOrder.GRB,
            variant = BleDobProtocol.ProtocolVariant.BLEDOB_07
        )

        assertArrayEquals(bufferPrimitive, bufferModel)
        assertEquals("7E 07 05 03 40 7F 08 10 EF", BleDobProtocol.toHexString(bufferModel))
    }

    @Test
    fun `writeColorPacket handles all ChannelOrder permutations accurately`() {
        val r = 255 // 0xFF
        val g = 128 // 0x80
        val b = 16  // 0x10
        val buffer = ByteArray(9)

        // RGB -> FF 80 10
        BleDobProtocol.writeColorPacket(buffer, r, g, b, channelOrder = BleDobProtocol.ChannelOrder.RGB)
        assertEquals("7E 07 05 03 FF 80 10 10 EF", BleDobProtocol.toHexString(buffer))

        // GRB -> 80 FF 10
        BleDobProtocol.writeColorPacket(buffer, r, g, b, channelOrder = BleDobProtocol.ChannelOrder.GRB)
        assertEquals("7E 07 05 03 80 FF 10 10 EF", BleDobProtocol.toHexString(buffer))

        // BRG -> 10 FF 80
        BleDobProtocol.writeColorPacket(buffer, r, g, b, channelOrder = BleDobProtocol.ChannelOrder.BRG)
        assertEquals("7E 07 05 03 10 FF 80 10 EF", BleDobProtocol.toHexString(buffer))

        // RBG -> FF 10 80
        BleDobProtocol.writeColorPacket(buffer, r, g, b, channelOrder = BleDobProtocol.ChannelOrder.RBG)
        assertEquals("7E 07 05 03 FF 10 80 10 EF", BleDobProtocol.toHexString(buffer))

        // GBR -> 80 10 FF
        BleDobProtocol.writeColorPacket(buffer, r, g, b, channelOrder = BleDobProtocol.ChannelOrder.GBR)
        assertEquals("7E 07 05 03 80 10 FF 10 EF", BleDobProtocol.toHexString(buffer))

        // BGR -> 10 80 FF
        BleDobProtocol.writeColorPacket(buffer, r, g, b, channelOrder = BleDobProtocol.ChannelOrder.BGR)
        assertEquals("7E 07 05 03 10 80 FF 10 EF", BleDobProtocol.toHexString(buffer))
    }

    @Test
    fun `writeColorPacket for BLEDOM_04 sets model byte 0x04 and modifier 0x00`() {
        val buffer = ByteArray(9)
        BleDobProtocol.writeColorPacket(
            outBuffer = buffer,
            r = 255,
            g = 128,
            b = 16,
            variant = BleDobProtocol.ProtocolVariant.BLEDOM_04
        )
        assertEquals("7E 04 05 03 FF 80 10 00 EF", BleDobProtocol.toHexString(buffer))
    }

    @Test
    fun `writeColorPacket clamps out-of-range values safely`() {
        val buffer = ByteArray(9)
        BleDobProtocol.writeColorPacket(
            outBuffer = buffer,
            r = 300,
            g = -50,
            b = 100,
            brightnessScale = 1.5f // clamped to 1.0f
        )
        // r -> 255 (FF), g -> 0 (00), b -> 100 (64)
        assertEquals("7E 07 05 03 FF 00 64 10 EF", BleDobProtocol.toHexString(buffer))
    }

    @Test
    fun `writeColorPacket throws IllegalArgumentException when buffer is undersized`() {
        val smallBuffer = ByteArray(8)
        assertThrows(IllegalArgumentException::class.java) {
            BleDobProtocol.writeColorPacket(smallBuffer, 255, 255, 255)
        }
    }

    // =========================================================================
    // 5. Hardware Brightness Framing Verification
    // =========================================================================

    @Test
    fun `createBrightnessPacket generates valid 9-byte packet and clamps percentage`() {
        val p100 = BleDobProtocol.createBrightnessPacket(100, BleDobProtocol.ProtocolVariant.BLEDOB_07)
        assertEquals("7E 07 01 64 FF FF FF 00 EF", BleDobProtocol.toHexString(p100))

        val p0 = BleDobProtocol.createBrightnessPacket(0, BleDobProtocol.ProtocolVariant.BLEDOB_07)
        assertEquals("7E 07 01 00 FF FF FF 00 EF", BleDobProtocol.toHexString(p0))

        val p50Bledom = BleDobProtocol.createBrightnessPacket(50, BleDobProtocol.ProtocolVariant.BLEDOM_04)
        assertEquals("7E 04 01 32 FF FF FF 00 EF", BleDobProtocol.toHexString(p50Bledom))

        val pClampedHigh = BleDobProtocol.createBrightnessPacket(150, BleDobProtocol.ProtocolVariant.BLEDOB_07)
        assertEquals("7E 07 01 64 FF FF FF 00 EF", BleDobProtocol.toHexString(pClampedHigh))

        val pClampedLow = BleDobProtocol.createBrightnessPacket(-10, BleDobProtocol.ProtocolVariant.BLEDOB_07)
        assertEquals("7E 07 01 00 FF FF FF 00 EF", BleDobProtocol.toHexString(pClampedLow))
    }

    // =========================================================================
    // 6. Hex String Formatting Verification
    // =========================================================================

    @Test
    fun `toHexString formats byte arrays accurately without padding errors`() {
        assertEquals("", BleDobProtocol.toHexString(ByteArray(0)))
        assertEquals("00", BleDobProtocol.toHexString(byteArrayOf(0x00.toByte())))
        assertEquals("FF", BleDobProtocol.toHexString(byteArrayOf(0xFF.toByte())))
        assertEquals("7E 07 EF", BleDobProtocol.toHexString(byteArrayOf(0x7E.toByte(), 0x07.toByte(), 0xEF.toByte())))
    }
}
