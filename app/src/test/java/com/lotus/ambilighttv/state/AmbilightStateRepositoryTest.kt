package com.lotus.ambilighttv.state

import com.lotus.ambilighttv.ble.BleDobProtocol
import com.lotus.ambilighttv.engine.VividEdgeColorEngine
import com.lotus.ambilighttv.model.BleMacAddress
import com.lotus.ambilighttv.model.RgbColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit test suite for [AmbilightStateRepository] and [AmbilightUiState].
 *
 * Verifies clean unconfigured defaults, elimination of arbitrary mock telemetry,
 * [RgbColor] and [BleMacAddress] domain model integration, and StateFlow mutation flows.
 */
class AmbilightStateRepositoryTest {

    @Before
    fun setUp() {
        AmbilightStateRepository.resetForTesting()
    }

    @Test
    fun `initial state has clean unconfigured defaults without mock telemetry`() {
        val state = AmbilightStateRepository.state.value

        assertFalse("Service should not be running initially", state.isServiceRunning)
        assertEquals(AmbilightMode.STOPPED, state.mode)
        assertEquals(BleConnectionStatus.DISCONNECTED, state.bleStatus)
        assertEquals("Target MAC must be empty on clean install", "", state.targetMacAddress)
        assertEquals("Connected device name must be unconfigured placeholder", "Nenhuma fita conectada", state.connectedDeviceName)
        assertEquals("Red channel must default to 0 (off)", 0, state.currentR)
        assertEquals("Green channel must default to 0 (off)", 0, state.currentG)
        assertEquals("Blue channel must default to 0 (off)", 0, state.currentB)
        assertEquals("Hex diagnostic packet must default to empty string", "", state.lastHexPacket)
        assertEquals("Initial FPS must be 0", 0, state.fps)
        assertEquals(90, state.brightnessPercent)
        assertTrue(state.standbySyncEnabled)
        assertTrue(state.drmFallbackEnabled)
    }

    @Test
    fun `initial state currentColor evaluates to RgbColor OFF and targetMac evaluates to BleMacAddress UNCONFIGURED`() {
        val state = AmbilightStateRepository.state.value

        assertEquals(RgbColor.OFF, state.currentColor)
        assertEquals(0, state.currentColor.r)
        assertEquals(0, state.currentColor.g)
        assertEquals(0, state.currentColor.b)
        assertEquals(BleMacAddress.UNCONFIGURED, state.targetMac)
        assertFalse(state.targetMac.isConfigured)
    }

    @Test
    fun `updateRuntimeState modifies state and emits on StateFlow`() {
        AmbilightStateRepository.updateRuntimeState {
            it.copy(
                isServiceRunning = true,
                mode = AmbilightMode.SCREEN_SYNC,
                bleStatus = BleConnectionStatus.CONNECTED,
                fps = 30
            )
        }

        val updated = AmbilightStateRepository.state.value
        assertTrue(updated.isServiceRunning)
        assertEquals(AmbilightMode.SCREEN_SYNC, updated.mode)
        assertEquals(BleConnectionStatus.CONNECTED, updated.bleStatus)
        assertEquals(30, updated.fps)
    }

    @Test
    fun `withColor helper updates RGB channels and currentColor without modifying other state`() {
        val initial = AmbilightStateRepository.state.value
        val updated = initial.withColor(RgbColor.WARM_AMBER, "7E 07 05 03 FF 8C 28 10 EF")

        assertEquals(255, updated.currentR)
        assertEquals(140, updated.currentG)
        assertEquals(40, updated.currentB)
        assertEquals("7E 07 05 03 FF 8C 28 10 EF", updated.lastHexPacket)
        assertEquals(RgbColor.WARM_AMBER, updated.currentColor)
        // Other fields untouched
        assertEquals(initial.isServiceRunning, updated.isServiceRunning)
        assertEquals(initial.targetMacAddress, updated.targetMacAddress)
    }

    @Test
    fun `withMacAddress helper updates targetMacAddress and targetMac without modifying other state`() {
        val initial = AmbilightStateRepository.state.value
        val mac = BleMacAddress.parse("BE:27:08:00:49:E2")
        val updated = initial.withMacAddress(mac, "ELK-BLEDOM")

        assertEquals("BE:27:08:00:49:E2", updated.targetMacAddress)
        assertEquals("ELK-BLEDOM", updated.connectedDeviceName)
        assertEquals(mac, updated.targetMac)
        assertTrue(updated.targetMac.isConfigured)
        // Other fields untouched
        assertEquals(initial.currentR, updated.currentR)
        assertEquals(initial.currentColor, updated.currentColor)
    }

    @Test
    fun `updateRuntimeColor method correctly publishes to StateFlow`() {
        AmbilightStateRepository.updateRuntimeColor(RgbColor.GAMING_CYAN, "PACKET_CYAN_001")

        val state = AmbilightStateRepository.state.value
        assertEquals(RgbColor.GAMING_CYAN, state.currentColor)
        assertEquals(0, state.currentR)
        assertEquals(229, state.currentG)
        assertEquals(255, state.currentB)
        assertEquals("PACKET_CYAN_001", state.lastHexPacket)
    }

    @Test
    fun `backward compatibility getters currentR currentG currentB targetMacAddress match domain representations`() {
        val custom = AmbilightUiState(
            currentR = 120,
            currentG = 200,
            currentB = 50,
            targetMacAddress = "AA:BB:CC:DD:EE:FF"
        )

        assertEquals(120, custom.currentColor.r)
        assertEquals(200, custom.currentColor.g)
        assertEquals(50, custom.currentColor.b)
        assertEquals("AA:BB:CC:DD:EE:FF", custom.targetMac.formatted)
        assertTrue(custom.targetMac.isConfigured)
    }
}
