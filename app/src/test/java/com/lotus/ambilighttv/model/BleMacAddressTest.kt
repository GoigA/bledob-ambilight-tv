package com.lotus.ambilighttv.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleMacAddressTest {

    @Test
    fun `standard uppercase MAC address is parsed accurately`() {
        val mac = BleMacAddress.parse("BE:27:08:00:49:E2")
        assertEquals("BE:27:08:00:49:E2", mac.address)
        assertEquals("BE:27:08:00:49:E2", mac.formatted)
        assertTrue(mac.isConfigured)
        assertTrue(BleMacAddress.isValid("BE:27:08:00:49:E2"))
    }

    @Test
    fun `lowercase and mixed case MAC addresses are normalized to uppercase`() {
        val mac = BleMacAddress.parse("be:27:08:00:49:e2")
        assertEquals("BE:27:08:00:49:E2", mac.address)
        assertEquals("BE:27:08:00:49:E2", mac.formatted)
        assertTrue(mac.isConfigured)
    }

    @Test
    fun `hyphen separators are normalized to colon format`() {
        val mac = BleMacAddress.parse("BE-27-08-00-49-E2")
        assertEquals("BE:27:08:00:49:E2", mac.address)
        assertTrue(mac.isConfigured)
        assertTrue(BleMacAddress.isValid("BE-27-08-00-49-E2"))
    }

    @Test
    fun `whitespace is trimmed during parse and validation`() {
        val mac = BleMacAddress.parse("   BE:27:08:00:49:E2  \n")
        assertEquals("BE:27:08:00:49:E2", mac.address)
        assertTrue(BleMacAddress.isValid("  BE:27:08:00:49:E2 "))
    }

    @Test
    fun `unconfigured singleton represents clean empty state`() {
        val empty = BleMacAddress.UNCONFIGURED
        assertEquals("", empty.address)
        assertEquals("", empty.formatted)
        assertFalse(empty.isConfigured)
        assertFalse(BleMacAddress.isValid(""))
        assertFalse(BleMacAddress.isValid("   "))
        assertFalse(BleMacAddress.isValid(null))
    }

    @Test
    fun `invalid MAC address formats are rejected`() {
        assertFalse(BleMacAddress.isValid("BE:27:08:00:49")) // 5 octets
        assertFalse(BleMacAddress.isValid("BE:27:08:00:49:E2:01")) // 7 octets
        assertFalse(BleMacAddress.isValid("BE:27:08:00:49:ZZ")) // invalid hex ZZ
        assertFalse(BleMacAddress.isValid("BE:27:8:00:49:E2")) // single digit octet
        assertFalse(BleMacAddress.isValid("BE:27:008:00:49:E2")) // 3 digit octet
        assertFalse(BleMacAddress.isValid("random string"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parse throws IllegalArgumentException on invalid format`() {
        BleMacAddress.parse("invalid_mac")
    }

    @Test
    fun `fromOrNull returns BleMacAddress for valid input and null for invalid`() {
        val valid = BleMacAddress.fromOrNull("be:27:08:00:49:e2")
        assertEquals("BE:27:08:00:49:E2", valid?.address)

        assertNull(BleMacAddress.fromOrNull("invalid"))
        assertNull(BleMacAddress.fromOrNull(""))
        assertNull(BleMacAddress.fromOrNull(null))
    }

    @Test
    fun `fromOrDefault returns fallback default on invalid or empty input`() {
        val fallback = BleMacAddress("AA:BB:CC:DD:EE:FF")
        assertEquals(fallback, BleMacAddress.fromOrDefault("invalid", default = fallback))
        assertEquals(fallback, BleMacAddress.fromOrDefault("", default = fallback))
        assertEquals(fallback, BleMacAddress.fromOrDefault(null, default = fallback))

        val parsed = BleMacAddress.fromOrDefault("BE:27:08:00:49:E2", default = fallback)
        assertEquals("BE:27:08:00:49:E2", parsed.address)
    }

    @Test
    fun `toByteArray and fromBytes roundtrip correctly`() {
        val rawBytes = byteArrayOf(
            0xBE.toByte(), 0x27.toByte(), 0x08.toByte(),
            0x00.toByte(), 0x49.toByte(), 0xE2.toByte()
        )
        val mac = BleMacAddress.fromBytes(rawBytes)
        assertEquals("BE:27:08:00:49:E2", mac.address)

        val outputBytes = mac.toByteArray()
        assertArrayEquals(rawBytes, outputBytes)
    }

    @Test(expected = IllegalStateException::class)
    fun `toByteArray on UNCONFIGURED throws IllegalStateException`() {
        BleMacAddress.UNCONFIGURED.toByteArray()
    }

    @Test
    fun `compareTo allows case-insensitive sorting`() {
        val mac1 = BleMacAddress("00:11:22:33:44:55")
        val mac2 = BleMacAddress("AA:BB:CC:DD:EE:FF")
        assertTrue(mac1 < mac2)
        assertEquals(0, mac1.compareTo(BleMacAddress("00:11:22:33:44:55")))
    }
}
