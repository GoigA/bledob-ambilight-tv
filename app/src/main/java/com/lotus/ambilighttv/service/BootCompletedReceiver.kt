package com.lotus.ambilighttv.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.lotus.ambilighttv.state.AmbilightStateRepository

/**
 * Automatically reconnects and turns on the BLEDOB LED strip when Android TV boots up.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            AmbilightStateRepository.loadPreferences(context)
            if (AmbilightStateRepository.state.value.standbySyncEnabled) {
                val serviceIntent = Intent(context, AmbilightForegroundService::class.java).apply {
                    this.action = AmbilightForegroundService.ACTION_POWER_ON
                }
                runCatching {
                    ContextCompat.startForegroundService(context, serviceIntent)
                }
            }
        }
    }
}
