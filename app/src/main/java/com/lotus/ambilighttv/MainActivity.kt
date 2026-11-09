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
import androidx.compose.foundation.focusable
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.lotus.ambilighttv.ble.BleDobProtocol
import com.lotus.ambilighttv.engine.VividEdgeColorEngine
import com.lotus.ambilighttv.service.AmbilightForegroundService
import com.lotus.ambilighttv.state.AmbilightStateRepository
import com.lotus.ambilighttv.state.AmbilightUiState
import com.lotus.ambilighttv.state.BleConnectionStatus

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
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E5FF),
                    secondary = Color(0xFFFFB300),
                    background = Color(0xFF0B0E14),
                    surface = Color(0xFF151A24)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AmbilightTvDashboard(
                        state = uiState,
                        onStartAmbilight = { requestScreenCapture() },
                        onStopAmbilight = { sendServiceAction(AmbilightForegroundService.ACTION_STOP_SERVICE) },
                        onMinimizeToBackground = { moveTaskToBack(true) },
                        onConnectBle = {
                            ensureRuntimePermissionsAndConnect()
                            sendServiceAction(AmbilightForegroundService.ACTION_CONNECT_BLE)
                        },
                        onScanBle = {
                            ensureRuntimePermissionsAndConnect()
                            sendServiceAction(AmbilightForegroundService.ACTION_SCAN_BLE)
                        },
                        onSaveMacAddress = { newMac ->
                            AmbilightStateRepository.updateSettings(
                                this,
                                targetMacAddress = newMac
                            )
                            sendServiceAction(AmbilightForegroundService.ACTION_CONNECT_BLE)
                        },
                        onPowerOn = { sendServiceAction(AmbilightForegroundService.ACTION_POWER_ON) },
                        onPowerOff = { sendServiceAction(AmbilightForegroundService.ACTION_POWER_OFF) },
                        onTestColor = { r, g, b -> sendTestColor(r, g, b) },
                        onSelectProfile = { profile ->
                            AmbilightStateRepository.updateSettings(this, profile = profile)
                        },
                        onSelectChannelOrder = { order ->
                            AmbilightStateRepository.updateSettings(this, channelOrder = order)
                        },
                        onSelectVariant = { variant ->
                            AmbilightStateRepository.updateSettings(this, protocolVariant = variant)
                        },
                        onChangeBrightness = { newPercent ->
                            AmbilightStateRepository.updateSettings(this, brightnessPercent = newPercent)
                        },
                        onToggleStandbySync = {
                            AmbilightStateRepository.updateSettings(
                                this,
                                standbySyncEnabled = !uiState.standbySyncEnabled
                            )
                        },
                        onToggleDrmFallback = {
                            AmbilightStateRepository.updateSettings(
                                this,
                                drmFallbackEnabled = !uiState.drmFallbackEnabled
                            )
                        }
                    )
                }
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

    private fun sendTestColor(r: Int, g: Int, b: Int) {
        val intent = Intent(this, AmbilightForegroundService::class.java).apply {
            action = AmbilightForegroundService.ACTION_TEST_COLOR
            putExtra(AmbilightForegroundService.EXTRA_TEST_R, r)
            putExtra(AmbilightForegroundService.EXTRA_TEST_G, g)
            putExtra(AmbilightForegroundService.EXTRA_TEST_B, b)
        }
        ContextCompat.startForegroundService(this, intent)
    }
}

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
    val liveColor = Color(state.currentR, state.currentG, state.currentB)
    val scrollState = rememberScrollState()
    var macInput by remember { mutableStateOf(state.targetMacAddress) }

    LaunchedEffect(state.targetMacAddress) {
        macInput = state.targetMacAddress
    }

    Column(
        modifier = Modifier
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
                        listOf(liveColor.copy(alpha = 0.85f), Color(0xFF00E5FF), liveColor.copy(alpha = 0.85f))
                    ),
                    shape = RoundedCornerShape(16.dp)
                ),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF151A24))
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
                        text = "BLEDOB Ambilight TV",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Dispositivo: ${state.connectedDeviceName} (${state.targetMacAddress}) | ${state.bleStatus.label}",
                        color = if (state.bleStatus == BleConnectionStatus.CONNECTED) Color(0xFF4CAF50) else Color(0xFFFFB300),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Status: ${state.mode.label} | ${state.fps} FPS | Hex: ${state.lastHexPacket}",
                        color = Color(0xFFB0BEC5),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(liveColor, CircleShape)
                        .border(2.dp, Color.White, CircleShape)
                )
            }
        }

        // Primary Action Row (Start / Stop / Background)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF151A24))
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "1. Controle do Servico em Background",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TvFocusableButton(
                        text = if (state.isServiceRunning) "Reiniciar Captura Ambilight" else "Iniciar Ambilight em Background",
                        primary = true,
                        onClick = onStartAmbilight
                    )
                    if (state.isServiceRunning) {
                        TvFocusableButton(
                            text = "Minimizar (Ir para TV mantendo LEDs ativos)",
                            primary = false,
                            onClick = onMinimizeToBackground
                        )
                        TvFocusableButton(
                            text = "Parar Ambilight e Desligar Fita",
                            primary = false,
                            onClick = onStopAmbilight
                        )
                    }
                }
            }
        }

        // BLE Configuration, Auto-Scan & Hardware Test Row
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF151A24))
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "2. Configurar Fita Bluetooth (MAC Address ou Busca Automatica)",
                    color = Color.White,
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
                        label = { Text("MAC Address da Fita (ex: BE:27:08:00:49:E2)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color(0xFF455A64),
                            focusedLabelColor = Color(0xFF00E5FF),
                            unfocusedLabelColor = Color(0xFFB0BEC5)
                        ),
                        modifier = Modifier.width(340.dp)
                    )
                    TvFocusableButton(
                        text = "Salvar MAC e Conectar",
                        onClick = { onSaveMacAddress(macInput) }
                    )
                    TvFocusableButton(
                        text = "Buscar Fita Automaticamente (Scan BLE)",
                        onClick = onScanBle
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TvFocusableButton(text = "Reconectar BLE", onClick = onConnectBle)
                    TvFocusableButton(text = "Ligar Fita (ON)", onClick = onPowerOn)
                    TvFocusableButton(text = "Desligar Fita (OFF)", onClick = onPowerOff)
                    TvFocusableButton(text = "Vermelho", onClick = { onTestColor(255, 0, 0) })
                    TvFocusableButton(text = "Verde", onClick = { onTestColor(0, 255, 0) })
                    TvFocusableButton(text = "Azul", onClick = { onTestColor(0, 0, 255) })
                    TvFocusableButton(text = "Ambar Cinema", onClick = { onTestColor(255, 140, 40) })
                }
            }
        }

        // Profile + Brightness + Calibration
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF151A24))
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "3. Perfil de Cores, Calibracao de Canais e Automacao",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Perfil:",
                        color = Color(0xFFB0BEC5),
                        modifier = Modifier.width(140.dp)
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VividEdgeColorEngine.AmbilightProfile.entries.forEach { profile ->
                            TvFocusableButton(
                                text = profile.label,
                                selected = state.profile == profile,
                                onClick = { onSelectProfile(profile) }
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Brilho (${state.brightnessPercent}%):",
                        color = Color(0xFFB0BEC5),
                        modifier = Modifier.width(140.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TvFocusableButton(
                            text = "- 10%",
                            onClick = { onChangeBrightness(state.brightnessPercent - 10) }
                        )
                        TvFocusableButton(
                            text = "+ 10%",
                            onClick = { onChangeBrightness(state.brightnessPercent + 10) }
                        )
                        TvFocusableButton(
                            text = "100% Max",
                            selected = state.brightnessPercent == 100,
                            onClick = { onChangeBrightness(100) }
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Ordem RGB:",
                        color = Color(0xFFB0BEC5),
                        modifier = Modifier.width(140.dp)
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BleDobProtocol.ChannelOrder.entries.forEach { order ->
                            TvFocusableButton(
                                text = order.label,
                                selected = state.channelOrder == order,
                                onClick = { onSelectChannelOrder(order) }
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Protocolo:",
                        color = Color(0xFFB0BEC5),
                        modifier = Modifier.width(140.dp)
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BleDobProtocol.ProtocolVariant.entries.forEach { variant ->
                            TvFocusableButton(
                                text = variant.label,
                                selected = state.protocolVariant == variant,
                                onClick = { onSelectVariant(variant) }
                            )
                        }
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TvFocusableButton(
                        text = "Standby Sync TV (Auto ON/OFF): ${if (state.standbySyncEnabled) "ATIVADO" else "DESATIVADO"}",
                        selected = state.standbySyncEnabled,
                        onClick = onToggleStandbySync
                    )
                    TvFocusableButton(
                        text = "Fallback Audio DRM (Netflix/Prime): ${if (state.drmFallbackEnabled) "ATIVADO" else "DESATIVADO"}",
                        selected = state.drmFallbackEnabled,
                        onClick = onToggleDrmFallback
                    )
                }
            }
        }
    }
}

@Composable
fun TvFocusableButton(
    text: String,
    primary: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    val borderStroke = when {
        isFocused -> BorderStroke(3.dp, Color(0xFF00E5FF))
        selected -> BorderStroke(2.dp, Color(0xFF4CAF50))
        else -> BorderStroke(1.dp, Color(0xFF37474F))
    }

    val containerColor = when {
        primary -> Color(0xFF00B8D4)
        selected -> Color(0xFF1B5E20)
        isFocused -> Color(0xFF263238)
        else -> Color(0xFF1E2736)
    }

    val contentColor = if (primary) Color(0xFF051014) else Color.White

    if (primary) {
        Button(
            onClick = onClick,
            border = borderStroke,
            colors = ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor
            ),
            modifier = Modifier
                .onFocusChanged { isFocused = it.isFocused }
                .focusable()
        ) {
            Text(text = text, fontWeight = FontWeight.Bold)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            border = borderStroke,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = containerColor,
                contentColor = contentColor
            ),
            modifier = Modifier
                .onFocusChanged { isFocused = it.isFocused }
                .focusable()
        ) {
            Text(text = text, fontWeight = if (selected || isFocused) FontWeight.Bold else FontWeight.Normal)
        }
    }
}
