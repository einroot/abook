package com.abook.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abook.data.preferences.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences
) : ViewModel() {

    val themeMode: StateFlow<String> = prefs.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences.THEME_AUTO)
    val language: StateFlow<String> = prefs.language
        .stateIn(viewModelScope, SharingStarted.Eagerly, "system")
    val keepScreenOn: StateFlow<Boolean> = prefs.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val autoPlayOnOpen: StateFlow<Boolean> = prefs.autoPlayOnOpen
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val seekShortSeconds: StateFlow<Int> = prefs.seekShortSeconds
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences.DEFAULT_SEEK_SHORT)
    val seekLongSeconds: StateFlow<Int> = prefs.seekLongSeconds
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences.DEFAULT_SEEK_LONG)

    fun setSeekShortSeconds(seconds: Int) {
        viewModelScope.launch {
            prefs.setSeekShortSeconds(seconds)
            // Ensure long seek is always >= short seek
            if (seconds > seekLongSeconds.value) {
                prefs.setSeekLongSeconds(seconds)
            }
        }
    }

    fun setSeekLongSeconds(seconds: Int) {
        viewModelScope.launch {
            prefs.setSeekLongSeconds(seconds)
            // Ensure short seek is always <= long seek
            if (seconds < seekShortSeconds.value) {
                prefs.setSeekShortSeconds(seconds)
            }
        }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }

    fun setLanguage(lang: String) {
        viewModelScope.launch { prefs.setLanguage(lang) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { prefs.setKeepScreenOn(enabled) }
    }

    fun setAutoPlayOnOpen(enabled: Boolean) {
        viewModelScope.launch { prefs.setAutoPlayOnOpen(enabled) }
    }

    fun requestBatteryOptimizationExemption() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
