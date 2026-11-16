package com.lotus.ambilighttv.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.lotus.ambilighttv.MainActivity
import com.lotus.ambilighttv.ble.BleDobGattClient
import com.lotus.ambilighttv.engine.AudioFallbackEngine
import com.lotus.ambilighttv.engine.HardwareVideoBypassPolicy
import com.lotus.ambilighttv.engine.VividEdgeColorEngine
import com.lotus.ambilighttv.state.AmbilightMode
import com.lotus.ambilighttv.state.AmbilightStateRepository
import com.lotus.ambilighttv.state.AmbilightUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AmbilightForegroundService : Service() {

    companion object {
        const val ACTION_START_PROJECTION = "com.lotus.ambilighttv.action.START_PROJECTION"
        const val ACTION_STOP_SERVICE = "com.lotus.ambilighttv.action.STOP_SERVICE"
        const val ACTION_CONNECT_BLE = "com.lotus.ambilighttv.action.CONNECT_BLE"
        const val ACTION_SCAN_BLE = "com.lotus.ambilighttv.action.SCAN_BLE"
        const val ACTION_TEST_COLOR = "com.lotus.ambilighttv.action.TEST_COLOR"
        const val ACTION_POWER_ON = "com.lotus.ambilighttv.action.POWER_ON"
        const val ACTION_POWER_OFF = "com.lotus.ambilighttv.action.POWER_OFF"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_TEST_R = "extra_test_r"
        const val EXTRA_TEST_G = "extra_test_g"
        const val EXTRA_TEST_B = "extra_test_b"

        private const val NOTIFICATION_CHANNEL_ID = "lotus_ambilight_channel"
        private const val NOTIFICATION_ID = 1001
        private const val CAPTURE_WIDTH = 32
        private const val CAPTURE_HEIGHT = 18
        private const val MAX_TV_SAFE_CAPTURE_FPS = 15
        private const val IMAGE_READER_MAX_IMAGES = 3
        private const val USAGE_STATS_QUERY_INTERVAL_MS = 500L
        private const val USAGE_STATS_INITIAL_LOOKBACK_MS = 3_600_000L
        private const val USAGE_STATS_OVERLAP_MS = 1_000L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var bleClient: BleDobGattClient
    private val colorEngine = VividEdgeColorEngine()
    private val hardwareVideoBypassPolicy = HardwareVideoBypassPolicy()
    private val audioFallbackEngine = AudioFallbackEngine(
        com.lotus.ambilighttv.engine.AudioEngineConfig(enableHardwareVisualizer = false)
    )

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var captureLoopJob: Job? = null
    private var audioPlaybackCallback: AudioManager.AudioPlaybackCallback? = null

    private val surfaceLock = Any()
    private val frameBuffer = IntArray(CAPTURE_WIDTH * CAPTURE_HEIGHT)
    private var rawPlaneBytes = ByteArray(CAPTURE_WIDTH * CAPTURE_HEIGHT * 4)
    @Volatile
    private var isTvScreenOff = false
    @Volatile
    private var isVirtualSurfaceAttached = false
    @Volatile
    private var cachedForegroundPackage: String? = null
    private var lastUsageStatsQueryElapsedMs = 0L
    private var lastUsageStatsWallClockMs = 0L
    private var frameCounter = 0
    private var lastFpsTimestampMs = 0L

    private val screenPowerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val standbySync = AmbilightStateRepository.state.value.standbySyncEnabled
            if (!standbySync) return

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isTvScreenOff = true
                    detachVirtualDisplaySurface()
                    AmbilightStateRepository.updateRuntimeState {
                        it.copy(mode = AmbilightMode.TV_STANDBY, fps = 0)
                    }
                    audioFallbackEngine.stop()
                    bleClient.powerOffAndDisconnectForStandby()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isTvScreenOff = false
                    if (!shouldBypassVirtualDisplay()) {
                        attachVirtualDisplaySurface()
                    }
                    if (mediaProjection != null) {
                        val activeMode = if (isVirtualSurfaceAttached) {
                            AmbilightMode.SCREEN_SYNC
                        } else {
                            AmbilightMode.DRM_AUDIO_FALLBACK
                        }
                        AmbilightStateRepository.updateRuntimeState {
                            it.copy(mode = activeMode)
                        }
                    } else {
                        AmbilightStateRepository.updateRuntimeState {
                            it.copy(mode = AmbilightMode.STOPPED)
                        }
                    }
                    bleClient.connect()
                    bleClient.sendPowerOn()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AmbilightStateRepository.loadPreferences(this)
        bleClient = BleDobGattClient(applicationContext)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenPowerReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PROJECTION -> {
                promoteToForeground(includeMediaProjection = true)
                bleClient.connect()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultData != null) {
                    startMediaProjectionCapture(resultCode, resultData)
                }
            }
            ACTION_CONNECT_BLE -> {
                promoteToForeground(includeMediaProjection = mediaProjection != null)
                bleClient.connect()
            }
            ACTION_SCAN_BLE -> {
                promoteToForeground(includeMediaProjection = mediaProjection != null)
                bleClient.startBleScan()
            }
            ACTION_TEST_COLOR -> {
                promoteToForeground(includeMediaProjection = mediaProjection != null)
                bleClient.connect()
                val r = intent.getIntExtra(EXTRA_TEST_R, 255)
                val g = intent.getIntExtra(EXTRA_TEST_G, 0)
                val b = intent.getIntExtra(EXTRA_TEST_B, 0)
                bleClient.sendPowerOn()
                bleClient.sendColor(r, g, b, force = true)
            }
            ACTION_POWER_ON -> {
                promoteToForeground(includeMediaProjection = mediaProjection != null)
                bleClient.connect()
                bleClient.sendPowerOn()
            }
            ACTION_POWER_OFF -> {
                promoteToForeground(includeMediaProjection = mediaProjection != null)
                bleClient.sendPowerOff()
            }
            ACTION_STOP_SERVICE -> {
                bleClient.sendPowerOff()
                stopProjectionAndService()
            }
        }
        return START_STICKY
    }

    private fun promoteToForeground(includeMediaProjection: Boolean) {
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val fgsType = if (includeMediaProjection) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(NOTIFICATION_ID, notification, fgsType)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgsType = if (includeMediaProjection) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                0
            }
            if (fgsType != 0) {
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startMediaProjectionCapture(resultCode: Int, resultData: Intent) {
        stopCaptureResourcesOnly()

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData) ?: return
        mediaProjection = projection

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCaptureResourcesOnly()
                AmbilightStateRepository.updateRuntimeState {
                    it.copy(isServiceRunning = false, mode = AmbilightMode.STOPPED, fps = 0)
                }
            }
        }, null)

        val thread = HandlerThread("LotusAmbilightCapture", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        captureThread = thread
        val handler = Handler(thread.looper)
        captureHandler = handler

        val reader = ImageReader.newInstance(
            CAPTURE_WIDTH,
            CAPTURE_HEIGHT,
            PixelFormat.RGBA_8888,
            IMAGE_READER_MAX_IMAGES
        )
        imageReader = reader

        hardwareVideoBypassPolicy.reset()
        lastUsageStatsQueryElapsedMs = 0L
        lastUsageStatsWallClockMs = 0L
        cachedForegroundPackage = null

        val startInBypass = shouldBypassVirtualDisplay()
        virtualDisplay = projection.createVirtualDisplay(
            "LotusAmbilightVirtualDisplay",
            CAPTURE_WIDTH,
            CAPTURE_HEIGHT,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            if (startInBypass) null else reader.surface,
            null,
            handler
        )
        synchronized(surfaceLock) {
            isVirtualSurfaceAttached = !startInBypass
        }

        registerAudioPlaybackWatcher(handler)

        colorEngine.reset()
        if (startInBypass) {
            audioFallbackEngine.start()
        }
        AmbilightStateRepository.updateRuntimeState {
            it.copy(
                isServiceRunning = true,
                mode = if (startInBypass) AmbilightMode.DRM_AUDIO_FALLBACK else AmbilightMode.SCREEN_SYNC
            )
        }

        captureLoopJob = serviceScope.launch {
            lastFpsTimestampMs = SystemClock.elapsedRealtime()
            frameCounter = 0

            while (isActive) {
                val currentState = AmbilightStateRepository.state.value
                colorEngine.profile = currentState.profile
                val effectiveFps = currentState.profile.targetFps.coerceIn(10, MAX_TV_SAFE_CAPTURE_FPS)
                val frameDelayMs = 1000L / effectiveFps

                if (isTvScreenOff) {
                    detachVirtualDisplaySurface()
                    delay(500L)
                    continue
                }

                if (shouldBypassVirtualDisplay()) {
                    detachVirtualDisplaySurface()
                    audioFallbackEngine.start()
                    val fallbackRgb = audioFallbackEngine.nextAmbientRgb()
                    bleClient.sendColor(fallbackRgb)
                    updateFpsAndMode(AmbilightMode.DRM_AUDIO_FALLBACK)
                    delay(frameDelayMs)
                    continue
                }

                attachVirtualDisplaySurface()

                val image = try {
                    reader.acquireLatestImage()
                } catch (_: Throwable) {
                    null
                }

                if (image != null) {
                    val extracted = extractPixelsAndCloseImage(image, frameBuffer)
                    if (extracted) {
                        val analysis = colorEngine.processFrame(
                            argbPixels = frameBuffer,
                            width = CAPTURE_WIDTH,
                            height = CAPTURE_HEIGHT
                        )

                        if (analysis.isDrmFallbackActive && currentState.drmFallbackEnabled) {
                            audioFallbackEngine.start()
                            val fallbackRgb = audioFallbackEngine.nextAmbientRgb()
                            bleClient.sendColor(fallbackRgb)
                            updateFpsAndMode(AmbilightMode.DRM_AUDIO_FALLBACK)
                        } else {
                            audioFallbackEngine.stop()
                            bleClient.sendColor(analysis.color)
                            updateFpsAndMode(AmbilightMode.SCREEN_SYNC)
                        }
                    }
                } else if (currentState.mode == AmbilightMode.DRM_AUDIO_FALLBACK && currentState.drmFallbackEnabled) {
                    val fallbackRgb = audioFallbackEngine.nextAmbientRgb()
                    bleClient.sendColor(fallbackRgb)
                    updateFpsAndMode(AmbilightMode.DRM_AUDIO_FALLBACK)
                }

                delay(frameDelayMs)
            }
        }
    }

    private fun registerAudioPlaybackWatcher(handler: Handler) {
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                val hasActiveMedia = configs?.any { isMediaPlaybackConfig(it) } == true
                if (hasActiveMedia) {
                    hardwareVideoBypassPolicy.evaluateBypass(
                        hasActiveMediaPlayback = true,
                        foregroundPackage = cachedForegroundPackage,
                        nowElapsedRealtimeMs = SystemClock.elapsedRealtime()
                    )
                    detachVirtualDisplaySurface()
                }
            }
        }
        audioPlaybackCallback = callback
        runCatching { audioManager.registerAudioPlaybackCallback(callback, handler) }
    }

    private fun unregisterAudioPlaybackWatcher() {
        val callback = audioPlaybackCallback ?: return
        audioPlaybackCallback = null
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        runCatching { audioManager.unregisterAudioPlaybackCallback(callback) }
    }

    private fun attachVirtualDisplaySurface() {
        val surface = imageReader?.surface ?: return
        synchronized(surfaceLock) {
            if (!isVirtualSurfaceAttached && !isTvScreenOff) {
                runCatching {
                    virtualDisplay?.surface = surface
                    isVirtualSurfaceAttached = true
                }
            }
        }
    }

    private fun detachVirtualDisplaySurface() {
        synchronized(surfaceLock) {
            if (isVirtualSurfaceAttached) {
                runCatching { virtualDisplay?.surface = null }
                isVirtualSurfaceAttached = false
            }
        }
        runCatching {
            while (true) {
                val pending = imageReader?.acquireLatestImage() ?: break
                pending.close()
            }
        }
    }

    private fun isMediaPlaybackConfig(config: AudioPlaybackConfiguration): Boolean {
        val attr = config.audioAttributes ?: return false
        val isMediaUsage = attr.usage == AudioAttributes.USAGE_MEDIA ||
            attr.usage == AudioAttributes.USAGE_GAME ||
            attr.usage == AudioAttributes.USAGE_UNKNOWN
        val isSonification = attr.contentType == AudioAttributes.CONTENT_TYPE_SONIFICATION
        return isMediaUsage && !isSonification
    }

    private fun isActiveMediaPlaybackDetected(): Boolean {
        val audioManager = getSystemService(AudioManager::class.java) ?: return false
        val configs = runCatching { audioManager.activePlaybackConfigurations }.getOrDefault(emptyList())
        val hasActiveMediaConfig = configs.any { isMediaPlaybackConfig(it) }
        return hasActiveMediaConfig || runCatching { audioManager.isMusicActive }.getOrDefault(false)
    }

    private fun resolveForegroundPackage(nowElapsedMs: Long): String? {
        if (lastUsageStatsQueryElapsedMs != 0L &&
            (nowElapsedMs - lastUsageStatsQueryElapsedMs) < USAGE_STATS_QUERY_INTERVAL_MS
        ) {
            return cachedForegroundPackage
        }
        lastUsageStatsQueryElapsedMs = nowElapsedMs

        val usageStatsManager = getSystemService(UsageStatsManager::class.java) ?: return cachedForegroundPackage
        val nowWallMs = System.currentTimeMillis()
        val beginWallMs = if (lastUsageStatsWallClockMs == 0L) {
            nowWallMs - USAGE_STATS_INITIAL_LOOKBACK_MS
        } else {
            (lastUsageStatsWallClockMs - USAGE_STATS_OVERLAP_MS).coerceAtLeast(0L)
        }
        lastUsageStatsWallClockMs = nowWallMs

        val resumedEventType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_FOREGROUND
        }

        runCatching {
            val events = usageStatsManager.queryEvents(beginWallMs, nowWallMs)
            val event = UsageEvents.Event()
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == resumedEventType && !event.packageName.isNullOrBlank()) {
                    cachedForegroundPackage = event.packageName
                }
            }
        }
        return cachedForegroundPackage
    }

    private fun shouldBypassVirtualDisplay(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean {
        val hasMediaPlayback = isActiveMediaPlaybackDetected()
        val foregroundPackage = resolveForegroundPackage(nowElapsedMs)
        return hardwareVideoBypassPolicy.evaluateBypass(
            hasActiveMediaPlayback = hasMediaPlayback,
            foregroundPackage = foregroundPackage,
            nowElapsedRealtimeMs = nowElapsedMs
        )
    }

    private fun updateFpsAndMode(activeMode: AmbilightMode) {
        frameCounter++
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastFpsTimestampMs
        if (elapsed >= 1000L) {
            val measuredFps = ((frameCounter * 1000L) / elapsed).toInt()
            frameCounter = 0
            lastFpsTimestampMs = now
            AmbilightStateRepository.updateRuntimeState {
                it.copy(mode = activeMode, fps = measuredFps)
            }
        } else if (AmbilightStateRepository.state.value.mode != activeMode) {
            AmbilightStateRepository.updateRuntimeState {
                it.copy(mode = activeMode)
            }
        }
    }

    private fun extractPixelsAndCloseImage(image: Image, outArgb: IntArray): Boolean {
        val width: Int
        val height: Int
        val pixelStride: Int
        val rowStride: Int
        try {
            val plane = image.planes.firstOrNull() ?: return false
            val buffer = plane.buffer
            pixelStride = plane.pixelStride
            rowStride = plane.rowStride
            width = image.width.coerceAtMost(CAPTURE_WIDTH)
            height = image.height.coerceAtMost(CAPTURE_HEIGHT)
            val requiredBytes = buffer.remaining()
            if (requiredBytes <= 0) return false
            if (rawPlaneBytes.size < requiredBytes) {
                rawPlaneBytes = ByteArray(requiredBytes)
            }
            buffer.get(rawPlaneBytes, 0, requiredBytes)
        } finally {
            runCatching { image.close() }
        }

        var outIndex = 0
        val maxLen = rawPlaneBytes.size
        for (y in 0 until height) {
            val rowStart = y * rowStride
            for (x in 0 until width) {
                val offset = rowStart + x * pixelStride
                if (offset + 2 < maxLen) {
                    val r = rawPlaneBytes[offset].toInt() and 0xFF
                    val g = rawPlaneBytes[offset + 1].toInt() and 0xFF
                    val b = rawPlaneBytes[offset + 2].toInt() and 0xFF
                    outArgb[outIndex++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                } else {
                    outArgb[outIndex++] = 0xFF000000.toInt()
                }
            }
        }
        return true
    }

    private fun stopCaptureResourcesOnly() {
        unregisterAudioPlaybackWatcher()
        captureLoopJob?.cancel()
        captureLoopJob = null
        audioFallbackEngine.stop()
        hardwareVideoBypassPolicy.reset()

        synchronized(surfaceLock) {
            isVirtualSurfaceAttached = false
        }

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching { imageReader?.close() }
        imageReader = null

        runCatching { mediaProjection?.stop() }
        mediaProjection = null

        runCatching { captureThread?.quitSafely() }
        captureThread = null
        captureHandler = null
    }

    private fun stopProjectionAndService() {
        stopCaptureResourcesOnly()
        AmbilightStateRepository.updateRuntimeState {
            it.copy(isServiceRunning = false, mode = AmbilightMode.STOPPED, fps = 0)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Lotus Ambilight TV Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém a sincronização Ambilight BLEDOB em segundo plano na Android TV"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AmbilightForegroundService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val state = AmbilightStateRepository.state.value
        val statusText = if (state.targetMacAddress.isNotBlank()) {
            val deviceLabel = if (state.connectedDeviceName.isNotBlank() &&
                state.connectedDeviceName != AmbilightUiState.DEFAULT_DEVICE_NAME
            ) {
                "${state.connectedDeviceName} (${state.targetMacAddress})"
            } else {
                state.targetMacAddress
            }
            when (state.mode) {
                AmbilightMode.SCREEN_SYNC -> "Sincronizando tela com $deviceLabel"
                AmbilightMode.DRM_AUDIO_FALLBACK -> "Modo áudio/ambiente ativo com $deviceLabel"
                AmbilightMode.TV_STANDBY -> "TV em espera: fita desligada ($deviceLabel)"
                AmbilightMode.STOPPED -> "Ambilight pronto ($deviceLabel)"
            }
        } else {
            "Aguardando conexão com fita LED (Nenhuma fita conectada)"
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Lotus Ambilight TV Ativo")
            .setContentText(statusText)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Parar Ambilight", pendingStop)
            .build()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenPowerReceiver) }
        stopCaptureResourcesOnly()
        bleClient.disconnectAndClose()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
