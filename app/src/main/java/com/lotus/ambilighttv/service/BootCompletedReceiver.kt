package com.lotus.ambilighttv.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.lotus.ambilighttv.MainActivity
import com.lotus.ambilighttv.state.AmbilightStateRepository

/**
 * Automatically reconnects, turns on the BLEDOB LED strip, and resumes screen capture
 * when Android TV boots up from a cold power cycle.
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

                val activityIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("auto_start", true)
                }
                runCatching {
                    context.startActivity(activityIntent)
                }
            }
        }
    }
}
