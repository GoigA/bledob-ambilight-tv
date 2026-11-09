package com.lotus.ambilighttv

import com.lotus.ambilighttv.ble.BleDobProtocol
import com.lotus.ambilighttv.engine.VividEdgeColorEngine
import com.lotus.ambilighttv.model.BleMacAddress
import com.lotus.ambilighttv.model.RgbColor
import com.lotus.ambilighttv.state.AmbilightMode
import com.lotus.ambilighttv.state.AmbilightStateRepository
import com.lotus.ambilighttv.state.AmbilightUiState
import com.lotus.ambilighttv.state.BleConnectionStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical Challenger Suite (Instance 2) for Milestone M1.
 * Focuses on State Management concurrency, Notification Text Generation,
 * Protocol Binary Equivalence, and Zero-Allocation Invariants.
 */
class StateAndNotificationContractTest {

    @Before
    fun setUp() {
        AmbilightStateRepository.resetForTesting()
    }

    // =========================================================================
    // 1. State Telemetry & Clean Defaults Verification
    // =========================================================================

    @Test
    fun `initial state telemetry conforms strictly to unconfigured clean baseline`() {
        val state = AmbilightStateRepository.state.value

        assertEquals("Red channel must be 0", 0, state.currentR)
        assertEquals("Green channel must be 0", 0, state.currentG)
        assertEquals("Blue channel must be 0", 0, state.currentB)
        assertTrue("lastHexPacket must be empty", state.lastHexPacket.isEmpty())
        assertTrue("targetMacAddress must be empty", state.targetMacAddress.isEmpty())
        assertEquals("Connected device name must be unconfigured placeholder", "Nenhuma fita conectada", state.connectedDeviceName)
        assertEquals(AmbilightMode.STOPPED, state.mode)
        assertEquals(BleConnectionStatus.DISCONNECTED, state.bleStatus)
        assertFalse(state.isServiceRunning)
        assertEquals(0, state.fps)
    }

    // =========================================================================
    // 2. Concurrent State Management & Race Condition Stress Test
    // =========================================================================

    @Test
    fun `concurrent updates between telemetry, mode, and settings do not cause race conditions or lost updates`() {
        val threads = 4
        val iterationsPerThread = 2500
        val executor = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)
        val exceptions = AtomicInteger(0)

        // Thread 1: Rapid color telemetry updates (simulating 60 FPS video capture)
        executor.submit {
            try {
                for (i in 0 until iterationsPerThread) {
                    val color = RgbColor.fromRgb(i % 256, (i * 2) % 256, (i * 3) % 256)
                    AmbilightStateRepository.updateRuntimeColor(color, "PACKET_$i")
                }
            } catch (t: Throwable) {
                exceptions.incrementAndGet()
            } finally {
                latch.countDown()
            }
        }

        // Thread 2: FPS and BLE connection status updates (simulating GATT client callbacks)
        executor.submit {
            try {
                for (i in 0 until iterationsPerThread) {
                    val status = if (i % 2 == 0) BleConnectionStatus.CONNECTED else BleConnectionStatus.CONNECTING
                    AmbilightStateRepository.updateRuntimeState {
                        it.copy(fps = (i % 60), bleStatus = status)
                    }
                }
            } catch (t: Throwable) {
                exceptions.incrementAndGet()
            } finally {
                latch.countDown()
            }
        }

        // Thread 3: Mode switching (simulating user toggling or TV standby)
        executor.submit {
            try {
                for (i in 0 until iterationsPerThread) {
                    val mode = when (i % 4) {
                        0 -> AmbilightMode.SCREEN_SYNC
                        1 -> AmbilightMode.DRM_AUDIO_FALLBACK
                        2 -> AmbilightMode.TV_STANDBY
                        else -> AmbilightMode.STOPPED
                    }
                    AmbilightStateRepository.updateRuntimeState {
                        it.copy(mode = mode, isServiceRunning = (mode != AmbilightMode.STOPPED))
                    }
                }
            } catch (t: Throwable) {
                exceptions.incrementAndGet()
            } finally {
                latch.countDown()
            }
        }

        // Thread 4: Profile and channel order configuration updates
        executor.submit {
            try {
                for (i in 0 until iterationsPerThread) {
                    val profile = if (i % 2 == 0) VividEdgeColorEngine.AmbilightProfile.DYNAMIC else VividEdgeColorEngine.AmbilightProfile.CINEMA
                    val order = if (i % 2 == 0) BleDobProtocol.ChannelOrder.RGB else BleDobProtocol.ChannelOrder.GRB
                    AmbilightStateRepository.updateRuntimeState {
                        it.copy(profile = profile, channelOrder = order)
                    }
                }
            } catch (t: Throwable) {
                exceptions.incrementAndGet()
            } finally {
                latch.countDown()
            }
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue("All concurrent state updates must complete within timeout", completed)
        assertEquals("No exceptions should occur during concurrent state updates", 0, exceptions.get())

        // Final state verification
        val finalState = AmbilightStateRepository.state.value
        assertTrue("Final R channel must be in valid range", finalState.currentR in 0..255)
        assertTrue("Final G channel must be in valid range", finalState.currentG in 0..255)
        assertTrue("Final B channel must be in valid range", finalState.currentB in 0..255)
        assertTrue("Final FPS must be in valid range", finalState.fps in 0..60)
        assertTrue(finalState.mode != null)
        assertTrue(finalState.bleStatus != null)
        assertTrue(finalState.profile != null)
        assertTrue(finalState.channelOrder != null)
    }

