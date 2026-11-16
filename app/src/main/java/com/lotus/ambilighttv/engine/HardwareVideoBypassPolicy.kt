package com.lotus.ambilighttv.engine

/**
 * Decides when the `VirtualDisplay` surface must be detached (`surface = null`)
 * to prevent SurfaceFlinger OpenGL composition from triggering software `MFC` detiling
 * inside MStar/MediaTek SoCs (`mstar.hardware.mmdisp@1.0-service`) during hardware video playback.
 */
class HardwareVideoBypassPolicy(
    private val cooldownMs: Long = DEFAULT_BYPASS_COOLDOWN_MS
) {
    private var hasTriggered: Boolean = false
    private var lastTriggerTimestampMs: Long = 0L

    /**
     * Returns true if the given [packageName] is a known streaming or hardware video player app.
     */
    fun isKnownHardwareVideoPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return packageName in KNOWN_HARDWARE_VIDEO_PACKAGES ||
            packageName.startsWith("com.google.android.youtube")
    }

    /**
     * Evaluates whether the `VirtualDisplay` surface should currently be detached,
     * updating the internal cooldown timestamp when hardware playback or a video app is active.
     */
    @Synchronized
    fun evaluateBypass(
        hasActiveMediaPlayback: Boolean,
        foregroundPackage: String?,
        nowElapsedRealtimeMs: Long
    ): Boolean {
        if (hasActiveMediaPlayback || isKnownHardwareVideoPackage(foregroundPackage)) {
            hasTriggered = true
            lastTriggerTimestampMs = nowElapsedRealtimeMs
            return true
        }

        if (hasTriggered && cooldownMs > 0L && (nowElapsedRealtimeMs - lastTriggerTimestampMs) < cooldownMs) {
            return true
        }

        return false
    }

    /**
     * Clears any retained trigger/cooldown state.
     */
    @Synchronized
    fun reset() {
        hasTriggered = false
        lastTriggerTimestampMs = 0L
    }

    companion object {
        const val DEFAULT_BYPASS_COOLDOWN_MS = 4_000L

        val KNOWN_HARDWARE_VIDEO_PACKAGES: Set<String> = setOf(
            "com.google.android.youtube.tv",
            "com.google.android.youtube.tvkids",
            "com.google.android.youtube",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus",
            "com.globo.globotv",
            "com.hbo.hbonow",
            "com.wbd.stream",
            "tv.twitch.android.app",
            "org.xbmc.kodi",
            "org.videolan.vlc",
            "com.plexapp.android",
            "com.apple.atve.androidtv.appletv",
            "com.crunchyroll.crunchyroid",
            "com.starplus.android"
        )
    }
}
