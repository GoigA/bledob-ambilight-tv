package com.lotus.ambilighttv.ble

import com.lotus.ambilighttv.model.RgbColor
import java.util.UUID

/**
 * Pure protocol encoder for LotusLantern / ELK-BLEDOB / BLEDOB controllers.
 *
 * Implements the 9-byte binary framing protocol used by Shenzhen Hi-Link / LotusLantern BLE controllers.
 * Provides zero-allocation in-place buffer mutation, static power packet caching, and full channel
 * reordering permutations.
 */
object BleDobProtocol {

    // =========================================================================
    // Protocol Frame Delimiters & Sizing
    // =========================================================================

    /** Delimiter starting every 9-byte protocol frame. */
    const val FRAME_HEADER: Byte = 0x7E.toByte()

    /** Delimiter terminating every 9-byte protocol frame. */
    const val FRAME_TRAILER: Byte = 0xEF.toByte()

    /** Total byte length of a standard protocol frame. */
    const val PACKET_LENGTH: Int = 9

    // =========================================================================
    // Controller Model Identifiers (Byte Index 1)
    // =========================================================================

    /** Controller model code for standard BLEDOB / LotusLantern strips (0x07). */
    const val MODEL_BLEDOB_07: Byte = 0x07.toByte()

    /** Controller model code for ELK-BLEDOM strips (0x04). */
    const val MODEL_BLEDOM_04: Byte = 0x04.toByte()

    // =========================================================================
    // Command Types (Byte Index 2)
    // =========================================================================

    /** Hardware brightness control command code (0x01). */
    const val CMD_BRIGHTNESS: Byte = 0x01.toByte()

    /** Power state control command code (0x04). */
    const val CMD_POWER: Byte = 0x04.toByte()

    /** Static RGB color control command code (0x05). */
    const val CMD_COLOR: Byte = 0x05.toByte()

    // =========================================================================
    // Payload Constants: Color Framing
    // =========================================================================

    /** Sub-command code indicating static 3-channel RGB payload follows at bytes 4..6 (0x03). */
    const val SUB_CMD_COLOR: Byte = 0x03.toByte()

    /** Tail modifier byte for BLEDOB color frames (Byte Index 7 = 0x10). */
    const val COLOR_MODIFIER_BLEDOB: Byte = 0x10.toByte()

    /** Tail modifier byte for BLEDOM color frames (Byte Index 7 = 0x00). */
    const val COLOR_MODIFIER_BLEDOM: Byte = 0x00.toByte()

    // =========================================================================
    // Payload Constants: Power Framing
    // =========================================================================

    /** Power ON state byte for BLEDOB controllers (Byte Index 3 = 0xFF). */
    const val POWER_STATE_ON_BLEDOB: Byte = 0xFF.toByte()

    /** Power ON state byte for BLEDOM controllers (Byte Index 3 = 0xF0). */
    const val POWER_STATE_ON_BLEDOM: Byte = 0xF0.toByte()

    /** Power OFF state byte for all controller variants (Byte Index 3 = 0x00). */
    const val POWER_STATE_OFF: Byte = 0x00.toByte()

    /** Reserved payload byte in power frames (Byte Index 4 = 0x00). */
    const val POWER_RESERVED_ZERO: Byte = 0x00.toByte()

    /** Mode flag for active power state (Byte Index 5 = 0x01). */
    const val POWER_FLAG_ACTIVE: Byte = 0x01.toByte()

    /** Mode flag for standby power state (Byte Index 5 = 0x00). */
    const val POWER_FLAG_STANDBY: Byte = 0x00.toByte()

    /** Parameter byte for BLEDOB power frames (Byte Index 6 = 0x02). */
    const val POWER_PARAM_BLEDOB: Byte = 0x02.toByte()

    /** Parameter byte for BLEDOM power frames (Byte Index 6 = 0xFF). */
    const val POWER_PARAM_BLEDOM: Byte = 0xFF.toByte()

    /** Trailer modifier byte for BLEDOB power frames (Byte Index 7 = 0x01). */
    const val POWER_TRAILER_MODIFIER_BLEDOB: Byte = 0x01.toByte()

    /** Trailer modifier byte for BLEDOM power frames (Byte Index 7 = 0x00). */
    const val POWER_TRAILER_MODIFIER_BLEDOM: Byte = 0x00.toByte()

    // =========================================================================
    // Payload Constants: Brightness Framing
    // =========================================================================

    /** Padding byte used in brightness frames at byte indices 4, 5, 6 (0xFF). */
    const val BRIGHTNESS_PADDING: Byte = 0xFF.toByte()

    /** Modifier byte in brightness frames at byte index 7 (0x00). */
    const val BRIGHTNESS_MODIFIER: Byte = 0x00.toByte()

