package com.lotus.ambilighttv.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareVideoBypassPolicyTest {

    @Test
    fun `active media playback triggers bypass immediately`() {
        val policy = HardwareVideoBypassPolicy(cooldownMs = 4_000L)

        val shouldBypass = policy.evaluateBypass(
            hasActiveMediaPlayback = true,
            foregroundPackage = null,
            nowElapsedRealtimeMs = 1_000L
        )

        assertTrue(shouldBypass)
    }

    @Test
    fun `known YouTube TV package triggers bypass even when media playback is silent or buffering`() {
        val policy = HardwareVideoBypassPolicy(cooldownMs = 4_000L)

        assertTrue(
            policy.evaluateBypass(
                hasActiveMediaPlayback = false,
                foregroundPackage = "com.google.android.youtube.tv",
                nowElapsedRealtimeMs = 1_000L
            )
        )
        assertTrue(policy.isKnownHardwareVideoPackage("com.google.android.youtube.tvkids"))
        assertTrue(policy.isKnownHardwareVideoPackage("com.google.android.youtube.unplugged"))
        assertTrue(policy.isKnownHardwareVideoPackage("com.netflix.ninja"))
    }

    @Test
    fun `launcher without active media playback does not trigger bypass`() {
        val policy = HardwareVideoBypassPolicy(cooldownMs = 4_000L)

        val shouldBypass = policy.evaluateBypass(
            hasActiveMediaPlayback = false,
            foregroundPackage = "com.google.android.apps.tv.launcherx",
            nowElapsedRealtimeMs = 1_000L
        )

        assertFalse(shouldBypass)
    }

    @Test
    fun `cooldown keeps bypass active for 4 seconds after pause or seek and releases afterward`() {
        val policy = HardwareVideoBypassPolicy(cooldownMs = 4_000L)

        assertTrue(
            policy.evaluateBypass(
                hasActiveMediaPlayback = true,
                foregroundPackage = null,
                nowElapsedRealtimeMs = 10_000L
            )
        )

        // Immediately after pause: within cooldown -> still bypassed
        assertTrue(
            policy.evaluateBypass(
                hasActiveMediaPlayback = false,
                foregroundPackage = "com.google.android.apps.tv.launcherx",
                nowElapsedRealtimeMs = 10_500L
            )
        )

        // At 13,999 ms (elapsed 3,999 ms < 4,000 ms): still bypassed
        assertTrue(
            policy.evaluateBypass(
                hasActiveMediaPlayback = false,
                foregroundPackage = "com.google.android.apps.tv.launcherx",
                nowElapsedRealtimeMs = 13_999L
            )
        )

        // At 14,000 ms (elapsed 4,000 ms >= cooldown): released back to normal screen sync
        assertFalse(
            policy.evaluateBypass(
                hasActiveMediaPlayback = false,
                foregroundPackage = "com.google.android.apps.tv.launcherx",
                nowElapsedRealtimeMs = 14_000L
            )
        )
    }

    @Test
    fun `reset clears retained cooldown state immediately`() {
        val policy = HardwareVideoBypassPolicy(cooldownMs = 4_000L)

        assertTrue(
            policy.evaluateBypass(
                hasActiveMediaPlayback = true,
                foregroundPackage = "com.google.android.youtube.tv",
                nowElapsedRealtimeMs = 5_000L
            )
        )

        policy.reset()

        assertFalse(
            policy.evaluateBypass(
                hasActiveMediaPlayback = false,
                foregroundPackage = "com.google.android.apps.tv.launcherx",
                nowElapsedRealtimeMs = 5_100L
            )
        )
    }
}
