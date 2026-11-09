package com.lotus.ambilighttv.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.lotus.ambilighttv.model.RgbColor
import com.lotus.ambilighttv.state.AmbilightStateRepository
import com.lotus.ambilighttv.state.BleConnectionStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production Bluetooth Low Energy (BLE) GATT client for LotusLantern / ELK-BLEDOB controllers.
 *
 * Resilience & Performance Optimizations (Milestone M2):
 * 1. Lifecycle Teardown & Reconnect Storm Fix: Uses [isExplicitShutdown] atomic guard to prevent
 *    asynchronous `STATE_DISCONNECTED` callbacks from scheduling reconnect loops during service teardown.
 * 2. Scan Callback Concurrency Guard: Uses [isConnectingOrConnected] atomic CAS to ensure multiple
 *    rapid scan results cannot trigger concurrent `connectGatt` attempts or exceed Android's 32-client limit.
 * 3. Zero-Allocation High-Speed Transmission: Uses pre-allocated reusable 9-byte buffers ([txColorBuffer],
 *    [txBrightnessBuffer]) and in-place [BleDobProtocol.writeColorPacket] with [RgbColor] value class.
 * 4. UI Telemetry Throttling: Decouples 25-30 FPS hardware LED updates from UI StateFlow emissions,
 *    throttling telemetry and hex string generation to 4 Hz ([UI_TELEMETRY_INTERVAL_MS]) to prevent TV
 *    Compose recomposition storms.
 * 5. Magic Numbers Elimination: All timings and protocol parameters are extracted into self-documenting
 *    named constants.
 */
