package com.lotus.ambilighttv.state

import android.content.Context
import com.lotus.ambilighttv.ble.BleDobProtocol
import com.lotus.ambilighttv.engine.VividEdgeColorEngine
import com.lotus.ambilighttv.model.BleMacAddress
import com.lotus.ambilighttv.model.RgbColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class BleConnectionStatus(val label: String) {
    DISCONNECTED("Desconectado"),
    SCANNING("Buscando fitas BLEDOB/BLEDOM..."),
    CONNECTING("Conectando via Bluetooth..."),
    CONNECTED("Conectado"),
    ERROR("Erro BLE, tentando reconectar")
}

enum class AmbilightMode(val label: String) {
    STOPPED("Parado"),
    SCREEN_SYNC("Sincronizando Tela (Ambilight Ativo)"),
    DRM_AUDIO_FALLBACK("Modo DRM Detectado (Fallback Audio / Luz Ambiente)"),
    TV_STANDBY("TV em Standby (Fita Desligada)")
}

/**
 * Immutable UI and runtime state for Lotus Ambilight TV.
 *
 * Holds user preferences, hardware connection state, and live telemetry.
 * Defaults represent a clean, unconfigured idle state with zero arbitrary mock telemetry.
 */
data class AmbilightUiState(
    val isServiceRunning: Boolean = false,
    val mode: AmbilightMode = AmbilightMode.STOPPED,
    val bleStatus: BleConnectionStatus = BleConnectionStatus.DISCONNECTED,
    val targetMacAddress: String = BleDobProtocol.DEFAULT_MAC_ADDRESS,
    val connectedDeviceName: String = DEFAULT_DEVICE_NAME,
    val currentR: Int = 0,
    val currentG: Int = 0,
    val currentB: Int = 0,
    val lastHexPacket: String = "",
    val profile: VividEdgeColorEngine.AmbilightProfile = VividEdgeColorEngine.AmbilightProfile.DYNAMIC,
    val channelOrder: BleDobProtocol.ChannelOrder = BleDobProtocol.ChannelOrder.RGB,
    val protocolVariant: BleDobProtocol.ProtocolVariant = BleDobProtocol.ProtocolVariant.BLEDOB_07,
    val brightnessPercent: Int = 90,
    val standbySyncEnabled: Boolean = true,
    val drmFallbackEnabled: Boolean = true,
    val fps: Int = 0
) {
    companion object {
        /** Human-readable fallback label when no peripheral is connected. */
        const val DEFAULT_DEVICE_NAME = "Nenhuma fita conectada"
    }

    /**
     * Typed domain representation of the current live ambilight color.
     * Backed by zero-allocation [RgbColor] value class.
     */
    val currentColor: RgbColor
        get() = RgbColor.fromRgb(currentR, currentG, currentB)

    /**
     * Typed domain representation of the target Bluetooth MAC address.
     * Safely wraps validation and uppercase normalization.
     */
    val targetMac: BleMacAddress
        get() = BleMacAddress.fromOrDefault(targetMacAddress, BleMacAddress.UNCONFIGURED)

    /**
     * Convenience copy helper for updating live color telemetry using [RgbColor].
     */
    fun withColor(color: RgbColor, lastPacket: String = lastHexPacket): AmbilightUiState =
        copy(
            currentR = color.r,
            currentG = color.g,
            currentB = color.b,
            lastHexPacket = lastPacket
        )

    /**
     * Convenience copy helper for updating target hardware using [BleMacAddress].
     */
    fun withMacAddress(mac: BleMacAddress, deviceName: String = connectedDeviceName): AmbilightUiState =
        copy(
            targetMacAddress = mac.formatted,
            connectedDeviceName = deviceName
        )
}

object AmbilightStateRepository {
    private const val PREFS_NAME = "lotus_ambilight_prefs"
    private const val KEY_MAC = "target_mac"
    private const val KEY_PROFILE = "profile"
    private const val KEY_CHANNEL_ORDER = "channel_order"
    private const val KEY_VARIANT = "protocol_variant"
    private const val KEY_BRIGHTNESS = "brightness_percent"
    private const val KEY_STANDBY_SYNC = "standby_sync"
    private const val KEY_DRM_FALLBACK = "drm_fallback"

    private val _state = MutableStateFlow(AmbilightUiState())
    val state: StateFlow<AmbilightUiState> = _state.asStateFlow()