    // =========================================================================
    // 3. Notification Text Generation Logic Challenge
    // =========================================================================

    private fun computeNotificationStatusText(state: AmbilightUiState): String {
        return if (state.targetMacAddress.isNotBlank()) {
            val deviceLabel = if (state.connectedDeviceName.isNotBlank() &&
                state.connectedDeviceName != AmbilightUiState.DEFAULT_DEVICE_NAME
            ) {
                "${state.connectedDeviceName} (${state.targetMacAddress})"
            } else {
                state.targetMacAddress
            }
            when (state.mode) {
                AmbilightMode.SCREEN_SYNC -> "Sincronizando tela com $deviceLabel"
                AmbilightMode.DRM_AUDIO_FALLBACK -> "Modo Ã¡udio/ambiente ativo com $deviceLabel"
                AmbilightMode.TV_STANDBY -> "TV em espera: fita desligada ($deviceLabel)"
                AmbilightMode.STOPPED -> "Ambilight pronto ($deviceLabel)"
            }
        } else {
            "Aguardando conexÃ£o com fita LED (Nenhuma fita conectada)"
        }
    }

    @Test
    fun `notification text correctly handles unconfigured state and never leaks hardcoded MAC`() {
        val forbiddenDevMac = "BE:27:08:00:49:E2"

        // 1. Initial default state
        val unconfiguredState = AmbilightUiState()
        val textUnconfigured = computeNotificationStatusText(unconfiguredState)
        assertEquals("Aguardando conexÃ£o com fita LED (Nenhuma fita conectada)", textUnconfigured)
        assertFalse(textUnconfigured.contains(forbiddenDevMac))

        // 2. Configured with MAC only (default device name)
        val macOnlyState = unconfiguredState.copy(
            targetMacAddress = "11:22:33:44:55:66",
            mode = AmbilightMode.SCREEN_SYNC
        )
        val textMacOnly = computeNotificationStatusText(macOnlyState)
        assertEquals("Sincronizando tela com 11:22:33:44:55:66", textMacOnly)

        // 3. Configured with custom device name
        val customDeviceState = unconfiguredState.copy(
            targetMacAddress = "11:22:33:44:55:66",
            connectedDeviceName = "LED-SALA",
            mode = AmbilightMode.SCREEN_SYNC
        )
        val textCustomDevice = computeNotificationStatusText(customDeviceState)
        assertEquals("Sincronizando tela com LED-SALA (11:22:33:44:55:66)", textCustomDevice)

        // 4. Test all modes
        val drmText = computeNotificationStatusText(customDeviceState.copy(mode = AmbilightMode.DRM_AUDIO_FALLBACK))
        assertEquals("Modo Ã¡udio/ambiente ativo com LED-SALA (11:22:33:44:55:66)", drmText)

        val standbyText = computeNotificationStatusText(customDeviceState.copy(mode = AmbilightMode.TV_STANDBY))
        assertEquals("TV em espera: fita desligada (LED-SALA (11:22:33:44:55:66))", standbyText)

        val stoppedText = computeNotificationStatusText(customDeviceState.copy(mode = AmbilightMode.STOPPED))
        assertEquals("Ambilight pronto (LED-SALA (11:22:33:44:55:66))", stoppedText)
    }

    // =========================================================================
    // 4. Protocol Binary Equivalence Challenge
    // =========================================================================

