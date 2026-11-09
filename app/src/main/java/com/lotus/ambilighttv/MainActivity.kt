package com.lotus.ambilighttv

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.lotus.ambilighttv.ble.BleDobProtocol
import com.lotus.ambilighttv.engine.VividEdgeColorEngine
import com.lotus.ambilighttv.model.BleMacAddress
import com.lotus.ambilighttv.model.DashboardAction
import com.lotus.ambilighttv.model.RgbColor
import com.lotus.ambilighttv.service.AmbilightForegroundService
import com.lotus.ambilighttv.state.AmbilightMode
import com.lotus.ambilighttv.state.AmbilightStateRepository
import com.lotus.ambilighttv.state.AmbilightUiState
import com.lotus.ambilighttv.state.BleConnectionStatus
import com.lotus.ambilighttv.ui.theme.AccentCyan
import com.lotus.ambilighttv.ui.theme.BorderDefault
import com.lotus.ambilighttv.ui.theme.BorderSubtle
import com.lotus.ambilighttv.ui.theme.LotusAmbilightTheme
import com.lotus.ambilighttv.ui.theme.OnAccentDark
import com.lotus.ambilighttv.ui.theme.PrimaryAction
import com.lotus.ambilighttv.ui.theme.StatusConnected
import com.lotus.ambilighttv.ui.theme.StatusDisconnected
import com.lotus.ambilighttv.ui.theme.StatusError
import com.lotus.ambilighttv.ui.theme.SurfaceCard
import com.lotus.ambilighttv.ui.theme.SurfaceElevated
import com.lotus.ambilighttv.ui.theme.SurfaceFocused
import com.lotus.ambilighttv.ui.theme.SurfaceSelected
import com.lotus.ambilighttv.ui.theme.SurfaceSelectedFocused
import com.lotus.ambilighttv.ui.theme.TelemetryOrange
import com.lotus.ambilighttv.ui.theme.TextPrimary
import com.lotus.ambilighttv.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, AmbilightForegroundService::class.java).apply {
                action = AmbilightForegroundService.ACTION_START_PROJECTION
                putExtra(AmbilightForegroundService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AmbilightForegroundService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        sendServiceAction(AmbilightForegroundService.ACTION_CONNECT_BLE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AmbilightStateRepository.loadPreferences(this)
        ensureRuntimePermissionsAndConnect()

        setContent {
            val uiState by AmbilightStateRepository.state.collectAsState()
            LotusAmbilightTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AmbilightTvDashboard(
                        state = uiState,
                        onAction = ::handleDashboardAction
                    )
                }
            }
        }
    }

    private fun handleDashboardAction(action: DashboardAction) {
        when (action) {
            DashboardAction.StartAmbilight -> requestScreenCapture()
            DashboardAction.StopAmbilight -> sendServiceAction(AmbilightForegroundService.ACTION_STOP_SERVICE)
            DashboardAction.MinimizeToBackground -> moveTaskToBack(true)
            DashboardAction.ConnectBle -> {
                ensureRuntimePermissionsAndConnect()
                sendServiceAction(AmbilightForegroundService.ACTION_CONNECT_BLE)
            }
            DashboardAction.ScanBle -> {
                ensureRuntimePermissionsAndConnect()
                sendServiceAction(AmbilightForegroundService.ACTION_SCAN_BLE)
            }
            is DashboardAction.SaveMacAddress -> {
                val normalizedMac = BleMacAddress.fromOrNull(action.mac)?.formatted
                    ?: action.mac.trim().uppercase()
                AmbilightStateRepository.updateSettings(this, targetMacAddress = normalizedMac)
                sendServiceAction(AmbilightForegroundService.ACTION_CONNECT_BLE)
            }
            DashboardAction.PowerOn -> sendServiceAction(AmbilightForegroundService.ACTION_POWER_ON)
            DashboardAction.PowerOff -> sendServiceAction(AmbilightForegroundService.ACTION_POWER_OFF)
            is DashboardAction.TestColor -> sendTestColor(action.color)
            is DashboardAction.SelectProfile -> AmbilightStateRepository.updateSettings(this, profile = action.profile)
            is DashboardAction.SelectChannelOrder -> AmbilightStateRepository.updateSettings(this, channelOrder = action.order)
            is DashboardAction.SelectProtocolVariant -> AmbilightStateRepository.updateSettings(this, protocolVariant = action.variant)
            is DashboardAction.ChangeBrightness -> AmbilightStateRepository.updateSettings(
                this,
                brightnessPercent = action.percent.coerceIn(10, 100)
            )
            DashboardAction.ToggleStandbySync -> {
                val current = AmbilightStateRepository.state.value.standbySyncEnabled
                AmbilightStateRepository.updateSettings(this, standbySyncEnabled = !current)
            }
            DashboardAction.ToggleDrmFallback -> {
                val current = AmbilightStateRepository.state.value.drmFallbackEnabled
                AmbilightStateRepository.updateSettings(this, drmFallbackEnabled = !current)
            }
        }
    }

    private fun ensureRuntimePermissionsAndConnect() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.BLUETOOTH_CONNECT
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.BLUETOOTH_SCAN
            }
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.POST_NOTIFICATIONS
            }
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            sendServiceAction(AmbilightForegroundService.ACTION_CONNECT_BLE)
        }
    }

    private fun requestScreenCapture() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, AmbilightForegroundService::class.java).apply {
            this.action = action
        }
        ContextCompat.startForegroundService(this, intent)
    }

    fun sendTestColor(color: RgbColor) {
        val intent = Intent(this, AmbilightForegroundService::class.java).apply {
            action = AmbilightForegroundService.ACTION_TEST_COLOR
            putExtra(AmbilightForegroundService.EXTRA_TEST_R, color.r)
            putExtra(AmbilightForegroundService.EXTRA_TEST_G, color.g)
            putExtra(AmbilightForegroundService.EXTRA_TEST_B, color.b)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    fun sendTestColor(r: Int, g: Int, b: Int) {
        sendTestColor(RgbColor.fromRgb(r, g, b))
    }
}

// ============================================================================
// UI String Presentation Helpers
// ============================================================================

@Composable
fun BleConnectionStatus.asDisplayString(): String = when (this) {
    BleConnectionStatus.DISCONNECTED -> stringResource(R.string.ble_status_disconnected)
    BleConnectionStatus.SCANNING -> stringResource(R.string.ble_status_scanning)
    BleConnectionStatus.CONNECTING -> stringResource(R.string.ble_status_connecting)
    BleConnectionStatus.CONNECTED -> stringResource(R.string.ble_status_connected)
    BleConnectionStatus.ERROR -> stringResource(R.string.ble_status_error)
}

@Composable
fun AmbilightMode.asDisplayString(): String = when (this) {
    AmbilightMode.STOPPED -> stringResource(R.string.ambilight_mode_stopped)
    AmbilightMode.SCREEN_SYNC -> stringResource(R.string.ambilight_mode_screen_sync)
    AmbilightMode.DRM_AUDIO_FALLBACK -> stringResource(R.string.ambilight_mode_drm_audio)
    AmbilightMode.TV_STANDBY -> stringResource(R.string.ambilight_mode_tv_standby)
}

@Composable
fun VividEdgeColorEngine.AmbilightProfile.asDisplayString(): String = when (this) {
    VividEdgeColorEngine.AmbilightProfile.CINEMA -> stringResource(R.string.profile_cinema)
    VividEdgeColorEngine.AmbilightProfile.DYNAMIC -> stringResource(R.string.profile_dynamic)
    VividEdgeColorEngine.AmbilightProfile.GAMING -> stringResource(R.string.profile_gaming)
}

@Composable
fun BleDobProtocol.ChannelOrder.asDisplayString(): String = when (this) {
    BleDobProtocol.ChannelOrder.RGB -> stringResource(R.string.channel_order_rgb)
    BleDobProtocol.ChannelOrder.GRB -> stringResource(R.string.channel_order_grb)
    BleDobProtocol.ChannelOrder.BRG -> stringResource(R.string.channel_order_brg)
    BleDobProtocol.ChannelOrder.RBG -> stringResource(R.string.channel_order_rbg)
    BleDobProtocol.ChannelOrder.GBR -> stringResource(R.string.channel_order_gbr)
    BleDobProtocol.ChannelOrder.BGR -> stringResource(R.string.channel_order_bgr)
}

@Composable
fun BleDobProtocol.ProtocolVariant.asDisplayString(): String = when (this) {
    BleDobProtocol.ProtocolVariant.BLEDOB_07 -> stringResource(R.string.variant_bledob_07)
    BleDobProtocol.ProtocolVariant.BLEDOM_04 -> stringResource(R.string.variant_bledom_04)
    BleDobProtocol.ProtocolVariant.DUAL_HYBRID -> stringResource(R.string.variant_dual_hybrid)
}

// ============================================================================
// Dashboard Composables (Unidirectional Data Flow)
// ============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AmbilightTvDashboard(
    state: AmbilightUiState,
    onAction: (DashboardAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val liveColor = Color(state.currentR, state.currentG, state.currentB)
    val scrollState = rememberScrollState()
    var macInput by remember { mutableStateOf(state.targetMacAddress) }

    LaunchedEffect(state.targetMacAddress) {
        macInput = state.targetMacAddress
    }

    val haloContentDescription = stringResource(R.string.cd_live_color_halo)
    val macInputContentDescription = stringResource(R.string.cd_mac_input_field)
    val brightnessDecreaseDescription = stringResource(R.string.cd_brightness_decrease)
    val brightnessIncreaseDescription = stringResource(R.string.cd_brightness_increase)
    val brightnessMaxDescription = stringResource(R.string.cd_brightness_max)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header + Live Ambilight Halo Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 2.dp,
                    brush = Brush.horizontalGradient(
                        listOf(liveColor.copy(alpha = 0.85f), AccentCyan, liveColor.copy(alpha = 0.85f))
                    ),
                    shape = RoundedCornerShape(16.dp)
                ),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.header_app_title),
                        color = TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    val deviceStatusText = if (state.targetMacAddress.isNotBlank() &&
                        state.targetMacAddress != BleDobProtocol.DEFAULT_MAC_ADDRESS
                    ) {
                        stringResource(
                            R.string.header_device_status_connected,
                            state.connectedDeviceName,
                            state.targetMacAddress,
                            state.bleStatus.asDisplayString()
                        )
                    } else {
                        stringResource(
                            R.string.header_device_status_unconfigured,
                            state.connectedDeviceName.ifBlank { stringResource(R.string.device_fallback_name) },
                            state.bleStatus.asDisplayString()
                        )
                    }

                    val bleStatusColor = when (state.bleStatus) {
                        BleConnectionStatus.CONNECTED -> StatusConnected
                        BleConnectionStatus.CONNECTING,
                        BleConnectionStatus.SCANNING -> TelemetryOrange
                        BleConnectionStatus.ERROR -> StatusError
                        BleConnectionStatus.DISCONNECTED -> StatusDisconnected
                    }

                    Text(
                        text = deviceStatusText,
                        color = bleStatusColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    val signalPacket = if (state.lastHexPacket.isNotBlank()) {
                        state.lastHexPacket
                    } else {
                        stringResource(R.string.telemetry_idle_signal)
                    }

                    Text(
                        text = stringResource(
                            R.string.header_telemetry_format,
                            state.mode.asDisplayString(),
                            state.fps,
                            signalPacket
                        ),
                        color = TextSecondary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(liveColor, CircleShape)
                        .border(2.dp, TextPrimary, CircleShape)
                        .semantics {
                            contentDescription = haloContentDescription
                        }
                )
            }
        }

        // Section 1: Background Service & Screen Capture
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = stringResource(R.string.section_service_title),
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TvFocusableButton(
                        text = if (state.isServiceRunning) {
                            stringResource(R.string.btn_service_restart)
                        } else {
                            stringResource(R.string.btn_service_start)
                        },
                        primary = true,
                        onClick = { onAction(DashboardAction.StartAmbilight) }
                    )
                    if (state.isServiceRunning) {
                        TvFocusableButton(
                            text = stringResource(R.string.btn_service_minimize),
                            primary = false,
                            onClick = { onAction(DashboardAction.MinimizeToBackground) }
                        )
                        TvFocusableButton(
                            text = stringResource(R.string.btn_service_stop),
                            primary = false,
                            onClick = { onAction(DashboardAction.StopAmbilight) }
                        )
                    }
                }
            }
        }

        // Section 2: BLE Configuration & Hardware Quick Actions
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.section_ble_config_title),
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = macInput,
                        onValueChange = { macInput = it.uppercase() },
                        label = { Text(stringResource(R.string.label_mac_input)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = BorderSubtle,
                            focusedLabelColor = AccentCyan,
                            unfocusedLabelColor = TextSecondary
                        ),
                        modifier = Modifier
                            .width(340.dp)
                            .semantics {
                                contentDescription = macInputContentDescription
                            }
                    )
                    TvFocusableButton(
                        text = stringResource(R.string.btn_save_mac_and_connect),
                        onClick = { onAction(DashboardAction.SaveMacAddress(macInput)) }
                    )
                    TvFocusableButton(
                        text = stringResource(R.string.btn_scan_ble),
                        onClick = { onAction(DashboardAction.ScanBle) }
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TvFocusableButton(
                        text = stringResource(R.string.btn_reconnect_ble),
                        onClick = { onAction(DashboardAction.ConnectBle) }
                    )
                    TvFocusableButton(
                        text = stringResource(R.string.btn_power_on),
                        onClick = { onAction(DashboardAction.PowerOn) }
                    )
                    TvFocusableButton(
                        text = stringResource(R.string.btn_power_off),
                        onClick = { onAction(DashboardAction.PowerOff) }
                    )
                    TvFocusableButton(
                        text = stringResource(R.string.btn_color_red),
                        onClick = { onAction(DashboardAction.TestColor(RgbColor.RED)) }
                    )
                    TvFocusableButton(
                        text = stringResource(R.string.btn_color_green),
                        onClick = { onAction(DashboardAction.TestColor(RgbColor.GREEN)) }
                    )
                    TvFocusableButton(
                        text = stringResource(R.string.btn_color_blue),
                        onClick = { onAction(DashboardAction.TestColor(RgbColor.BLUE)) }
                    )
                    TvFocusableButton(
                        text = stringResource(R.string.btn_color_amber),
                        onClick = { onAction(DashboardAction.TestColor(RgbColor.WARM_AMBER)) }
                    )
                }
            }
        }

        // Section 3: Color Profiles, Calibration & Automation Toggles
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.section_calibration_title),
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.label_profile),
                        color = TextSecondary,
                        modifier = Modifier.width(140.dp)
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VividEdgeColorEngine.AmbilightProfile.entries.forEach { profile ->
                            TvFocusableButton(
                                text = profile.asDisplayString(),
                                selected = state.profile == profile,
                                onClick = { onAction(DashboardAction.SelectProfile(profile)) }
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.label_brightness_format, state.brightnessPercent),
                        color = TextSecondary,
                        modifier = Modifier.width(140.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvFocusableButton(
                            text = stringResource(R.string.btn_brightness_decrease),
                            modifier = Modifier.semantics { contentDescription = brightnessDecreaseDescription },
                            onClick = { onAction(DashboardAction.ChangeBrightness(state.brightnessPercent - 10)) }
                        )
                        TvFocusableButton(
                            text = stringResource(R.string.btn_brightness_increase),
                            modifier = Modifier.semantics { contentDescription = brightnessIncreaseDescription },
                            onClick = { onAction(DashboardAction.ChangeBrightness(state.brightnessPercent + 10)) }
                        )
                        TvFocusableButton(
                            text = stringResource(R.string.btn_brightness_max),
                            modifier = Modifier.semantics { contentDescription = brightnessMaxDescription },
                            selected = state.brightnessPercent == 100,
                            onClick = { onAction(DashboardAction.ChangeBrightness(100)) }
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.label_channel_order),
                        color = TextSecondary,
                        modifier = Modifier.width(140.dp)
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BleDobProtocol.ChannelOrder.entries.forEach { order ->
                            TvFocusableButton(
                                text = order.asDisplayString(),
                                selected = state.channelOrder == order,
                                onClick = { onAction(DashboardAction.SelectChannelOrder(order)) }
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.label_protocol_variant),
                        color = TextSecondary,
                        modifier = Modifier.width(140.dp)
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BleDobProtocol.ProtocolVariant.entries.forEach { variant ->
                            TvFocusableButton(
                                text = variant.asDisplayString(),
                                selected = state.protocolVariant == variant,
                                onClick = { onAction(DashboardAction.SelectProtocolVariant(variant)) }
                            )
                        }
                    }
                }

                val standbyLabel = if (state.standbySyncEnabled) {
                    stringResource(R.string.status_enabled)
                } else {
                    stringResource(R.string.status_disabled)
                }
                val drmLabel = if (state.drmFallbackEnabled) {
                    stringResource(R.string.status_enabled)
                } else {
                    stringResource(R.string.status_disabled)
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TvFocusableButton(
                        text = stringResource(R.string.btn_standby_sync_format, standbyLabel),
                        selected = state.standbySyncEnabled,
                        onClick = { onAction(DashboardAction.ToggleStandbySync) }
                    )
                    TvFocusableButton(
                        text = stringResource(R.string.btn_drm_fallback_format, drmLabel),
                        selected = state.drmFallbackEnabled,
                        onClick = { onAction(DashboardAction.ToggleDrmFallback) }
                    )
                }
            }
        }
    }
}

