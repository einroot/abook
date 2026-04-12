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
            // Nothing to do immediately; the timer is stored in DataStore
            // and SleepTimerManager.restoreTimerState() handles recovery.
        }
    }
}
