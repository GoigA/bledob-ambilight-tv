package com.lotus.ambilighttv.model

/**
 * Value class representing a validated Bluetooth Low Energy MAC address.
 *
 * Ensures all MAC addresses conform to the standard 6-octet hexadecimal format:
 * `^([0-9A-Fa-f]{2}:){5}([0-9A-Fa-f]{2})$`
 *
 * Provides safe representation of both configured hardware devices and
 * clean unconfigured/empty states ([UNCONFIGURED]), eliminating arbitrary hardcoded
 * developer MAC addresses from production code.
 */
@JvmInline
value class BleMacAddress(val address: String) : Comparable<BleMacAddress> {

    /**
     * True if this address represents a valid, configured physical hardware device.
     */
    val isConfigured: Boolean
        get() = address.isNotBlank() && isValid(address)

    /**
     * Uppercase normalized representation (e.g. "AA:BB:CC:DD:EE:FF").
     */
    val formatted: String
        get() = address.trim().uppercase()

    /**
     * Converts this MAC address to its 6 raw hardware bytes.
     * @throws IllegalStateException if this address is unconfigured or malformed.
     */
    fun toByteArray(): ByteArray {
        check(isConfigured) { "Cannot convert unconfigured MAC address to byte array" }
        val parts = formatted.split(':')
        check(parts.size == 6) { "Malformed MAC address: $address" }
        return ByteArray(6) { index -> parts[index].toInt(16).toByte() }
    }

    override fun compareTo(other: BleMacAddress): Int =
        address.compareTo(other.address, ignoreCase = true)

    override fun toString(): String = address

    companion object {
        /**
         * Represents an unconfigured or empty Bluetooth MAC address state.
         * Used as default before scanning or pairing a physical light strip.
         */
        val UNCONFIGURED = BleMacAddress("")

        private val MAC_REGEX = Regex("^([0-9A-Fa-f]{2}:){5}([0-9A-Fa-f]{2})$")

        /**
         * Checks whether [raw] is a valid 6-octet MAC address string (supports colons and hyphens).
         */
        fun isValid(raw: String?): Boolean {
            if (raw.isNullOrBlank()) return false
            val normalized = normalizeCandidate(raw)
            return MAC_REGEX.matches(normalized)
        }

        /**
         * Parses and normalizes [raw] into a [BleMacAddress].
         * Supports lowercase and hyphen-separated octets.
         *
         * @throws IllegalArgumentException if [raw] is not a valid MAC address.
         */
        fun parse(raw: String): BleMacAddress {
            val normalized = normalizeCandidate(raw)
            require(MAC_REGEX.matches(normalized)) {
                "Invalid Bluetooth MAC address: '$raw'. Expected 6 octets format: XX:XX:XX:XX:XX:XX."
            }
            return BleMacAddress(normalized)
        }

        /**
         * Safely attempts to parse [raw], returning `null` if invalid, blank, or null.
         */
        fun fromOrNull(raw: String?): BleMacAddress? {
            if (raw.isNullOrBlank()) return null
            val normalized = normalizeCandidate(raw)
            return if (MAC_REGEX.matches(normalized)) BleMacAddress(normalized) else null
        }

        /**
         * Safely parses [raw], returning [default] if invalid or null.
         */
        fun fromOrDefault(raw: String?, default: BleMacAddress = UNCONFIGURED): BleMacAddress =
            fromOrNull(raw) ?: default

        /**
         * Reconstructs a [BleMacAddress] from 6 raw hardware bytes.
         * @throws IllegalArgumentException if [bytes] is not exactly 6 bytes in length.
         */
        fun fromBytes(bytes: ByteArray): BleMacAddress {
            require(bytes.size == 6) { "Bluetooth MAC address must be exactly 6 bytes, got ${bytes.size}" }
            val formatted = bytes.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
            return BleMacAddress(formatted)
        }

        private fun normalizeCandidate(raw: String): String =
            raw.trim().replace('-', ':').uppercase()
    }
}
