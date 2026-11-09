package com.lotus.ambilighttv.engine

import android.media.audiofx.Visualizer
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Audio-reactive + Warm Ambient Fallback Engine when MediaProjection encounters
 * DRM-protected video (Widevine L1 / FLAG_SECURE on Netflix, Prime Video, Disney+).
 */
class AudioFallbackEngine {
    private var visualizer: Visualizer? = null
    private var latestBassEnergy: Float = 0f
    private var latestMidEnergy: Float = 0f
    private var phase: Double = 0.0

    fun start() {
        if (visualizer != null) return
        try {
            val v = Visualizer(0) // Audio session 0 = system output mix on Android TV
            v.captureSize = Visualizer.getCaptureSizeRange()[0].coerceAtLeast(128)
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) = Unit

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (fft == null || fft.size < 16) return
                        var bassSum = 0f
                        for (i in 2 until 8 step 2) {
                            val re = fft[i].toFloat()
                            val im = fft[i + 1].toFloat()
                            bassSum += hypot(re, im)
                        }
                        var midSum = 0f
                        for (i in 8 until 24.coerceAtMost(fft.size - 1) step 2) {
                            val re = fft[i].toFloat()
                            val im = fft[i + 1].toFloat()
                            midSum += hypot(re, im)
                        }
                        latestBassEnergy = (bassSum / (3f * 128f)).coerceIn(0f, 1f)
                        latestMidEnergy = (midSum / (8f * 128f)).coerceIn(0f, 1f)
                    }
                },
                Visualizer.getMaxCaptureRate() / 2,
                false,
                true
            )
            v.enabled = true
            visualizer = v
        } catch (_: Throwable) {
            // If RECORD_AUDIO is not granted or Visualizer session 0 is restricted,
            // fallback gracefully to warm cinema breathing mode.
            visualizer = null
        }
    }

    fun stop() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Throwable) {
        } finally {
            visualizer = null
            latestBassEnergy = 0f
            latestMidEnergy = 0f
        }
    }

    /**
     * Computes an ambient RGB frame from current audio energy (or warm cinema glow if quiet).
     */
    fun nextAmbientColor(): IntArray {
        phase += 0.06 + (latestBassEnergy * 0.18)
        val bass = latestBassEnergy
        val mid = latestMidEnergy

        return if (bass > 0.05f || mid > 0.05f) {
            // Audio-reactive warm/vivid cinema pulse
            val intensity = (0.35f + bass * 0.65f).coerceIn(0.25f, 1.0f)
            val r = ((180 + 75 * sin(phase * 0.7)) * intensity).roundToInt().coerceIn(20, 255)
            val g = ((90 + 110 * mid + 40 * cos(phase * 0.5)) * intensity).roundToInt().coerceIn(10, 255)
            val b = ((140 + 115 * bass) * intensity).roundToInt().coerceIn(15, 255)
            intArrayOf(r, g, b)
        } else {
            // Gentle Philips Lounge Warm White / Amber glow during quiet DRM scenes
            val breath = 0.85 + 0.15 * sin(phase * 0.4)
            intArrayOf(
                (210 * breath).roundToInt().coerceIn(0, 255),
                (115 * breath).roundToInt().coerceIn(0, 255),
                (45 * breath).roundToInt().coerceIn(0, 255)
            )
        }
    }
}
