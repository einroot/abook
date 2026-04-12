package com.abook.ui.voicesettings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abook.data.db.dao.VoiceProfileDao
import com.abook.data.db.entity.VoiceProfileEntity
import com.abook.data.preferences.AppPreferences
import com.abook.service.AudioEffectsManager
import com.abook.service.TtsEngine
import com.abook.service.TtsPlaybackService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class TtsEngineInfo(val packageName: String, val displayName: String)

data class VoiceSettingsUiState(
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val volume: Float = 1.0f,
    val pan: Float = 0.0f,
    val availableVoices: List<TtsEngine.VoiceInfo> = emptyList(),
    val selectedVoiceName: String? = null,
    val availableLocales: List<Locale> = emptyList(),
    val selectedLocale: Locale? = null,
    val equalizerInfo: AudioEffectsManager.EqualizerInfo? = null,
    val bassBoostStrength: Int = 0,
    val virtualizerStrength: Int = 0,
    val reverbPreset: Int = 0,
    val loudnessGain: Int = 0,
    val useSsml: Boolean = false,
    val ssmlPauseMs: Int = 300,
    val activeProfileId: Long? = null,
    val isInitialized: Boolean = false,
    val availableEngines: List<TtsEngineInfo> = emptyList(),
    val currentEngine: String? = null
)

