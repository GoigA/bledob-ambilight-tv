package com.lotus.ambilighttv.ble

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BleDobGattClientTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testContext: Context
    private lateinit var client: BleDobGattClient

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

    @Test
    fun `timing constants verify self-documenting human-readable values`() {
        assertEquals(5_000L, BleDobGattClient.CONNECT_TIMEOUT_MS)
        assertEquals(10_000L, BleDobGattClient.BLE_SCAN_DURATION_MS)
        assertEquals(2_500L, BleDobGattClient.RECONNECT_BACKOFF_MS)
        assertEquals(250L, BleDobGattClient.UI_TELEMETRY_INTERVAL_MS)
        assertEquals(45L, BleDobGattClient.DUAL_COMMAND_POWER_ON_DELAY_MS)
        assertEquals(35L, BleDobGattClient.DUAL_COMMAND_POWER_OFF_DELAY_MS)
        assertEquals(50L, BleDobGattClient.POWER_OFF_BURST_DELAY_MS)
        assertEquals(2, BleDobGattClient.POWER_OFF_REPEAT_COUNT)
        assertEquals(10, BleDobGattClient.MIN_TARGET_FPS)
        assertEquals(30, BleDobGattClient.MAX_TARGET_FPS)
    }

    @Test
    fun `disconnectAndClose sets isExplicitShutdown and prevents reconnect`() {
        assertFalse("Initial isExplicitShutdown must be false", client.isExplicitShutdown.get())

        client.disconnectAndClose()

        assertTrue("After disconnectAndClose, isExplicitShutdown must be true", client.isExplicitShutdown.get())
        assertFalse("After disconnectAndClose, isConnectingOrConnected must be false", client.isConnectingOrConnected.get())
        assertFalse("After disconnectAndClose, isConnectedAndReady must be false", client.isConnectedAndReady)
        assertEquals(BleConnectionStatus.DISCONNECTED, AmbilightStateRepository.state.value.bleStatus)
    }

    @Test
    fun `connect clears isExplicitShutdown guard`() {
        client.disconnectAndClose()
        assertTrue(client.isExplicitShutdown.get())

        // Calling connect resets the shutdown guard even if adapter is null in tests
        client.connect("BE:27:08:00:49:E2")
        assertFalse("connect() must clear isExplicitShutdown flag", client.isExplicitShutdown.get())
    }

    @Test
    fun `isConnectingOrConnected CAS prevents concurrent connection storms`() {
        // First connection attempt wins CAS
        assertTrue("First CAS attempt should succeed", client.isConnectingOrConnected.compareAndSet(false, true))

        // Second concurrent connection attempt loses CAS
        assertFalse("Concurrent CAS attempt must fail", client.isConnectingOrConnected.compareAndSet(false, true))

        // On failure or disconnection, flag is released
        client.isConnectingOrConnected.set(false)
        assertTrue("CAS can be acquired again after release", client.isConnectingOrConnected.compareAndSet(false, true))
    }

    @Test
    fun `initial lastSentColor is null and has no magic -1 sentinels`() {
        assertNull("lastSentColor must be null initially", client.lastSentColor)
    }

    @Test
    fun `sendColor with force updates state repository and formats hex telemetry`() {
        val testColor = RgbColor.fromRgb(255, 128, 64)
        client.sendColor(testColor, force = true)

        val state = AmbilightStateRepository.state.value
        assertEquals(255, state.currentR)
        assertEquals(128, state.currentG)
        assertEquals(64, state.currentB)
        assertNotNull(state.lastHexPacket)
        assertTrue(state.lastHexPacket.startsWith("7E 07 05 03"))
        assertTrue(state.lastHexPacket.endsWith("10 EF"))
    }

    @Test
    fun `sendColor raw channels overload correctly delegates to RgbColor`() {
        client.sendColor(r = 0, g = 255, b = 128, force = true)

        val state = AmbilightStateRepository.state.value
        assertEquals(0, state.currentR)
        assertEquals(255, state.currentG)
        assertEquals(128, state.currentB)
    }

    @Test
    fun `startBleScan gracefully sets ERROR status when BluetoothAdapter is unavailable`() {
        client.startBleScan()
        val state = AmbilightStateRepository.state.value
        assertEquals(BleConnectionStatus.ERROR, state.bleStatus)
        assertFalse(client.isConnectingOrConnected.get())
    }

    @Test
    fun `sendHardwareBrightness and power commands execute without exception`() {
        // In unit test environment without real GATT connection, these methods safely no-op
        client.sendHardwareBrightness(80)
        client.sendPowerOn()
        client.sendPowerOff()
    }
}