    /** Minimum allowable color channel value. */
    const val CHANNEL_MIN: Int = 0

    /** Maximum allowable color channel value. */
    const val CHANNEL_MAX: Int = 255

    /** Minimum hardware brightness percentage. */
    const val BRIGHTNESS_MIN_PERCENT: Int = 0

    /** Maximum hardware brightness percentage. */
    const val BRIGHTNESS_MAX_PERCENT: Int = 100

    // =========================================================================
    // Device Defaults & Bluetooth Identifiers
    // =========================================================================

    /** Empty unconfigured MAC address placeholder awaiting device pairing or scan. */
    const val UNCONFIGURED_MAC_ADDRESS: String = ""

    /**
     * Default MAC address fallback.
     * Note: Previously hardcoded to developer's physical hardware ("AA:BB:CC:DD:EE:FF");
     * now cleanly defaults to unconfigured empty string [UNCONFIGURED_MAC_ADDRESS] for production.
     */
    const val DEFAULT_MAC_ADDRESS: String = UNCONFIGURED_MAC_ADDRESS

    /** Default advertised BLE peripheral name. */
    const val DEFAULT_DEVICE_NAME: String = "BLEDOB"

    // =========================================================================
    // Bluetooth GATT UUIDs
    // =========================================================================

    val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val FALLBACK_SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")

    val WRITE_CHAR_UUID: UUID = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb")
    val FALLBACK_WRITE_CHAR_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    val NOTIFY_CHAR_UUID: UUID = UUID.fromString("0000fff4-0000-1000-8000-00805f9b34fb")

    // =========================================================================
    // Static Cached Power Packets (Zero Heap Allocations on Power Commands)
    // =========================================================================

    /** Pre-compiled static 9-byte packet for BLEDOB Power ON. */
    val POWER_ON_BLEDOB: ByteArray = byteArrayOf(
        FRAME_HEADER,
        MODEL_BLEDOB_07,
        CMD_POWER,
        POWER_STATE_ON_BLEDOB,
        POWER_RESERVED_ZERO,
        POWER_FLAG_ACTIVE,
        POWER_PARAM_BLEDOB,
        POWER_TRAILER_MODIFIER_BLEDOB,
        FRAME_TRAILER
    )

    /** Pre-compiled static 9-byte packet for BLEDOM Power ON. */
    val POWER_ON_BLEDOM: ByteArray = byteArrayOf(
        FRAME_HEADER,
        MODEL_BLEDOM_04,
        CMD_POWER,
        POWER_STATE_ON_BLEDOM,
        POWER_RESERVED_ZERO,
        POWER_FLAG_ACTIVE,
        POWER_PARAM_BLEDOM,
        POWER_TRAILER_MODIFIER_BLEDOM,
        FRAME_TRAILER
    )

    /** Pre-compiled static 9-byte packet for BLEDOB Power OFF. */
    val POWER_OFF_BLEDOB: ByteArray = byteArrayOf(
        FRAME_HEADER,
        MODEL_BLEDOB_07,
        CMD_POWER,
        POWER_STATE_OFF,
        POWER_RESERVED_ZERO,
        POWER_FLAG_STANDBY,
        POWER_PARAM_BLEDOB,
        POWER_TRAILER_MODIFIER_BLEDOB,
        FRAME_TRAILER
    )

    /** Pre-compiled static 9-byte packet for BLEDOM Power OFF. */
    val POWER_OFF_BLEDOM: ByteArray = byteArrayOf(
        FRAME_HEADER,
        MODEL_BLEDOM_04,
        CMD_POWER,
        POWER_STATE_OFF,
        POWER_RESERVED_ZERO,
        POWER_FLAG_STANDBY,
        POWER_PARAM_BLEDOM,
        POWER_TRAILER_MODIFIER_BLEDOM,
        FRAME_TRAILER
    )

    // =========================================================================
    // Protocol Enumerations
    // =========================================================================

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

        /**
         * Legacy channel mapping helper returning a new 3-element IntArray.
         * Retained for backward compatibility. Prefer [writeChannels] for zero-allocation code paths.
         */
        fun mapChannels(r: Int, g: Int, b: Int): IntArray {
            val rc = r.coerceIn(CHANNEL_MIN, CHANNEL_MAX)
            val gc = g.coerceIn(CHANNEL_MIN, CHANNEL_MAX)
            val bc = b.coerceIn(CHANNEL_MIN, CHANNEL_MAX)
            return when (this) {
                RGB -> intArrayOf(rc, gc, bc)
                GRB -> intArrayOf(gc, rc, bc)
                BRG -> intArrayOf(bc, rc, gc)
                RBG -> intArrayOf(rc, bc, gc)
                GBR -> intArrayOf(gc, bc, rc)
                BGR -> intArrayOf(bc, gc, rc)
            }
        }