/**
 * Backward-compatible overload for legacy callback invocations.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AmbilightTvDashboard(
    state: AmbilightUiState,
    onStartAmbilight: () -> Unit,
    onStopAmbilight: () -> Unit,
    onMinimizeToBackground: () -> Unit,
    onConnectBle: () -> Unit,
    onScanBle: () -> Unit,
    onSaveMacAddress: (String) -> Unit,
    onPowerOn: () -> Unit,
    onPowerOff: () -> Unit,
    onTestColor: (Int, Int, Int) -> Unit,
    onSelectProfile: (VividEdgeColorEngine.AmbilightProfile) -> Unit,
    onSelectChannelOrder: (BleDobProtocol.ChannelOrder) -> Unit,
    onSelectVariant: (BleDobProtocol.ProtocolVariant) -> Unit,
    onChangeBrightness: (Int) -> Unit,
    onToggleStandbySync: () -> Unit,
    onToggleDrmFallback: () -> Unit
) {
    AmbilightTvDashboard(
        state = state,
        onAction = { action ->
            when (action) {
                DashboardAction.StartAmbilight -> onStartAmbilight()
                DashboardAction.StopAmbilight -> onStopAmbilight()
                DashboardAction.MinimizeToBackground -> onMinimizeToBackground()
                DashboardAction.ConnectBle -> onConnectBle()
                DashboardAction.ScanBle -> onScanBle()
                is DashboardAction.SaveMacAddress -> onSaveMacAddress(action.mac)
                DashboardAction.PowerOn -> onPowerOn()
                DashboardAction.PowerOff -> onPowerOff()
                is DashboardAction.TestColor -> onTestColor(action.color.r, action.color.g, action.color.b)
                is DashboardAction.SelectProfile -> onSelectProfile(action.profile)
                is DashboardAction.SelectChannelOrder -> onSelectChannelOrder(action.order)
                is DashboardAction.SelectProtocolVariant -> onSelectVariant(action.variant)
                is DashboardAction.ChangeBrightness -> onChangeBrightness(action.percent)
                DashboardAction.ToggleStandbySync -> onToggleStandbySync()
                DashboardAction.ToggleDrmFallback -> onToggleDrmFallback()
            }
        }
    )
}

/**
 * Android TV D-Pad optimized focusable button using canonical MutableInteractionSource.
 */
@Composable
fun TvFocusableButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val borderStroke = when {
        isFocused -> BorderStroke(3.dp, AccentCyan)
        selected -> BorderStroke(2.dp, StatusConnected)
        else -> BorderStroke(1.dp, BorderDefault)
    }

    val containerColor = when {
        primary && isFocused -> AccentCyan
        primary -> PrimaryAction
        selected && isFocused -> SurfaceSelectedFocused
        selected -> SurfaceSelected
        isFocused -> SurfaceFocused
        else -> SurfaceElevated
    }

    val contentColor = when {
        primary && isFocused -> OnAccentDark
        primary -> OnAccentDark
        else -> TextPrimary
    }

    if (primary) {
        Button(
            onClick = onClick,
            border = borderStroke,
            interactionSource = interactionSource,
            colors = ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor
            ),
            modifier = modifier
        ) {
            Text(text = text, fontWeight = FontWeight.Bold)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            border = borderStroke,
            interactionSource = interactionSource,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = containerColor,
                contentColor = contentColor
            ),
            modifier = modifier
        ) {
            Text(
                text = text,
                fontWeight = if (selected || isFocused) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}
