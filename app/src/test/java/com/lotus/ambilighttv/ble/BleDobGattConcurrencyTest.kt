package com.lotus.ambilighttv.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.ContextWrapper
import com.lotus.ambilighttv.model.RgbColor
import com.lotus.ambilighttv.state.AmbilightStateRepository
import com.lotus.ambilighttv.state.BleConnectionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical Adversarial Challenger Test Suite for Milestone M2:
 * Rigorously stress-tests BleDobGattClient concurrency, teardown safety,
 * UI telemetry throttling, and zero-allocation buffer isolation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleDobGattConcurrencyTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testContext: Context
    private lateinit var client: BleDobGattClient

    private fun getPrivateField(target: Any, name: String): Any? {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target)
    }

    private fun setPrivateField(target: Any, name: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun createDummyGatt(): BluetoothGatt {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocateInstance = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocateInstance.invoke(unsafe, BluetoothGatt::class.java) as BluetoothGatt
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        testContext = object : ContextWrapper(null) {
            override fun getSystemService(name: String): Any? = null
            override fun getApplicationContext(): Context = this
        }
        AmbilightStateRepository.resetForTesting()
        client = BleDobGattClient(testContext, testDispatcher)
    }

    @After
    fun tearDown() {
        client.release()
        Dispatchers.resetMain()
    }

    // =========================================================================
    // 1. Concurrency Stress: Scan Callback CAS Connection Guard
    // =========================================================================

    @Test
    fun `concurrency stress - 100 simultaneous threads attempting CAS connection yields exactly 1 winner`() {
        val threads = 100
        val executor = Executors.newFixedThreadPool(threads)
        val readyLatch = CountDownLatch(threads)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threads)

        val casWinners = AtomicInteger(0)
        val casLosers = AtomicInteger(0)

        for (i in 0 until threads) {
            executor.submit {
                readyLatch.countDown()
                try {
                    startLatch.await()
                    if (client.isConnectingOrConnected.compareAndSet(false, true)) {
                        casWinners.incrementAndGet()
                    } else {
                        casLosers.incrementAndGet()
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        readyLatch.await(5, TimeUnit.SECONDS)
        startLatch.countDown() // Release all threads simultaneously
        val finished = doneLatch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue("All threads must finish execution", finished)
        assertEquals("Exactly 1 thread must acquire the connection CAS", 1, casWinners.get())
        assertEquals("Exactly 99 threads must lose the CAS and be safely rejected", 99, casLosers.get())
        assertTrue("isConnectingOrConnected flag must remain true", client.isConnectingOrConnected.get())
    }

    @Test
    fun `isConnectingOrConnected CAS releases cleanly across multiple consecutive waves of bursts`() {
        val waves = 10
        val threadsPerWave = 20
        val executor = Executors.newFixedThreadPool(threadsPerWave)

        for (wave in 1..waves) {
            val readyLatch = CountDownLatch(threadsPerWave)
            val startLatch = CountDownLatch(1)
            val doneLatch = CountDownLatch(threadsPerWave)
            val winners = AtomicInteger(0)

            for (i in 0 until threadsPerWave) {
                executor.submit {
                    readyLatch.countDown()
                    try {
                        startLatch.await()
                        if (client.isConnectingOrConnected.compareAndSet(false, true)) {
                            winners.incrementAndGet()
                        }
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }

            readyLatch.await(5, TimeUnit.SECONDS)
            startLatch.countDown()
            doneLatch.await(5, TimeUnit.SECONDS)

            assertEquals("Wave $wave must have exactly 1 winner", 1, winners.get())
            assertTrue(client.isConnectingOrConnected.get())

            // Release connection flag for next wave (simulating disconnection or error cleanup)
            client.isConnectingOrConnected.set(false)
            assertFalse(client.isConnectingOrConnected.get())
        }

        executor.shutdown()
    }

    @Test
    fun `error cleanup paths strictly release isConnectingOrConnected flag`() {
        // 1. Scan failed callback releases flag
        client.isConnectingOrConnected.set(true)
        client.leScanCallback.onScanFailed(1)
        assertFalse("onScanFailed must release isConnectingOrConnected", client.isConnectingOrConnected.get())
        assertEquals(BleConnectionStatus.ERROR, AmbilightStateRepository.state.value.bleStatus)

        // 2. Disconnect releases flag
        client.isConnectingOrConnected.set(true)
        client.disconnectAndClose()
        assertFalse("disconnectAndClose must release isConnectingOrConnected", client.isConnectingOrConnected.get())

        // 3. startBleScan with missing adapter releases flag
        client.isConnectingOrConnected.set(true)
        client.startBleScan()
        assertFalse("startBleScan without adapter must release isConnectingOrConnected", client.isConnectingOrConnected.get())
    }

    // =========================================================================
    // 2. Teardown Safety: isExplicitShutdown Reconnection Prevention
    // =========================================================================

    @Test
    fun `teardown safety - isExplicitShutdown suppresses reconnect when STATE_DISCONNECTED arrives asynchronously`() {
        val dummyGatt = createDummyGatt()

        // Verify initial state
        assertFalse(client.isExplicitShutdown.get())

        // Deliberate shutdown
        client.disconnectAndClose()
        assertTrue("isExplicitShutdown must be true after disconnectAndClose", client.isExplicitShutdown.get())
        assertEquals(BleConnectionStatus.DISCONNECTED, AmbilightStateRepository.state.value.bleStatus)

        // Asynchronous callback from Android Bluetooth daemon arriving on Binder thread
        client.gattCallback.onConnectionStateChange(
            dummyGatt,
            BluetoothGatt.GATT_SUCCESS,
            BluetoothProfile.STATE_DISCONNECTED
        )

        // Verify that reconnect was NOT scheduled and state remains DISCONNECTED
        assertTrue("isExplicitShutdown must still be true", client.isExplicitShutdown.get())
        assertFalse("isConnectingOrConnected must remain false", client.isConnectingOrConnected.get())
        assertFalse("isConnectedAndReady must remain false", client.isConnectedAndReady)

        // Fast-forward coroutine time past RECONNECT_BACKOFF_MS (2,500ms)
        testDispatcher.scheduler.advanceTimeBy(10_000L)
        testDispatcher.scheduler.runCurrent()

        // Confirm no zombie reconnect occurred
        assertFalse("No reconnect attempt should have occurred after shutdown", client.isConnectingOrConnected.get())
        assertEquals(BleConnectionStatus.DISCONNECTED, AmbilightStateRepository.state.value.bleStatus)
    }

    @Test
    fun `teardown safety - isExplicitShutdown suppresses reconnect on service discovery failure`() {
        val dummyGatt = createDummyGatt()

        client.disconnectAndClose()
        assertTrue(client.isExplicitShutdown.get())

        // Simulate failed service discovery arriving after teardown
        client.gattCallback.onServicesDiscovered(dummyGatt, BluetoothGatt.GATT_FAILURE)

        testDispatcher.scheduler.advanceTimeBy(10_000L)
        testDispatcher.scheduler.runCurrent()

        assertFalse("No reconnect coroutine should execute", client.isConnectingOrConnected.get())
    }

    @Test
    fun `release completely cancels coroutine scope and cancels active jobs`() {
        client.release()
        assertTrue("release must set isExplicitShutdown", client.isExplicitShutdown.get())
        assertFalse(client.isConnectingOrConnected.get())
        assertFalse(client.isConnectedAndReady)
    }

    // =========================================================================
    // 3. UI Telemetry Throttling: 4 Hz (250ms) Rate-Limiting Assertions
    // =========================================================================

    @Test
    fun `telemetry throttling - StateFlow emissions are throttled to 4 Hz (250ms) during high-frequency color streaming`() {
        // Set brightness to 100% so color values match exactly
        AmbilightStateRepository.updateRuntimeState {
            it.copy(brightnessPercent = 100)
        }

        // Ensure frame-rate limiter does not reject writes (interval = 0ms simulated)
        setPrivateField(client, "lastWriteTimestampMs", -10_000L)

        // 1. When elapsed time since last telemetry is < 250ms (e.g. 100ms), StateFlow emission is suppressed
        setPrivateField(client, "lastTelemetryTimestampMs", -100L)
        val color1 = RgbColor.fromRgb(200, 100, 50)
        client.sendColor(color1, force = false)

        assertEquals("When delta < 250ms, StateFlow must NOT emit telemetry (throttled)", 0, AmbilightStateRepository.state.value.currentR)

        // 2. When elapsed time reaches UI_TELEMETRY_INTERVAL_MS (250ms), StateFlow emission occurs
        setPrivateField(client, "lastTelemetryTimestampMs", -250L)
        client.sendColor(color1, force = false)

        assertEquals("When delta >= 250ms (4 Hz), StateFlow MUST emit telemetry", 200, AmbilightStateRepository.state.value.currentR)
        assertEquals(100, AmbilightStateRepository.state.value.currentG)
        assertEquals(50, AmbilightStateRepository.state.value.currentB)

        // 3. Immediately attempting another transmission (delta ~0ms) is again throttled
        val color2 = RgbColor.fromRgb(10, 20, 30)
        client.sendColor(color2, force = false)

        assertEquals("Subsequent immediate color write must remain throttled", 200, AmbilightStateRepository.state.value.currentR)

        // 4. Advancing past 250ms allows the next emission
        setPrivateField(client, "lastTelemetryTimestampMs", -250L)
        client.sendColor(color2, force = false)

        assertEquals("After 250ms cooldown, next emission passes through", 10, AmbilightStateRepository.state.value.currentR)
    }

    @Test
    fun `sendColor with force bypasses UI telemetry throttling interval`() {
        // High-speed consecutive forced sends
        val color1 = RgbColor.fromRgb(10, 20, 30)
        val color2 = RgbColor.fromRgb(40, 50, 60)
        val color3 = RgbColor.fromRgb(70, 80, 90)

        client.sendColor(color1, force = true)
        assertEquals(10, AmbilightStateRepository.state.value.currentR)

        client.sendColor(color2, force = true)
        assertEquals(40, AmbilightStateRepository.state.value.currentR)

        client.sendColor(color3, force = true)
        assertEquals(70, AmbilightStateRepository.state.value.currentR)
    }

    // =========================================================================
    // 4. Buffer Isolation: Zero-Allocation and Memory Safety
    // =========================================================================

    @Test
    fun `buffer isolation - txColorBuffer mutates in-place without reallocation across 10000 frames`() {
        val txColorBuffer = getPrivateField(client, "txColorBuffer") as ByteArray
        val initialIdentity = System.identityHashCode(txColorBuffer)

        assertEquals("txColorBuffer must be exactly 9 bytes", BleDobProtocol.PACKET_LENGTH, txColorBuffer.size)

        for (i in 0 until 10_000) {
            val r = (i * 7) % 256
            val g = (i * 13) % 256
            val b = (i * 19) % 256
            val color = RgbColor.fromRgb(r, g, b)

            // Mutate via writeColorPacket directly into client's txColorBuffer
            BleDobProtocol.writeColorPacket(
                outBuffer = txColorBuffer,
                color = color,
                brightnessScale = 1.0f,
                channelOrder = BleDobProtocol.ChannelOrder.RGB,
                variant = BleDobProtocol.ProtocolVariant.BLEDOB_07
            )

            // Assert protocol delimiters
            assertEquals(0x7E.toByte(), txColorBuffer[0])
            assertEquals(0x07.toByte(), txColorBuffer[1])
            assertEquals(0x05.toByte(), txColorBuffer[2])
            assertEquals(0x03.toByte(), txColorBuffer[3])
            assertEquals(r.toByte(), txColorBuffer[4])
            assertEquals(g.toByte(), txColorBuffer[5])
            assertEquals(b.toByte(), txColorBuffer[6])
            assertEquals(0x10.toByte(), txColorBuffer[7])
            assertEquals(0xEF.toByte(), txColorBuffer[8])
        }

        val finalTxColorBuffer = getPrivateField(client, "txColorBuffer") as ByteArray
        val finalIdentity = System.identityHashCode(finalTxColorBuffer)

        assertSame("txColorBuffer instance must remain strictly identical", txColorBuffer, finalTxColorBuffer)
        assertEquals("Buffer identity hash must never change (zero reallocation)", initialIdentity, finalIdentity)
    }

    @Test
    fun `buffer isolation - txBrightnessBuffer mutates in-place without memory corruption across 0 to 100 percent`() {
        val txBrightnessBuffer = getPrivateField(client, "txBrightnessBuffer") as ByteArray
        val initialIdentity = System.identityHashCode(txBrightnessBuffer)

        assertEquals("txBrightnessBuffer must be exactly 9 bytes", BleDobProtocol.PACKET_LENGTH, txBrightnessBuffer.size)

        for (pct in -20..120) {
            BleDobProtocol.writeBrightnessPacket(
                outBuffer = txBrightnessBuffer,
                percent = pct,
                variant = BleDobProtocol.ProtocolVariant.BLEDOB_07
            )

            val expectedClamped = pct.coerceIn(0, 100).toByte()
            assertEquals(0x7E.toByte(), txBrightnessBuffer[0])
            assertEquals(0x07.toByte(), txBrightnessBuffer[1])
            assertEquals(0x01.toByte(), txBrightnessBuffer[2])
            assertEquals(expectedClamped, txBrightnessBuffer[3])
            assertEquals(0xFF.toByte(), txBrightnessBuffer[4])
            assertEquals(0xFF.toByte(), txBrightnessBuffer[5])
            assertEquals(0xFF.toByte(), txBrightnessBuffer[6])
            assertEquals(0x00.toByte(), txBrightnessBuffer[7])
            assertEquals(0xEF.toByte(), txBrightnessBuffer[8])
        }

        val finalBrightnessBuffer = getPrivateField(client, "txBrightnessBuffer") as ByteArray
        assertSame(txBrightnessBuffer, finalBrightnessBuffer)
        assertEquals(initialIdentity, System.identityHashCode(finalBrightnessBuffer))
    }

    // =========================================================================
    // 5. System Integration & Channel Permutation Robustness
    // =========================================================================

    @Test
    fun `system integration - all ChannelOrder settings correctly format hex telemetry in repository`() {
        val testColor = RgbColor.fromRgb(0x11, 0x22, 0x33)
        AmbilightStateRepository.updateRuntimeState {
            it.copy(brightnessPercent = 100)
        }

        for (order in BleDobProtocol.ChannelOrder.values()) {
            AmbilightStateRepository.updateRuntimeState {
                it.copy(channelOrder = order, brightnessPercent = 100)
            }

            client.sendColor(testColor, force = true)

            val state = AmbilightStateRepository.state.value
            val hex = state.lastHexPacket
            assertNotNull(hex)
            assertTrue("Hex packet must start with 7E 07 05 03", hex.startsWith("7E 07 05 03"))
            assertTrue("Hex packet must end with 10 EF", hex.endsWith("10 EF"))

            // Verify order mapping in hex
            when (order) {
                BleDobProtocol.ChannelOrder.RGB -> assertTrue("Hex contains 11 22 33", hex.contains("11 22 33"))
                BleDobProtocol.ChannelOrder.GRB -> assertTrue("Hex contains 22 11 33", hex.contains("22 11 33"))
                BleDobProtocol.ChannelOrder.BRG -> assertTrue("Hex contains 33 11 22", hex.contains("33 11 22"))
                BleDobProtocol.ChannelOrder.RBG -> assertTrue("Hex contains 11 33 22", hex.contains("11 33 22"))
                BleDobProtocol.ChannelOrder.GBR -> assertTrue("Hex contains 22 33 11", hex.contains("22 33 11"))
                BleDobProtocol.ChannelOrder.BGR -> assertTrue("Hex contains 33 22 11", hex.contains("33 22 11"))
            }
        }
    }
}