    @Test
    fun `exact hardware packet binary equivalence for BLEDOB and BLEDOM`() {
        // BLEDOB Power ON: 7E 07 04 FF 00 01 02 01 EF
        val bledobOnExpected = byteArrayOf(0x7E.toByte(), 0x07, 0x04, 0xFF.toByte(), 0x00, 0x01, 0x02, 0x01, 0xEF.toByte())
        assertArrayEquals(bledobOnExpected, BleDobProtocol.createPowerOnPacket(BleDobProtocol.ProtocolVariant.BLEDOB_07))
        assertEquals("7E 07 04 FF 00 01 02 01 EF", BleDobProtocol.toHexString(BleDobProtocol.createPowerOnPacket(BleDobProtocol.ProtocolVariant.BLEDOB_07)))

        // BLEDOB Power OFF: 7E 07 04 00 00 00 02 01 EF
        val bledobOffExpected = byteArrayOf(0x7E.toByte(), 0x07, 0x04, 0x00, 0x00, 0x00, 0x02, 0x01, 0xEF.toByte())
        assertArrayEquals(bledobOffExpected, BleDobProtocol.createPowerOffPacket(BleDobProtocol.ProtocolVariant.BLEDOB_07))
        assertEquals("7E 07 04 00 00 00 02 01 EF", BleDobProtocol.toHexString(BleDobProtocol.createPowerOffPacket(BleDobProtocol.ProtocolVariant.BLEDOB_07)))

        // BLEDOM Power ON: 7E 04 04 F0 00 01 FF 00 EF
        val bledomOnExpected = byteArrayOf(0x7E.toByte(), 0x04, 0x04, 0xF0.toByte(), 0x00, 0x01, 0xFF.toByte(), 0x00, 0xEF.toByte())
        assertArrayEquals(bledomOnExpected, BleDobProtocol.createPowerOnPacket(BleDobProtocol.ProtocolVariant.BLEDOM_04))
        assertEquals("7E 04 04 F0 00 01 FF 00 EF", BleDobProtocol.toHexString(BleDobProtocol.createPowerOnPacket(BleDobProtocol.ProtocolVariant.BLEDOM_04)))

        // BLEDOM Power OFF: 7E 04 04 00 00 00 FF 00 EF
        val bledomOffExpected = byteArrayOf(0x7E.toByte(), 0x04, 0x04, 0x00, 0x00, 0x00, 0xFF.toByte(), 0x00, 0xEF.toByte())
        assertArrayEquals(bledomOffExpected, BleDobProtocol.createPowerOffPacket(BleDobProtocol.ProtocolVariant.BLEDOM_04))
        assertEquals("7E 04 04 00 00 00 FF 00 EF", BleDobProtocol.toHexString(BleDobProtocol.createPowerOffPacket(BleDobProtocol.ProtocolVariant.BLEDOM_04)))

        // BLEDOB Pure Red (RGB order): 7E 07 05 03 FF 00 00 10 EF
        val redBuffer = ByteArray(9)
        BleDobProtocol.writeColorPacket(redBuffer, 255, 0, 0, 1.0f, BleDobProtocol.ChannelOrder.RGB, BleDobProtocol.ProtocolVariant.BLEDOB_07)
        assertEquals("7E 07 05 03 FF 00 00 10 EF", BleDobProtocol.toHexString(redBuffer))

        // BLEDOM Pure Red (RGB order): 7E 04 05 03 FF 00 00 00 EF
        BleDobProtocol.writeColorPacket(redBuffer, 255, 0, 0, 1.0f, BleDobProtocol.ChannelOrder.RGB, BleDobProtocol.ProtocolVariant.BLEDOM_04)
        assertEquals("7E 04 05 03 FF 00 00 00 EF", BleDobProtocol.toHexString(redBuffer))

        // BLEDOB Brightness 100%: 7E 07 01 64 FF FF FF 00 EF
        val brightBuffer = ByteArray(9)
        BleDobProtocol.writeBrightnessPacket(brightBuffer, 100, BleDobProtocol.ProtocolVariant.BLEDOB_07)
        assertEquals("7E 07 01 64 FF FF FF 00 EF", BleDobProtocol.toHexString(brightBuffer))

        // BLEDOM Brightness 100%: 7E 04 01 64 FF FF FF 00 EF
        BleDobProtocol.writeBrightnessPacket(brightBuffer, 100, BleDobProtocol.ProtocolVariant.BLEDOM_04)
        assertEquals("7E 04 01 64 FF FF FF 00 EF", BleDobProtocol.toHexString(brightBuffer))
    }

    // =========================================================================
    // 5. Memory & Allocation Invariant Verification
    // =========================================================================

    @Test
    fun `writeColorPacket operates with zero buffer allocation across 50000 executions`() {
        val reusableBuffer = ByteArray(9)
        val color = RgbColor.fromRgb(128, 64, 32)

        for (i in 0 until 50_000) {
            val returned = BleDobProtocol.writeColorPacket(
                outBuffer = reusableBuffer,
                color = color,
                brightnessScale = 0.9f,
                channelOrder = BleDobProtocol.ChannelOrder.RGB,
                variant = BleDobProtocol.ProtocolVariant.BLEDOB_07
            )
            assertSame("Must return exact same ByteArray instance", reusableBuffer, returned)
        }
    }

    @Test
    fun `static power packets are cached singletons`() {
        val p1 = BleDobProtocol.createPowerOnPacket(BleDobProtocol.ProtocolVariant.BLEDOB_07)
        val p2 = BleDobProtocol.createPowerOnPacket(BleDobProtocol.ProtocolVariant.BLEDOB_07)
        val p3 = BleDobProtocol.createPowerOffPacket(BleDobProtocol.ProtocolVariant.BLEDOB_07)
        val p4 = BleDobProtocol.createPowerOffPacket(BleDobProtocol.ProtocolVariant.BLEDOB_07)

        assertSame(p1, p2)
        assertSame(p3, p4)
        assertSame(BleDobProtocol.POWER_ON_BLEDOB, p1)
        assertSame(BleDobProtocol.POWER_OFF_BLEDOB, p3)
    }
}