    fun loadPreferences(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mac = prefs.getString(KEY_MAC, BleDobProtocol.DEFAULT_MAC_ADDRESS)
            ?: BleDobProtocol.DEFAULT_MAC_ADDRESS
        val profileName = prefs.getString(KEY_PROFILE, VividEdgeColorEngine.AmbilightProfile.DYNAMIC.name)
        val orderName = prefs.getString(KEY_CHANNEL_ORDER, BleDobProtocol.ChannelOrder.RGB.name)
        val variantName = prefs.getString(KEY_VARIANT, BleDobProtocol.ProtocolVariant.BLEDOB_07.name)
        val brightness = prefs.getInt(KEY_BRIGHTNESS, 90).coerceIn(10, 100)
        val standbySync = prefs.getBoolean(KEY_STANDBY_SYNC, true)
        val drmFallback = prefs.getBoolean(KEY_DRM_FALLBACK, true)

        val normalizedMac = BleMacAddress.fromOrNull(mac)?.formatted ?: mac.trim().uppercase()

        _state.update {
            it.copy(
                targetMacAddress = normalizedMac,
                profile = runCatching { VividEdgeColorEngine.AmbilightProfile.valueOf(profileName!!) }
                    .getOrDefault(VividEdgeColorEngine.AmbilightProfile.DYNAMIC),
                channelOrder = runCatching { BleDobProtocol.ChannelOrder.valueOf(orderName!!) }
                    .getOrDefault(BleDobProtocol.ChannelOrder.RGB),
                protocolVariant = runCatching { BleDobProtocol.ProtocolVariant.valueOf(variantName!!) }
                    .getOrDefault(BleDobProtocol.ProtocolVariant.BLEDOB_07),
                brightnessPercent = brightness,
                standbySyncEnabled = standbySync,
                drmFallbackEnabled = drmFallback
            )
        }
    }

    fun updateSettings(
        context: Context,
        profile: VividEdgeColorEngine.AmbilightProfile = _state.value.profile,
        channelOrder: BleDobProtocol.ChannelOrder = _state.value.channelOrder,
        protocolVariant: BleDobProtocol.ProtocolVariant = _state.value.protocolVariant,
        brightnessPercent: Int = _state.value.brightnessPercent,
        standbySyncEnabled: Boolean = _state.value.standbySyncEnabled,
        drmFallbackEnabled: Boolean = _state.value.drmFallbackEnabled,
        targetMacAddress: String = _state.value.targetMacAddress
    ) {
        val clampedBrightness = brightnessPercent.coerceIn(10, 100)
        val normalizedMac = BleMacAddress.fromOrNull(targetMacAddress)?.formatted
            ?: targetMacAddress.trim().uppercase()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MAC, normalizedMac)
            .putString(KEY_PROFILE, profile.name)
            .putString(KEY_CHANNEL_ORDER, channelOrder.name)
            .putString(KEY_VARIANT, protocolVariant.name)
            .putInt(KEY_BRIGHTNESS, clampedBrightness)
            .putBoolean(KEY_STANDBY_SYNC, standbySyncEnabled)
            .putBoolean(KEY_DRM_FALLBACK, drmFallbackEnabled)
            .apply()

        _state.update {
            it.copy(
                targetMacAddress = normalizedMac,
                profile = profile,
                channelOrder = channelOrder,
                protocolVariant = protocolVariant,
                brightnessPercent = clampedBrightness,
                standbySyncEnabled = standbySyncEnabled,
                drmFallbackEnabled = drmFallbackEnabled
            )
        }
    }

    fun updateSettings(
        context: Context,
        targetMac: BleMacAddress,
        profile: VividEdgeColorEngine.AmbilightProfile = _state.value.profile,
        channelOrder: BleDobProtocol.ChannelOrder = _state.value.channelOrder,
        protocolVariant: BleDobProtocol.ProtocolVariant = _state.value.protocolVariant,
        brightnessPercent: Int = _state.value.brightnessPercent,
        standbySyncEnabled: Boolean = _state.value.standbySyncEnabled,
        drmFallbackEnabled: Boolean = _state.value.drmFallbackEnabled
    ) = updateSettings(
        context = context,
        profile = profile,
        channelOrder = channelOrder,
        protocolVariant = protocolVariant,
        brightnessPercent = brightnessPercent,
        standbySyncEnabled = standbySyncEnabled,
        drmFallbackEnabled = drmFallbackEnabled,
        targetMacAddress = targetMac.formatted
    )

    fun updateRuntimeColor(color: RgbColor, lastPacket: String = "") {
        _state.update {
            it.copy(
                currentR = color.r,
                currentG = color.g,
                currentB = color.b,
                lastHexPacket = lastPacket
            )
        }
    }

    fun updateRuntimeState(transform: (AmbilightUiState) -> AmbilightUiState) {
        _state.update(transform)
    }

    /**
     * Resets repository state to clean unconfigured defaults (primarily for testing).
     */
    fun resetForTesting() {
        _state.value = AmbilightUiState()
    }
}
