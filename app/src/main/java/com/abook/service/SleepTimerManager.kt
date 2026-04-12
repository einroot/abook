package com.abook.service

import android.app.NotificationManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.abook.domain.model.SleepTimerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.sqrt

private val Context.sleepTimerStore by preferencesDataStore(name = "sleep_timer")

class SleepTimerManager(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private var timerJob: Job? = null
    private var fadeJob: Job? = null
    private var shakeListener: SensorEventListener? = null
    private var originalVolume: Float = 1.0f

    // Configurable options
    var fadeOutDurationSeconds: Int = 120
    var vibrationWarningEnabled: Boolean = false
    var vibrationWarningSecondsBefore: Int = 60
    var dndEnabled: Boolean = false
    private var chaptersRemaining: Int = 0
    private var chapterMode: Boolean = false
    private var vibrationTriggered: Boolean = false

    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    var onVolumeChange: ((Float) -> Unit)? = null
    var onTimerExpired: (() -> Unit)? = null

    private companion object {
        const val SHAKE_THRESHOLD = 15f
        const val EXTEND_MINUTES = 15
        val KEY_TIMER_END = longPreferencesKey("timer_end_epoch")
        val KEY_FADE_DURATION = intPreferencesKey("fade_duration")
        val KEY_IS_ACTIVE = booleanPreferencesKey("is_active")
    }

    fun start(durationMinutes: Int, currentVolume: Float) {
        cancel()
        originalVolume = currentVolume
        chapterMode = false
        vibrationTriggered = false
        val totalSeconds = durationMinutes * 60

        _state.value = SleepTimerState(
            isActive = true,
            remainingSeconds = totalSeconds,
            isFadingOut = false
        )

        // Persist state
        scope.launch {
            context.sleepTimerStore.edit { prefs ->
                prefs[KEY_TIMER_END] = System.currentTimeMillis() + totalSeconds * 1000L
                prefs[KEY_FADE_DURATION] = fadeOutDurationSeconds
                prefs[KEY_IS_ACTIVE] = true
            }
        }

        timerJob = scope.launch {
            runTimer(totalSeconds)
        }

        registerShakeDetector()

        // Backup alarm for persistence across process kill
        SleepTimerAlarmReceiver.scheduleAlarm(
            context,
            System.currentTimeMillis() + durationMinutes * 60_000L,
            durationMinutes,
            true
        )
    }

    private suspend fun runTimer(initialSeconds: Int) {
        var remaining = initialSeconds
        while (remaining > 0) {
            delay(1000)
            remaining--
            val isFading = remaining <= fadeOutDurationSeconds

            _state.value = SleepTimerState(
                isActive = true,
                remainingSeconds = remaining,
                isFadingOut = isFading
            )

            if (isFading && fadeOutDurationSeconds > 0) {
                val fadeProgress = remaining.toFloat() / fadeOutDurationSeconds
                onVolumeChange?.invoke(originalVolume * fadeProgress)
            }

            // Vibration warning
            if (vibrationWarningEnabled && !vibrationTriggered &&
                remaining == vibrationWarningSecondsBefore
            ) {
                vibrationTriggered = true
                vibrate()
            }
        }

        _state.value = SleepTimerState()
        if (dndEnabled) enableDnd()
        context.sleepTimerStore.edit { prefs ->
            prefs[KEY_IS_ACTIVE] = false
        }
        onTimerExpired?.invoke()
    }

    fun startChapterTimer(chapters: Int) {
        cancel()
        chapterMode = true
        chaptersRemaining = chapters
        _state.value = SleepTimerState(isActive = true, remainingSeconds = -1)
    }

    fun notifyChapterCompleted() {
        if (!chapterMode) return
        chaptersRemaining--
        if (chaptersRemaining <= 0) {
            _state.value = SleepTimerState()
            if (dndEnabled) enableDnd()
            onTimerExpired?.invoke()
            chapterMode = false
        }
    }

    suspend fun restoreTimerState() {
        val prefs = context.sleepTimerStore.data.first()
        val isActive = prefs[KEY_IS_ACTIVE] ?: false
        if (!isActive) return
        val endEpoch = prefs[KEY_TIMER_END] ?: return
        val fadeDur = prefs[KEY_FADE_DURATION] ?: 120
        val remaining = ((endEpoch - System.currentTimeMillis()) / 1000).toInt()
        if (remaining <= 0) {
            context.sleepTimerStore.edit { it[KEY_IS_ACTIVE] = false }
            return
        }
        fadeOutDurationSeconds = fadeDur
        _state.value = SleepTimerState(
            isActive = true,
            remainingSeconds = remaining,
            isFadingOut = remaining <= fadeDur
        )
        timerJob = scope.launch { runTimer(remaining) }
        registerShakeDetector()
    }

    private fun vibrate() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                    .defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, 200, 100, 200, 100, 200),
                        -1
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 200, 100, 200, 100, 200), -1)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun enableDnd() {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun extend(minutes: Int = EXTEND_MINUTES) {
        val current = _state.value
        if (!current.isActive) return

        val newRemaining = current.remainingSeconds + minutes * 60
        vibrationTriggered = false

        if (current.isFadingOut) {
            onVolumeChange?.invoke(originalVolume)
        }

        _state.value = current.copy(
            remainingSeconds = newRemaining,
            isFadingOut = newRemaining <= fadeOutDurationSeconds
        )

        timerJob?.cancel()
        timerJob = scope.launch { runTimer(newRemaining) }
    }

    fun cancel() {
        timerJob?.cancel()
        fadeJob?.cancel()
        timerJob = null
        fadeJob = null
        unregisterShakeDetector()

        if (_state.value.isFadingOut) {
            onVolumeChange?.invoke(originalVolume)
        }

        _state.value = SleepTimerState()
        SleepTimerAlarmReceiver.cancelAlarm(context)
        scope.launch {
            context.sleepTimerStore.edit { it[KEY_IS_ACTIVE] = false }
        }
    }

    private fun registerShakeDetector() {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return

        shakeListener = object : SensorEventListener {
            private var lastShakeTime = 0L

            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

                if (magnitude > SHAKE_THRESHOLD) {
                    val now = System.currentTimeMillis()
                    if (now - lastShakeTime > 2000) {
                        lastShakeTime = now
                        extend()
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(
            shakeListener,
            accelerometer,
            SensorManager.SENSOR_DELAY_UI
        )
    }

    private fun unregisterShakeDetector() {
        shakeListener?.let {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            sensorManager?.unregisterListener(it)
        }
        shakeListener = null
    }

    fun release() {
        cancel()
    }
}
