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
import com.lotus.ambilighttv.state.AmbilightStateRepository
import com.lotus.ambilighttv.state.BleConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@SuppressLint("MissingPermission")
class BleDobGattClient(
    private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    @Volatile
    private var bluetoothGatt: BluetoothGatt? = null

    @Volatile
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var isConnectedAndReady: Boolean = false

    private var reconnectJob: Job? = null
    private var scanJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var lastWriteTimestampMs: Long = 0L
    private var lastSentR: Int = -1
    private var lastSentG: Int = -1
    private var lastSentB: Int = -1

    fun connect(targetMac: String = AmbilightStateRepository.state.value.targetMacAddress) {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
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

        AmbilightStateRepository.updateRuntimeState {
            it.copy(bleStatus = BleConnectionStatus.CONNECTING, targetMacAddress = cleanMac)
        }

        try {
            val device = adapter.getRemoteDevice(cleanMac)
            connectToDevice(device)
            connectTimeoutJob?.cancel()
            connectTimeoutJob = scope.launch {
                delay(5_000L)
                if (!isConnectedAndReady) {
                    startBleScan()
                }
            }
        } catch (_: IllegalArgumentException) {
            startBleScan()
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        closeGattOnly()
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    fun startBleScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
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
            delay(10_000L)
            runCatching { scanner.stopScan(leScanCallback) }
            if (!isConnectedAndReady && AmbilightStateRepository.state.value.bleStatus == BleConnectionStatus.SCANNING) {
                AmbilightStateRepository.updateRuntimeState {
                    it.copy(bleStatus = BleConnectionStatus.DISCONNECTED)
                }
            }
        }
    }

    private val leScanCallback = object : ScanCallback() {
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
                        connectedDeviceName = name.ifBlank { BleDobProtocol.DEFAULT_DEVICE_NAME }
                    )
                }
                connectToDevice(device)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectTimeoutJob?.cancel()
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    isConnectedAndReady = false
                    writeCharacteristic = null
                    AmbilightStateRepository.updateRuntimeState {
                        it.copy(bleStatus = BleConnectionStatus.DISCONNECTED)
                    }
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                scheduleReconnect()
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
                AmbilightStateRepository.updateRuntimeState {
                    it.copy(
                        bleStatus = BleConnectionStatus.CONNECTED,
                        connectedDeviceName = gatt.device.name ?: BleDobProtocol.DEFAULT_DEVICE_NAME
                    )
                }
                sendPowerOn()
            } else {
                AmbilightStateRepository.updateRuntimeState {
                    it.copy(bleStatus = BleConnectionStatus.ERROR)
                }
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(2_500L)
            connect(AmbilightStateRepository.state.value.targetMacAddress)
        }
    }

    fun sendPowerOn() {
        val state = AmbilightStateRepository.state.value
        val packet = BleDobProtocol.createPowerOnPacket(state.protocolVariant)
        writeRawPacket(packet)
        if (state.protocolVariant == BleDobProtocol.ProtocolVariant.DUAL_HYBRID) {
            scope.launch {
                delay(45L)
                writeRawPacket(BleDobProtocol.createPowerOnPacket(BleDobProtocol.ProtocolVariant.BLEDOM_04))
            }
        }
    }

    fun sendPowerOff() {
        val state = AmbilightStateRepository.state.value
        scope.launch {
            repeat(2) {
                writeRawPacket(BleDobProtocol.createPowerOffPacket(state.protocolVariant))
                if (state.protocolVariant == BleDobProtocol.ProtocolVariant.DUAL_HYBRID) {
                    delay(35L)
                    writeRawPacket(BleDobProtocol.createPowerOffPacket(BleDobProtocol.ProtocolVariant.BLEDOM_04))
                }
                delay(50L)
            }
        }
    }

    fun sendHardwareBrightness(percent: Int) {
        val state = AmbilightStateRepository.state.value
        val packet = BleDobProtocol.createBrightnessPacket(percent, state.protocolVariant)
        writeRawPacket(packet)
    }

    fun sendColor(r: Int, g: Int, b: Int, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val state = AmbilightStateRepository.state.value
        val minIntervalMs = (1000L / state.profile.targetFps.coerceIn(10, 30))

        if (!force && (now - lastWriteTimestampMs) < minIntervalMs) {
            return
        }

        val delta = abs(r - lastSentR) + abs(g - lastSentG) + abs(b - lastSentB)
        if (!force && delta < state.profile.minColorDelta) {
            return
        }

        val packet = BleDobProtocol.createColorPacket(
            r = r,
            g = g,
            b = b,
            brightnessScale = state.brightnessPercent / 100f,
            channelOrder = state.channelOrder,
            variant = state.protocolVariant
        )

        if (writeRawPacket(packet)) {
            lastWriteTimestampMs = now
            lastSentR = r
            lastSentG = g
            lastSentB = b
        }

        AmbilightStateRepository.updateRuntimeState {
            it.copy(
                currentR = r.coerceIn(0, 255),
                currentG = g.coerceIn(0, 255),
                currentB = b.coerceIn(0, 255),
                lastHexPacket = BleDobProtocol.toHexString(packet)
            )
        }
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
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: Throwable) {
        } finally {
            bluetoothGatt = null
        }
    }

    fun disconnectAndClose() {
        reconnectJob?.cancel()
        scanJob?.cancel()
        connectTimeoutJob?.cancel()
        closeGattOnly()
        AmbilightStateRepository.updateRuntimeState {
            it.copy(bleStatus = BleConnectionStatus.DISCONNECTED)
        }
    }
}
