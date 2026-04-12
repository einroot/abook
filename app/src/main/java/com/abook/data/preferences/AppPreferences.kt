package com.abook.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appDataStore by preferencesDataStore(name = "app_preferences")

class AppPreferences(private val context: Context) {

    companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_LANGUAGE = stringPreferencesKey("language")
        val KEY_DEFAULT_VOICE_PROFILE_ID = longPreferencesKey("default_voice_profile_id")
        val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val KEY_AUTO_PLAY_ON_OPEN = booleanPreferencesKey("auto_play_on_open")
        val KEY_VOICE_LANG_FILTER = stringPreferencesKey("voice_lang_filter")
        val KEY_SEEK_SHORT_SECONDS = intPreferencesKey("seek_short_seconds")
        val KEY_SEEK_LONG_SECONDS = intPreferencesKey("seek_long_seconds")

        const val DEFAULT_SEEK_SHORT = 30
        const val DEFAULT_SEEK_LONG = 300

        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_AUTO = "auto"
        const val THEME_AMOLED = "amoled"
    }

    val themeMode: Flow<String> = context.appDataStore.data.map { it[KEY_THEME_MODE] ?: THEME_AUTO }
    val language: Flow<String> = context.appDataStore.data.map { it[KEY_LANGUAGE] ?: "system" }
    val defaultVoiceProfileId: Flow<Long?> =
        context.appDataStore.data.map { it[KEY_DEFAULT_VOICE_PROFILE_ID] }
    val keepScreenOn: Flow<Boolean> =
        context.appDataStore.data.map { it[KEY_KEEP_SCREEN_ON] ?: false }
    val autoPlayOnOpen: Flow<Boolean> =
        context.appDataStore.data.map { it[KEY_AUTO_PLAY_ON_OPEN] ?: false }
    val voiceLanguageFilter: Flow<String?> =
        context.appDataStore.data.map { it[KEY_VOICE_LANG_FILTER] }
    val seekShortSeconds: Flow<Int> =
        context.appDataStore.data.map { it[KEY_SEEK_SHORT_SECONDS] ?: DEFAULT_SEEK_SHORT }
    val seekLongSeconds: Flow<Int> =
        context.appDataStore.data.map { it[KEY_SEEK_LONG_SECONDS] ?: DEFAULT_SEEK_LONG }

    suspend fun setThemeMode(mode: String) {
        context.appDataStore.edit { it[KEY_THEME_MODE] = mode }
    }

    suspend fun setLanguage(lang: String) {
        context.appDataStore.edit { it[KEY_LANGUAGE] = lang }
    }

    suspend fun setDefaultVoiceProfileId(id: Long) {
        context.appDataStore.edit { it[KEY_DEFAULT_VOICE_PROFILE_ID] = id }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.appDataStore.edit { it[KEY_KEEP_SCREEN_ON] = enabled }
    }

    suspend fun setAutoPlayOnOpen(enabled: Boolean) {
        context.appDataStore.edit { it[KEY_AUTO_PLAY_ON_OPEN] = enabled }
    }

    suspend fun setVoiceLanguageFilter(lang: String?) {
        context.appDataStore.edit {
            if (lang == null) it.remove(KEY_VOICE_LANG_FILTER)
            else it[KEY_VOICE_LANG_FILTER] = lang
        }
    }

    suspend fun setSeekShortSeconds(seconds: Int) {
        context.appDataStore.edit { it[KEY_SEEK_SHORT_SECONDS] = seconds.coerceIn(5, 600) }
    }

    suspend fun setSeekLongSeconds(seconds: Int) {
        context.appDataStore.edit { it[KEY_SEEK_LONG_SECONDS] = seconds.coerceIn(30, 1800) }
    }
}
