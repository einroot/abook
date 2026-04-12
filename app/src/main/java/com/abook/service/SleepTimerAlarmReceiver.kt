package com.abook.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class SleepTimerAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val durationMinutes = intent.getIntExtra(EXTRA_DURATION_MINUTES, 30)
        val fadeOutEnabled = intent.getBooleanExtra(EXTRA_FADE_OUT, true)

        val serviceIntent = Intent(context, TtsPlaybackService::class.java).apply {
            action = TtsPlaybackService.ACTION_START_SLEEP_TIMER
            putExtra(EXTRA_DURATION_MINUTES, durationMinutes)
            putExtra(EXTRA_FADE_OUT, fadeOutEnabled)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    companion object {
        const val EXTRA_DURATION_MINUTES = "duration_minutes"
        const val EXTRA_FADE_OUT = "fade_out"
        private const val REQUEST_CODE = 1001

        fun scheduleAlarm(
            context: Context,
            triggerAtMillis: Long,
            durationMinutes: Int,
            fadeOut: Boolean
        ) {
            val intent = Intent(context, SleepTimerAlarmReceiver::class.java).apply {
                putExtra(EXTRA_DURATION_MINUTES, durationMinutes)
                putExtra(EXTRA_FADE_OUT, fadeOut)
            }
            val pi = PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (am.canScheduleExactAlarms()) {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                    } else {
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                    }
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                }
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        }

        fun cancelAlarm(context: Context) {
            val intent = Intent(context, SleepTimerAlarmReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pi)
        }
    }
}