@HiltViewModel
class VoiceSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val voiceProfileDao: VoiceProfileDao,
    private val appPreferences: AppPreferences
) : ViewModel() {

    val voiceLanguageFilter: StateFlow<String?> = appPreferences.voiceLanguageFilter
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setVoiceLanguageFilter(lang: String?) {
        viewModelScope.launch {
            appPreferences.setVoiceLanguageFilter(lang)
        }
    }

    private var service: TtsPlaybackService? = null
    private var ttsEngine: TtsEngine? = null
    private var audioEffects: AudioEffectsManager? = null

    private val _uiState = MutableStateFlow(VoiceSettingsUiState())
    val uiState: StateFlow<VoiceSettingsUiState> = _uiState.asStateFlow()

    val profiles = voiceProfileDao.getAllProfiles()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? TtsPlaybackService.LocalBinder ?: return
            service = localBinder.getService()
            ttsEngine = service?.getTtsEngine()
            audioEffects = service?.getAudioEffectsManager()
            loadCurrentState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            ttsEngine = null
            audioEffects = null
        }
    }

    init {
        try {
            val intent = Intent(context, TtsPlaybackService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (_: Exception) {
                context.startService(intent)
            }
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadCurrentState() {
        val engine = ttsEngine ?: return
        val effects = audioEffects
        val engines = engine.getInstalledEngines().map {
            TtsEngineInfo(it.name, it.label)
        }

        _uiState.value = VoiceSettingsUiState(
            speechRate = engine.getCurrentSpeechRate(),
            pitch = engine.getCurrentPitch(),
            volume = engine.getCurrentVolume(),
            pan = engine.getCurrentPan(),
            availableVoices = engine.getAvailableVoices(),
            selectedVoiceName = engine.getCurrentVoice()?.name,
            availableLocales = engine.getAvailableLocales(),
            selectedLocale = engine.getCurrentVoice()?.locale,
            equalizerInfo = effects?.getEqualizerInfo(),
            bassBoostStrength = effects?.getBassBoostStrength() ?: 0,
            virtualizerStrength = effects?.getVirtualizerStrength() ?: 0,
            reverbPreset = effects?.getPresetReverb() ?: 0,
            loudnessGain = effects?.getLoudnessGain() ?: 0,
            useSsml = engine.getCurrentSsmlEnabled(),
            ssmlPauseMs = engine.getCurrentSsmlPauseMs(),
            isInitialized = true,
            availableEngines = engines,
            currentEngine = engine.getCurrentEnginePackage()
        )
    }

    fun selectEngine(packageName: String) {
        viewModelScope.launch {
            service?.reinitializeTts(packageName)
            loadCurrentState()
        }
    }

    fun downloadVoiceData() {
        val intent = ttsEngine?.promptVoiceDownload() ?: return
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- Speech rate ---

    fun setSpeechRate(rate: Float) {
        ttsEngine?.setSpeechRate(rate)
        _uiState.value = _uiState.value.copy(speechRate = rate)
    }

    // --- Pitch ---

    fun setPitch(pitch: Float) {
        ttsEngine?.setPitch(pitch)
        _uiState.value = _uiState.value.copy(pitch = pitch)
    }

    // --- Volume ---

    fun setVolume(volume: Float) {
        ttsEngine?.setVolume(volume)
        _uiState.value = _uiState.value.copy(volume = volume)
    }

    // --- Pan ---

    fun setPan(pan: Float) {
        ttsEngine?.setPan(pan)
        _uiState.value = _uiState.value.copy(pan = pan)
    }

    /**
     * Re-speak from current position so rate/pitch/volume/pan changes
     * are audible immediately. Called from sliders on onValueChangeFinished.
     */
    fun applyLivePlaybackChanges() {
        service?.resyncPlayback()
    }

    // --- Voice selection ---

    fun selectVoice(voiceName: String) {
        if (ttsEngine?.setVoice(voiceName) == true) {
            _uiState.value = _uiState.value.copy(
                selectedVoiceName = voiceName,
                selectedLocale = ttsEngine?.getCurrentVoice()?.locale
            )
            applyLivePlaybackChanges()
        }
    }

    // --- Locale selection ---

    fun selectLocale(locale: Locale) {
        ttsEngine?.setLanguage(locale)
        _uiState.value = _uiState.value.copy(
            selectedLocale = locale,
            availableVoices = ttsEngine?.getAvailableVoices() ?: emptyList()
        )
        applyLivePlaybackChanges()
    }

    // --- Equalizer ---

    fun setEqualizerPreset(preset: Int) {
        audioEffects?.setEqualizerPreset(preset)
        _uiState.value = _uiState.value.copy(
            equalizerInfo = audioEffects?.getEqualizerInfo()
        )
    }

    fun setEqualizerBandLevel(band: Int, level: Int) {
        audioEffects?.setEqualizerBandLevel(band, level)
        _uiState.value = _uiState.value.copy(
            equalizerInfo = audioEffects?.getEqualizerInfo()
        )
    }

    // --- Bass Boost ---

    fun setBassBoost(strength: Int) {
        audioEffects?.setBassBoostStrength(strength)
        _uiState.value = _uiState.value.copy(bassBoostStrength = strength)
    }

    // --- Virtualizer ---

    fun setVirtualizer(strength: Int) {
        audioEffects?.setVirtualizerStrength(strength)
        _uiState.value = _uiState.value.copy(virtualizerStrength = strength)
    }

    // --- Reverb ---

    fun setReverbPreset(preset: Int) {
        audioEffects?.setPresetReverb(preset.toShort())
        _uiState.value = _uiState.value.copy(reverbPreset = preset)
    }

    // --- Loudness ---

    fun setLoudness(gainMb: Int) {
        audioEffects?.setLoudnessGain(gainMb)
        _uiState.value = _uiState.value.copy(loudnessGain = gainMb)
    }

    // --- Custom EQ presets ---

    val customEqPresets: List<AudioEffectsManager.CustomEqPreset>
        get() = audioEffects?.customPresets ?: emptyList()

    fun applyCustomEqPreset(preset: AudioEffectsManager.CustomEqPreset) {
        audioEffects?.applyCustomPreset(preset)
        _uiState.value = _uiState.value.copy(
            equalizerInfo = audioEffects?.getEqualizerInfo()
        )
    }

    // --- SSML ---

    fun setSsmlEnabled(enabled: Boolean) {
        ttsEngine?.setSsmlEnabled(enabled)
        _uiState.value = _uiState.value.copy(useSsml = enabled)
        applyLivePlaybackChanges()
    }

    fun setSsmlPauseMs(ms: Int) {
        ttsEngine?.setSsmlPauseMs(ms)
        _uiState.value = _uiState.value.copy(ssmlPauseMs = ms)
    }

    // --- Preview ---

    fun previewVoice() {
        // If book is playing, pause it and tell the service to auto-resume
        // after the preview utterance completes (id="preview").
        val wasPlaying = service?.playbackState?.value?.isPlaying == true
        if (wasPlaying) {
            service?.pause()
        }
        service?.setResumeAfterPreview(wasPlaying)
        ttsEngine?.speak(
            "Это предварительное прослушивание текущих настроек голоса. " +
            "Скорость, тон, громкость и эффекты применены.",
            "preview",
            android.speech.tts.TextToSpeech.QUEUE_FLUSH
        )
    }

    // --- Profiles ---

    fun saveProfile(name: String) {
        val state = _uiState.value
        viewModelScope.launch(Dispatchers.IO) {
            val entity = VoiceProfileEntity(
                name = name,
                speechRate = state.speechRate,
                pitch = state.pitch,
                volume = state.volume,
                pan = state.pan,
                voiceName = state.selectedVoiceName,
                locale = state.selectedLocale?.toLanguageTag(),
                equalizerPreset = state.equalizerInfo?.currentPreset ?: -1,
                equalizerBandLevels = state.equalizerInfo?.bandLevels?.joinToString(",") ?: "",
                bassBoostStrength = state.bassBoostStrength,
                virtualizerStrength = state.virtualizerStrength,
                useSsml = state.useSsml,
                ssmlPauseBetweenSentencesMs = state.ssmlPauseMs,
                reverbPreset = state.reverbPreset,
                loudnessGain = state.loudnessGain
            )
            val id = voiceProfileDao.insert(entity)
            _uiState.value = state.copy(activeProfileId = id)
        }
    }

    fun loadProfile(profile: VoiceProfileEntity) {
        // Apply TTS params directly without triggering individual resyncs.
        // We'll do ONE resync at the end to avoid rapid stop/start stuttering.
        ttsEngine?.setSpeechRate(profile.speechRate)
        ttsEngine?.setPitch(profile.pitch)
        ttsEngine?.setVolume(profile.volume)
        ttsEngine?.setPan(profile.pan)
        profile.voiceName?.let { ttsEngine?.setVoice(it) }
        profile.locale?.let {
            try { ttsEngine?.setLanguage(Locale.forLanguageTag(it)) } catch (_: Exception) {}
        }
        ttsEngine?.setSsmlEnabled(profile.useSsml)
        ttsEngine?.setSsmlPauseMs(profile.ssmlPauseBetweenSentencesMs)

        // Apply audio effects (these are live via DSP, no resync needed)
        if (profile.equalizerPreset >= 0) {
            audioEffects?.setEqualizerPreset(profile.equalizerPreset)
        } else if (profile.equalizerBandLevels.isNotBlank()) {
            profile.equalizerBandLevels.split(",").forEachIndexed { band, level ->
                level.toIntOrNull()?.let { audioEffects?.setEqualizerBandLevel(band, it) }
            }
        }
        audioEffects?.setBassBoostStrength(profile.bassBoostStrength)
        audioEffects?.setVirtualizerStrength(profile.virtualizerStrength)
        audioEffects?.setPresetReverb(profile.reverbPreset.toShort())
        audioEffects?.setLoudnessGain(profile.loudnessGain)

        // Update UI state in one shot
        _uiState.value = _uiState.value.copy(
            speechRate = profile.speechRate,
            pitch = profile.pitch,
            volume = profile.volume,
            pan = profile.pan,
            selectedVoiceName = profile.voiceName ?: _uiState.value.selectedVoiceName,
            selectedLocale = profile.locale?.let {
                try { Locale.forLanguageTag(it) } catch (_: Exception) { null }
            } ?: _uiState.value.selectedLocale,
            equalizerInfo = audioEffects?.getEqualizerInfo(),
            bassBoostStrength = profile.bassBoostStrength,
            virtualizerStrength = profile.virtualizerStrength,
            reverbPreset = profile.reverbPreset,
            loudnessGain = profile.loudnessGain,
            useSsml = profile.useSsml,
            ssmlPauseMs = profile.ssmlPauseBetweenSentencesMs,
            activeProfileId = profile.id
        )

        // Single resync to apply all TTS changes at once
        applyLivePlaybackChanges()
    }

    fun deleteProfile(profile: VoiceProfileEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            voiceProfileDao.delete(profile)
        }
    }

    override fun onCleared() {
        try { context.unbindService(connection) } catch (_: Exception) {}
        super.onCleared()
    }
}
