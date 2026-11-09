package com.lotus.ambilighttv.ble

import java.util.UUID

/**
 * Pure protocol encoder for LotusLantern / ELK-BLEDOB / BLEDOB controllers.
 * Target device: BLEDOB (MAC: BE:27:08:00:49:E2)
 */
object BleDobProtocol {
    const val DEFAULT_MAC_ADDRESS = "BE:27:08:00:49:E2"
    const val DEFAULT_DEVICE_NAME = "BLEDOB"

    val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val FALLBACK_SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")

    val WRITE_CHAR_UUID: UUID = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb")
    val FALLBACK_WRITE_CHAR_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    val NOTIFY_CHAR_UUID: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")

    enum class ProtocolVariant(val label: String) {
        BLEDOB_07("BLEDOB (0x07)"),
        BLEDOM_04("ELK-BLEDOM (0x04)"),
        DUAL_HYBRID("Híbrido (0x07 + 0x04)")
    }

    enum class ChannelOrder(val label: String) {
        RGB("RGB (Padrão)"),
        GRB("GRB"),
        BRG("BRG"),
        RBG("RBG"),
        GBR("GBR"),
        BGR("BGR");

        fun mapChannels(r: Int, g: Int, b: Int): IntArray {
            val rc = r.coerceIn(0, 255)
            val gc = g.coerceIn(0, 255)
            val bc = b.coerceIn(0, 255)
            return when (this) {
                RGB -> intArrayOf(rc, gc, bc)
                GRB -> intArrayOf(gc, rc, bc)
                BRG -> intArrayOf(bc, rc, gc)
                RBG -> intArrayOf(rc, bc, gc)
                GBR -> intArrayOf(gc, bc, rc)
                BGR -> intArrayOf(bc, gc, rc)
            }
        }
    }

    /**
     * Generates the 9-byte Power ON command for BLEDOB / ELK-BLEDOM.
     */
    fun createPowerOnPacket(variant: ProtocolVariant = ProtocolVariant.BLEDOB_07): ByteArray {
        return when (variant) {
            ProtocolVariant.BLEDOB_07, ProtocolVariant.DUAL_HYBRID -> byteArrayOf(
                0x7E.toByte(),
                0x07.toByte(),
                0x04.toByte(),
                0xFF.toByte(),
                0x00.toByte(),
                0x01.toByte(),
                0x02.toByte(),
                0x01.toByte(),
                0xEF.toByte()
            )
            ProtocolVariant.BLEDOM_04 -> byteArrayOf(
                0x7E.toByte(),
                0x04.toByte(),
                0x04.toByte(),
                0xF0.toByte(),
                0x00.toByte(),
                0x01.toByte(),
                0xFF.toByte(),
                0x00.toByte(),
                0xEF.toByte()
            )
        }
    }

    /**
     * Generates the 9-byte Power OFF command for BLEDOB / ELK-BLEDOM.
     */
    fun createPowerOffPacket(variant: ProtocolVariant = ProtocolVariant.BLEDOB_07): ByteArray {
        return when (variant) {
            ProtocolVariant.BLEDOB_07, ProtocolVariant.DUAL_HYBRID -> byteArrayOf(
                0x7E.toByte(),
                0x07.toByte(),
                0x04.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x02.toByte(),
                0x01.toByte(),
                0xEF.toByte()
            )
            ProtocolVariant.BLEDOM_04 -> byteArrayOf(
                0x7E.toByte(),
                0x04.toByte(),
                0x04.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0xFF.toByte(),
                0x00.toByte(),
                0xEF.toByte()
            )
        }
    }

    /**
     * Generates the 9-byte RGB color packet:
     * BLEDOB: 7E 07 05 03 [R] [G] [B] 10 EF
     * BLEDOM: 7E 04 05 03 [R] [G] [B] 00 EF
     */
    fun createColorPacket(
        r: Int,
        g: Int,
        b: Int,
        brightnessScale: Float = 1.0f,
        channelOrder: ChannelOrder = ChannelOrder.RGB,
        variant: ProtocolVariant = ProtocolVariant.BLEDOB_07
    ): ByteArray {
        val scale = brightnessScale.coerceIn(0.0f, 1.0f)
        val scaledR = (r.coerceIn(0, 255) * scale).toInt()
        val scaledG = (g.coerceIn(0, 255) * scale).toInt()
        val scaledB = (b.coerceIn(0, 255) * scale).toInt()

        val mapped = channelOrder.mapChannels(scaledR, scaledG, scaledB)
        val headerModelByte = if (variant == ProtocolVariant.BLEDOM_04) 0x04.toByte() else 0x07.toByte()
        val modifierByte = if (variant == ProtocolVariant.BLEDOM_04) 0x00.toByte() else 0x10.toByte()

        return byteArrayOf(
            0x7E.toByte(),
            headerModelByte,
            0x05.toByte(),
            0x03.toByte(),
            mapped[0].toByte(),
            mapped[1].toByte(),
            mapped[2].toByte(),
            modifierByte,
            0xEF.toByte()
        )
    }

    /**
     * Generates the 9-byte Hardware Brightness packet (0..100%).
     */
    fun createBrightnessPacket(
        percent: Int,
        variant: ProtocolVariant = ProtocolVariant.BLEDOB_07
    ): ByteArray {
        val clamped = percent.coerceIn(0, 100)
        val headerModelByte = if (variant == ProtocolVariant.BLEDOM_04) 0x04.toByte() else 0x07.toByte()
        return byteArrayOf(
            0x7E.toByte(),
            headerModelByte,
            0x01.toByte(),
            clamped.toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
            0x00.toByte(),
            0xEF.toByte()
        )
    }

    /**
     * Formats a packet as an uppercase hex string for diagnostics (e.g., "7E 07 05 03 FF 00 00 10 EF").
     */
    fun toHexString(packet: ByteArray): String =
        packet.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
