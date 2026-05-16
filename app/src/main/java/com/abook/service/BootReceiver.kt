package com.abook.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restores scheduled sleep timer alarms after device reboot.
 * Sleep timer state is stored in DataStore and re-checked by the service when it starts.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            val serviceIntent = Intent(context, TtsPlaybackService::class.java).apply {
                action = TtsPlaybackService.ACTION_RESTORE_SLEEP_TIMER
            }
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (_: Exception) {
                // Boot-time foreground service starts can be restricted by OEMs.
                // The timer is still restored when the app/service is opened later.
            }
        }
    }
}