@SuppressLint("MissingPermission")
class BleDobGattClient(
    private val context: Context,
    coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        /** Duration to wait for GATT service discovery before falling back to BLE scanning. */
        const val CONNECT_TIMEOUT_MS: Long = 5_000L

        /** Maximum duration for active BLE scanner before returning to idle. */
        const val BLE_SCAN_DURATION_MS: Long = 10_000L

        /** Cooldown backoff delay before scheduling automatic reconnection after a link loss. */
        const val RECONNECT_BACKOFF_MS: Long = 2_500L

        /** Interval between UI telemetry updates (4 Hz = 250ms) to prevent TV Compose recomposition storms. */
        const val UI_TELEMETRY_INTERVAL_MS: Long = 250L

        /** Delay between BLEDOB and BLEDOM packets during DUAL_HYBRID power-on sequence. */
        const val DUAL_COMMAND_POWER_ON_DELAY_MS: Long = 45L

        /** Delay between BLEDOB and BLEDOM packets during DUAL_HYBRID power-off sequence. */
        const val DUAL_COMMAND_POWER_OFF_DELAY_MS: Long = 35L

        /** Inter-packet delay between power-off retry bursts. */
        const val POWER_OFF_BURST_DELAY_MS: Long = 50L

        /** Number of repetitions for power-off bursts to ensure strip controller registers shutdown. */
        const val POWER_OFF_REPEAT_COUNT: Int = 2

        /** Minimum framerate clamp for transmission rate limiter. */
        const val MIN_TARGET_FPS: Int = 10

        /** Maximum framerate clamp for transmission rate limiter. */
        const val MAX_TARGET_FPS: Int = 30
    }

    private val scope = CoroutineScope(SupervisorJob() + coroutineDispatcher)
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    @Volatile
    private var bluetoothGatt: BluetoothGatt? = null

    @Volatile
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    internal var isConnectedAndReady: Boolean = false
        private set

    /**
     * Guard flag ensuring intentional disconnects or service stops do NOT awaken
     * an asynchronous reconnect coroutine when Android dispatches STATE_DISCONNECTED.
     */
    internal val isExplicitShutdown = AtomicBoolean(false)

    /**
     * Guard flag preventing concurrent GATT connection attempts when multiple BLE scan
     * callbacks arrive simultaneously before `stopScan` takes effect in the Bluetooth daemon.
     */
    internal val isConnectingOrConnected = AtomicBoolean(false)

    /** Pre-allocated 9-byte buffer for zero-allocation color packet framing at 25-30 FPS. */
    private val txColorBuffer = ByteArray(BleDobProtocol.PACKET_LENGTH)

    /** Pre-allocated 9-byte buffer for zero-allocation hardware brightness packet framing. */
    private val txBrightnessBuffer = ByteArray(BleDobProtocol.PACKET_LENGTH)

    /** Tracked last sent color to compute Manhattan delta without magic integer sentinels. */
    internal var lastSentColor: RgbColor? = null
        private set

    /** Timestamp of the last physical Bluetooth transmission for frame-rate limiting. */
    private var lastWriteTimestampMs: Long = 0L

    /** Timestamp of the last UI telemetry update to throttle Compose recomposition to 2-4 Hz. */
    private var lastTelemetryTimestampMs: Long = 0L

    private var reconnectJob: Job? = null
    private var scanJob: Job? = null
    private var connectTimeoutJob: Job? = null

    /**
     * Initiates connection to [targetMac] or starts BLE scan if MAC is unconfigured.
     */
    fun connect(targetMac: String = AmbilightStateRepository.state.value.targetMacAddress) {
        isExplicitShutdown.set(false)

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            isConnectingOrConnected.set(false)
            AmbilightStateRepository.updateRuntimeState {
                it.copy(bleStatus = BleConnectionStatus.ERROR)
            }
            return
        }

        val cleanMac = targetMac.trim().uppercase()
        if (isConnectedAndReady && bluetoothGatt?.device?.address?.uppercase() == cleanMac) {
            return
        }

        if (cleanMac.isBlank() || !BluetoothAdapter.checkBluetoothAddress(cleanMac)) {
            startBleScan()
            return
        }

        if (!isConnectingOrConnected.compareAndSet(false, true)) {
            // Already connecting or connected
            return
        }

        AmbilightStateRepository.updateRuntimeState {
            it.copy(bleStatus = BleConnectionStatus.CONNECTING, targetMacAddress = cleanMac)
        }

        try {
            val device = adapter.getRemoteDevice(cleanMac)
            connectToDevice(device)
        } catch (_: IllegalArgumentException) {
            isConnectingOrConnected.set(false)
            startBleScan()
        } catch (_: SecurityException) {
            isConnectingOrConnected.set(false)
            AmbilightStateRepository.updateRuntimeState {
                it.copy(bleStatus = BleConnectionStatus.ERROR)
            }
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        closeGattOnly()
        try {
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
            armConnectTimeout()
        } catch (_: SecurityException) {
            isConnectingOrConnected.set(false)
            AmbilightStateRepository.updateRuntimeState {
                it.copy(bleStatus = BleConnectionStatus.ERROR)
            }
        }
    }

    private fun armConnectTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (!isConnectedAndReady) {
                isConnectingOrConnected.set(false)
                closeGattOnly()
                startBleScan()
            }
        }
    }

    /**
     * Starts low-latency BLE scan looking for ELK-BLEDOB / BLEDOM controllers.
     */
    fun startBleScan() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            isConnectingOrConnected.set(false)
            AmbilightStateRepository.updateRuntimeState {
                it.copy(bleStatus = BleConnectionStatus.ERROR)
            }
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            isConnectingOrConnected.set(false)
            return
        }

        isConnectingOrConnected.set(false)
        AmbilightStateRepository.updateRuntimeState {
            it.copy(bleStatus = BleConnectionStatus.SCANNING)
        }
        scanJob?.cancel()
        scanJob = scope.launch {
            runCatching { scanner.stopScan(leScanCallback) }
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, leScanCallback)
            delay(BLE_SCAN_DURATION_MS)
            runCatching { scanner.stopScan(leScanCallback) }
            if (!isConnectedAndReady && AmbilightStateRepository.state.value.bleStatus == BleConnectionStatus.SCANNING) {
                AmbilightStateRepository.updateRuntimeState {
                    it.copy(bleStatus = BleConnectionStatus.DISCONNECTED)
                }
            }
        }
    }

    internal val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val name = device.name?.uppercase() ?: ""
            val address = device.address?.uppercase() ?: ""
            val targetMac = AmbilightStateRepository.state.value.targetMacAddress.uppercase()

            val matchesKnownController = name.contains("BLEDOB") ||
                name.contains("BLEDOM") ||
                name.contains("MELK") ||
                name.contains("LEDBLE") ||
                name.contains("LOTUS")

            if (address == targetMac || matchesKnownController) {
                if (!isConnectingOrConnected.compareAndSet(false, true)) {
                    // Another scan result or connection attempt already claimed the link
                    return
                }

                runCatching { bluetoothAdapter?.bluetoothLeScanner?.stopScan(this) }
                scanJob?.cancel()
                connectTimeoutJob?.cancel()

                AmbilightStateRepository.updateSettings(
                    context = context,
                    targetMacAddress = address
                )
                AmbilightStateRepository.updateRuntimeState {
                    it.copy(
                        targetMacAddress = address,
                        connectedDeviceName = name.ifBlank { BleDobProtocol.DEFAULT_DEVICE_NAME },
                        bleStatus = BleConnectionStatus.CONNECTING
                    )
                }
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isConnectingOrConnected.set(false)
            AmbilightStateRepository.updateRuntimeState {
                it.copy(bleStatus = BleConnectionStatus.ERROR)
            }
        }
    }

    internal val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (bluetoothGatt != null && gatt != bluetoothGatt) {
                try { gatt.close() } catch (_: Throwable) {}
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectTimeoutJob?.cancel()
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    isConnectedAndReady = false
                    isConnectingOrConnected.set(false)
                    writeCharacteristic = null

                    if (isExplicitShutdown.get()) {
                        // User or service deliberately disconnected; suppress reconnect
                        return
                    }

                    AmbilightStateRepository.updateRuntimeState {
                        it.copy(bleStatus = BleConnectionStatus.DISCONNECTED)
                    }
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (bluetoothGatt != null && gatt != bluetoothGatt) return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                isConnectingOrConnected.set(false)
                if (!isExplicitShutdown.get()) {
                    scheduleReconnect()
                }
                return
            }

            val service = gatt.getService(BleDobProtocol.SERVICE_UUID)
                ?: gatt.getService(BleDobProtocol.FALLBACK_SERVICE_UUID)
            val characteristic = service?.getCharacteristic(BleDobProtocol.WRITE_CHAR_UUID)
                ?: service?.getCharacteristic(BleDobProtocol.FALLBACK_WRITE_CHAR_UUID)
                ?: gatt.services.firstNotNullOfOrNull { svc ->
                    svc.characteristics.firstOrNull { ch ->
                        (ch.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                    }
                }

            if (characteristic != null) {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                writeCharacteristic = characteristic
                isConnectedAndReady = true
                isConnectingOrConnected.set(false)
                connectTimeoutJob?.cancel()

                AmbilightStateRepository.updateRuntimeState {
                    it.copy(
                        bleStatus = BleConnectionStatus.CONNECTED,
                        connectedDeviceName = gatt.device.name ?: BleDobProtocol.DEFAULT_DEVICE_NAME
                    )
                }
                sendPowerOn()
            } else {
                isConnectedAndReady = false
                isConnectingOrConnected.set(false)
                AmbilightStateRepository.updateRuntimeState {
                    it.copy(bleStatus = BleConnectionStatus.ERROR)
                }
                if (!isExplicitShutdown.get()) {
                    scheduleReconnect()
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (isExplicitShutdown.get()) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_BACKOFF_MS)
            if (!isExplicitShutdown.get()) {
                connect(AmbilightStateRepository.state.value.targetMacAddress)
            }
        }
    }

    /**
     * Sends Power ON command using static pre-compiled byte packet (zero allocation).
     */
    fun sendPowerOn() {
        val state = AmbilightStateRepository.state.value
        val packet = BleDobProtocol.createPowerOnPacket(state.protocolVariant)
        writeRawPacket(packet)
        if (state.protocolVariant == BleDobProtocol.ProtocolVariant.DUAL_HYBRID) {
            scope.launch {
                delay(DUAL_COMMAND_POWER_ON_DELAY_MS)
                writeRawPacket(BleDobProtocol.createPowerOnPacket(BleDobProtocol.ProtocolVariant.BLEDOM_04))
            }
        }
    }

    /**
     * Sends Power OFF burst using static pre-compiled byte packet (zero allocation).
     */
    fun sendPowerOff() {
        val state = AmbilightStateRepository.state.value
        scope.launch {
            repeat(POWER_OFF_REPEAT_COUNT) {
                writeRawPacket(BleDobProtocol.createPowerOffPacket(state.protocolVariant))
                if (state.protocolVariant == BleDobProtocol.ProtocolVariant.DUAL_HYBRID) {
                    delay(DUAL_COMMAND_POWER_OFF_DELAY_MS)
                    writeRawPacket(BleDobProtocol.createPowerOffPacket(BleDobProtocol.ProtocolVariant.BLEDOM_04))
                }
                delay(POWER_OFF_BURST_DELAY_MS)
            }
        }
    }

    /**
     * Sends hardware brightness packet (0..100%) using pre-allocated reusable buffer.
     */
    fun sendHardwareBrightness(percent: Int) {
        val state = AmbilightStateRepository.state.value
        BleDobProtocol.writeBrightnessPacket(txBrightnessBuffer, percent, state.protocolVariant)
        writeRawPacket(txBrightnessBuffer)
    }

    /**
     * Primary zero-allocation color transmission entry point.
     * Uses pre-allocated [txColorBuffer] and in-place [BleDobProtocol.writeColorPacket].
     * Throttles UI telemetry updates to [UI_TELEMETRY_INTERVAL_MS] (4 Hz) to prevent Compose recomposition storms.
     *
     * @param color Typed domain color to transmit to physical LED strip.
     * @param force When true, bypasses frame-rate throttle and delta checks.
     */
    fun sendColor(color: RgbColor, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val state = AmbilightStateRepository.state.value
        val minIntervalMs = (1000L / state.profile.targetFps.coerceIn(MIN_TARGET_FPS, MAX_TARGET_FPS))

        if (!force && (now - lastWriteTimestampMs) < minIntervalMs) {
            return
        }

        val last = lastSentColor
        if (!force && last != null) {
            val delta = color.deltaTo(last)
            if (delta < state.profile.minColorDelta) {
                return
            }
        }

        BleDobProtocol.writeColorPacket(
            outBuffer = txColorBuffer,
            color = color,
            brightnessScale = state.brightnessPercent / 100f,
            channelOrder = state.channelOrder,
            variant = state.protocolVariant
        )

        if (writeRawPacket(txColorBuffer)) {
            lastWriteTimestampMs = now
            lastSentColor = color
        }

        if (force || (now - lastTelemetryTimestampMs) >= UI_TELEMETRY_INTERVAL_MS) {
            lastTelemetryTimestampMs = now
            val hex = BleDobProtocol.toHexString(txColorBuffer)
            AmbilightStateRepository.updateRuntimeColor(color, hex)
        }
    }

    /**
     * Backward-compatible overload accepting raw integer channels.
     * Wraps channels into zero-allocation [RgbColor] and delegates to [sendColor].
     */
    fun sendColor(r: Int, g: Int, b: Int, force: Boolean = false) {
        sendColor(RgbColor.fromRgb(r, g, b), force)
    }

    private fun writeRawPacket(packet: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val characteristic = writeCharacteristic ?: return false
        if (!isConnectedAndReady) return false

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    packet,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                characteristic.value = packet
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun closeGattOnly() {
        isConnectedAndReady = false
        writeCharacteristic = null
        val gatt = bluetoothGatt
        bluetoothGatt = null
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Throwable) {
        }
    }

    /**
     * Gracefully disconnects and shuts down GATT link.
     * Sets [isExplicitShutdown] to true to guarantee no reconnect coroutine awakens.
     */
    fun disconnectAndClose() {
        isExplicitShutdown.set(true)
        isConnectingOrConnected.set(false)
        reconnectJob?.cancel()
        reconnectJob = null
        scanJob?.cancel()
        scanJob = null
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        closeGattOnly()
        AmbilightStateRepository.updateRuntimeState {
            it.copy(bleStatus = BleConnectionStatus.DISCONNECTED)
        }
    }

    /**
     * Full teardown method releasing all coroutine jobs and Bluetooth resources.
     */
    fun release() {
        disconnectAndClose()
        scope.cancel()
    }
}
