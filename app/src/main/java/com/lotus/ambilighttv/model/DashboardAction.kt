package com.lotus.ambilighttv.model

import com.lotus.ambilighttv.ble.BleDobProtocol
import com.lotus.ambilighttv.engine.VividEdgeColorEngine

/**
 * Sealed interface representing all user intents dispatched from the Ambilight TV dashboard.
 *
 * Implements Unidirectional Data Flow (UDF):
 * - State flows down: `AmbilightUiState`
 * - Events flow up: `(DashboardAction) -> Unit`
 */
sealed interface DashboardAction {

    // ========================================================================
    // 1. Service & Screen Capture Lifecycle
    // ========================================================================

    /** Requests MediaProjection permissions to begin background screen capture. */
    data object StartAmbilight : DashboardAction

    /** Stops foreground capture service, releases projection, and turns off LED strip. */
    data object StopAmbilight : DashboardAction

    /** Minimizes dashboard to background (moveTaskToBack) while keeping LEDs active. */
    data object MinimizeToBackground : DashboardAction

    // ========================================================================
    // 2. Bluetooth Low Energy Connectivity & Discovery
    // ========================================================================

    /** Reconnects to the configured Bluetooth LE peripheral. */
    data object ConnectBle : DashboardAction

    /** Starts active BLE scan to automatically discover nearby ELK-BLEDOB / ELK-BLEDOM strips. */
    data object ScanBle : DashboardAction

    /** Saves a new target MAC address and attempts connection. */
    data class SaveMacAddress(val mac: String) : DashboardAction {
        companion object {
            operator fun invoke(address: BleMacAddress): SaveMacAddress =
                SaveMacAddress(address.formatted)
        }
    }

    // ========================================================================
    // 3. Hardware Direct Control & Color Testing
    // ========================================================================

    /** Sends manual power ON packet to physical strip. */
    data object PowerOn : DashboardAction

    /** Sends manual power OFF packet to physical strip. */
    data object PowerOff : DashboardAction

    /**
     * Sends a direct hardware test color packet to verify BLE communication.
     * Backed by zero-allocation [RgbColor] value class.
     */
    data class TestColor(val color: RgbColor) : DashboardAction {
        constructor(r: Int, g: Int, b: Int) : this(RgbColor.fromRgb(r, g, b))
    }

    // ========================================================================
    // 4. Calibration & Ambilight Engine Settings
    // ========================================================================

    /** Selects color enhancement profile (DYNAMIC, CINEMA, GAMING). */
    data class SelectProfile(val profile: VividEdgeColorEngine.AmbilightProfile) : DashboardAction

    /** Selects physical RGB LED channel wiring order (RGB, RBG, BRG, BGR, GRB, GBR). */
    data class SelectChannelOrder(val order: BleDobProtocol.ChannelOrder) : DashboardAction

    /** Selects BLE communication protocol variant (BLEDOB 0x7E, BLEDOM 0xFB, DUAL HYBRID). */
    data class SelectProtocolVariant(val variant: BleDobProtocol.ProtocolVariant) : DashboardAction

    /** Updates overall strip brightness percentage. */
    data class ChangeBrightness(val percent: Int) : DashboardAction

    // ========================================================================
    // 5. Automations & Sync Toggles
    // ========================================================================

    /** Toggles TV standby sync (auto turn on/off LEDs on TV screen state changes). */
    data object ToggleStandbySync : DashboardAction

    /** Toggles audio visualizer fallback when DRM-protected content (Netflix/Prime) is active. */
    data object ToggleDrmFallback : DashboardAction
}

// Convenience aliases for architecture interoperability
typealias UpdateProfile = DashboardAction.SelectProfile
typealias UpdateBrightness = DashboardAction.ChangeBrightness
typealias UpdateChannelOrder = DashboardAction.SelectChannelOrder
typealias UpdateVariant = DashboardAction.SelectProtocolVariant
