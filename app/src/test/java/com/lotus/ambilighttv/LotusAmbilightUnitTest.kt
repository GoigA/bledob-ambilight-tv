package com.lotus.ambilighttv

import com.lotus.ambilighttv.ble.BleDobProtocol
import com.lotus.ambilighttv.engine.VividEdgeColorEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LotusAmbilightUnitTest {

    @Test
    fun `BLEDOB 0x07 Power ON and OFF packets match hardware specification`() {
        val powerOn = BleDobProtocol.createPowerOnPacket(BleDobProtocol.ProtocolVariant.BLEDOB_07)
        val powerOff = BleDobProtocol.createPowerOffPacket(BleDobProtocol.ProtocolVariant.BLEDOB_07)

        assertEquals("7E 07 04 FF 00 01 02 01 EF", BleDobProtocol.toHexString(powerOn))
        assertEquals("7E 07 04 00 00 00 02 01 EF", BleDobProtocol.toHexString(powerOff))
    }

    @Test
    fun `BLEDOB RGB packet encodes channels and channel order permutations accurately`() {
        val rgbPacket = BleDobProtocol.createColorPacket(
            r = 255,
            g = 128,
            b = 16,
            brightnessScale = 1.0f,
            channelOrder = BleDobProtocol.ChannelOrder.RGB,
            variant = BleDobProtocol.ProtocolVariant.BLEDOB_07
        )
        assertEquals("7E 07 05 03 FF 80 10 10 EF", BleDobProtocol.toHexString(rgbPacket))

        val grbPacket = BleDobProtocol.createColorPacket(
            r = 255,
            g = 128,
            b = 16,
            brightnessScale = 1.0f,
            channelOrder = BleDobProtocol.ChannelOrder.GRB,
            variant = BleDobProtocol.ProtocolVariant.BLEDOB_07
        )
        assertEquals("7E 07 05 03 80 FF 10 10 EF", BleDobProtocol.toHexString(grbPacket))
    }

    @Test
    fun `VividEdgeColorEngine crops letterbox black bars and extracts vivid border color`() {
        val engine = VividEdgeColorEngine(VividEdgeColorEngine.AmbilightProfile.GAMING)
        val width = 16
        val height = 12
        val frame = IntArray(width * height)

        // Top 2 rows and bottom 2 rows are black letterbox bars; active video is vivid red/orange
        for (y in 2 until 10) {
            for (x in 0 until width) {
                frame[y * width + x] = (0xFF shl 24) or (240 shl 16) or (40 shl 8) or 10
            }
        }

        val result = engine.processFrame(frame, width, height)
        assertFalse(result.isBlackFrame)
        assertFalse(result.isDrmFallbackActive)
        assertEquals(2, result.activeTopRow)
        assertEquals(9, result.activeBottomRow)
        assertTrue("Expected dominant red channel > 200, got ${result.r}", result.r > 200)
        assertTrue("Expected red > green", result.r > result.g)
    }

    @Test
    fun `VividEdgeColorEngine triggers DRM fallback after consecutive black frames`() {
        val engine = VividEdgeColorEngine(VividEdgeColorEngine.AmbilightProfile.DYNAMIC)
        val width = 16
        val height = 9
        val blackFrame = IntArray(width * height) // all 0x00000000

        var lastResult: VividEdgeColorEngine.FrameAnalysisResult? = null
        repeat(VividEdgeColorEngine.DRM_BLACK_FRAME_TRIGGER_COUNT) {
            lastResult = engine.processFrame(blackFrame, width, height)
        }

        assertTrue(lastResult!!.isBlackFrame)
        assertTrue(lastResult!!.isDrmFallbackActive)

        // When a normal non-DRM frame arrives, fallback immediately deactivates
        val brightFrame = IntArray(width * height) { (0xFF shl 24) or (20 shl 16) or (220 shl 8) or 40 }
        val recovered = engine.processFrame(brightFrame, width, height)
        assertFalse(recovered.isBlackFrame)
        assertFalse(recovered.isDrmFallbackActive)
        assertTrue(recovered.g > recovered.r)
    }
}