        /**
         * Zero-allocation in-place channel ordering. Writes reordered RGB bytes directly into [out] at [offset].
         */
        fun writeChannels(r: Byte, g: Byte, b: Byte, out: ByteArray, offset: Int = 4) {
            when (this) {
                RGB -> { out[offset] = r; out[offset + 1] = g; out[offset + 2] = b }
                GRB -> { out[offset] = g; out[offset + 1] = r; out[offset + 2] = b }
                BRG -> { out[offset] = b; out[offset + 1] = r; out[offset + 2] = g }
                RBG -> { out[offset] = r; out[offset + 1] = b; out[offset + 2] = g }
                GBR -> { out[offset] = g; out[offset + 1] = b; out[offset + 2] = r }
                BGR -> { out[offset] = b; out[offset + 1] = g; out[offset + 2] = r }
            }
        }
    }

    // =========================================================================
    // Power Packet Generation
    // =========================================================================

    /**
     * Returns the cached static 9-byte Power ON command for the requested [variant].
     * Generates zero heap allocations.
     */
    fun createPowerOnPacket(variant: ProtocolVariant = ProtocolVariant.BLEDOB_07): ByteArray {
        return when (variant) {
            ProtocolVariant.BLEDOB_07, ProtocolVariant.DUAL_HYBRID -> POWER_ON_BLEDOB
            ProtocolVariant.BLEDOM_04 -> POWER_ON_BLEDOM
        }
    }

    /**
     * Returns the cached static 9-byte Power OFF command for the requested [variant].
     * Generates zero heap allocations.
     */
    fun createPowerOffPacket(variant: ProtocolVariant = ProtocolVariant.BLEDOB_07): ByteArray {
        return when (variant) {
            ProtocolVariant.BLEDOB_07, ProtocolVariant.DUAL_HYBRID -> POWER_OFF_BLEDOB
            ProtocolVariant.BLEDOM_04 -> POWER_OFF_BLEDOM
        }
    }

    // =========================================================================
    // Zero-Allocation Color Packet Framing
    // =========================================================================

    /**
     * Zero-allocation color packet writer accepting domain model [color].
     * Encodes color into [outBuffer] directly without allocating heap objects.
     */
    fun writeColorPacket(
        outBuffer: ByteArray,
        color: RgbColor,
        brightnessScale: Float = 1.0f,
        channelOrder: ChannelOrder = ChannelOrder.RGB,
        variant: ProtocolVariant = ProtocolVariant.BLEDOB_07
    ): ByteArray = writeColorPacket(
        outBuffer = outBuffer,
        r = color.r,
        g = color.g,
        b = color.b,
        brightnessScale = brightnessScale,
        channelOrder = channelOrder,
        variant = variant
    )

    /**
     * Zero-allocation color packet writer accepting individual channel values.
     * Directly mutates [outBuffer] and returns it.
     *
     * @param outBuffer Destination byte array (must have size >= [PACKET_LENGTH]).
     * @param r Red channel (0..255).
     * @param g Green channel (0..255).
     * @param b Blue channel (0..255).
     * @param brightnessScale Brightness scalar in range 0.0..1.0.
     * @param channelOrder Channel sequence order (RGB, GRB, etc.).
     * @param variant Target controller hardware protocol.
     * @return [outBuffer] containing the framed 9-byte packet.
     */
    fun writeColorPacket(
        outBuffer: ByteArray,
        r: Int,
        g: Int,
        b: Int,
        brightnessScale: Float = 1.0f,
        channelOrder: ChannelOrder = ChannelOrder.RGB,
        variant: ProtocolVariant = ProtocolVariant.BLEDOB_07
    ): ByteArray {
        require(outBuffer.size >= PACKET_LENGTH) {
            "Output buffer must have at least $PACKET_LENGTH bytes, got ${outBuffer.size}"
        }

        val scale = brightnessScale.coerceIn(0.0f, 1.0f)
        val scaledR = (r.coerceIn(CHANNEL_MIN, CHANNEL_MAX) * scale).toInt().toByte()
        val scaledG = (g.coerceIn(CHANNEL_MIN, CHANNEL_MAX) * scale).toInt().toByte()
        val scaledB = (b.coerceIn(CHANNEL_MIN, CHANNEL_MAX) * scale).toInt().toByte()

        val headerModelByte = when (variant) {
            ProtocolVariant.BLEDOM_04 -> MODEL_BLEDOM_04
            ProtocolVariant.BLEDOB_07, ProtocolVariant.DUAL_HYBRID -> MODEL_BLEDOB_07
        }
        val modifierByte = when (variant) {
            ProtocolVariant.BLEDOM_04 -> COLOR_MODIFIER_BLEDOM
            ProtocolVariant.BLEDOB_07, ProtocolVariant.DUAL_HYBRID -> COLOR_MODIFIER_BLEDOB
        }

        outBuffer[0] = FRAME_HEADER
        outBuffer[1] = headerModelByte
        outBuffer[2] = CMD_COLOR
        outBuffer[3] = SUB_CMD_COLOR

        channelOrder.writeChannels(scaledR, scaledG, scaledB, outBuffer, offset = 4)

        outBuffer[7] = modifierByte
        outBuffer[8] = FRAME_TRAILER

        return outBuffer
    }

    /**
     * Backward-compatible color packet generator. Allocates a new 9-byte array on each call.
     * Retained for legacy callers and baseline unit tests.
     */
    fun createColorPacket(
        r: Int,
        g: Int,
        b: Int,
        brightnessScale: Float = 1.0f,
        channelOrder: ChannelOrder = ChannelOrder.RGB,
        variant: ProtocolVariant = ProtocolVariant.BLEDOB_07
    ): ByteArray {
        val buffer = ByteArray(PACKET_LENGTH)
        return writeColorPacket(
            outBuffer = buffer,
            r = r,
            g = g,
            b = b,
            brightnessScale = brightnessScale,
            channelOrder = channelOrder,
            variant = variant
        )
    }

    /**
     * Convenience color packet generator accepting domain model [color].
     */
    fun createColorPacket(
        color: RgbColor,
        brightnessScale: Float = 1.0f,
        channelOrder: ChannelOrder = ChannelOrder.RGB,
        variant: ProtocolVariant = ProtocolVariant.BLEDOB_07
    ): ByteArray = createColorPacket(
        r = color.r,
        g = color.g,
        b = color.b,
        brightnessScale = brightnessScale,
        channelOrder = channelOrder,
        variant = variant
    )

    // =========================================================================
    // Zero-Allocation Hardware Brightness Framing
    // =========================================================================

    /**
     * Writes a 9-byte hardware brightness packet (0..100%) directly into [outBuffer].
     */
    fun writeBrightnessPacket(
        outBuffer: ByteArray,
        percent: Int,
        variant: ProtocolVariant = ProtocolVariant.BLEDOB_07
    ): ByteArray {
        require(outBuffer.size >= PACKET_LENGTH) {
            "Output buffer must have at least $PACKET_LENGTH bytes, got ${outBuffer.size}"
        }

        val clamped = percent.coerceIn(BRIGHTNESS_MIN_PERCENT, BRIGHTNESS_MAX_PERCENT).toByte()
        val headerModelByte = when (variant) {
            ProtocolVariant.BLEDOM_04 -> MODEL_BLEDOM_04
            ProtocolVariant.BLEDOB_07, ProtocolVariant.DUAL_HYBRID -> MODEL_BLEDOB_07
        }

        outBuffer[0] = FRAME_HEADER
        outBuffer[1] = headerModelByte
        outBuffer[2] = CMD_BRIGHTNESS
        outBuffer[3] = clamped
        outBuffer[4] = BRIGHTNESS_PADDING
        outBuffer[5] = BRIGHTNESS_PADDING
        outBuffer[6] = BRIGHTNESS_PADDING
        outBuffer[7] = BRIGHTNESS_MODIFIER
        outBuffer[8] = FRAME_TRAILER

        return outBuffer
    }

    /**
     * Generates the 9-byte Hardware Brightness packet (0..100%).
     */
    fun createBrightnessPacket(
        percent: Int,
        variant: ProtocolVariant = ProtocolVariant.BLEDOB_07
    ): ByteArray {
        val buffer = ByteArray(PACKET_LENGTH)
        return writeBrightnessPacket(buffer, percent, variant)
    }

    // =========================================================================
    // High-Performance Hex Formatting
    // =========================================================================

    private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

    /**
     * Formats a packet as an uppercase hex string for diagnostics (e.g., "7E 07 05 03 FF 00 00 10 EF").
     * Avoids intermediate String allocation per byte.
     */
    fun toHexString(packet: ByteArray): String {
        if (packet.isEmpty()) return ""
        val result = java.lang.StringBuilder(packet.size * 3 - 1)
        for (i in packet.indices) {
            val b = packet[i].toInt() and 0xFF
            result.append(HEX_CHARS[b ushr 4])
            result.append(HEX_CHARS[b and 0x0F])
            if (i < packet.size - 1) {
                result.append(' ')
            }
        }
        return result.toString()
    }
}
