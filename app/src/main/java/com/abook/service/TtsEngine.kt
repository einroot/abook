package com.abook.service

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale

class TtsEngine(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var audioSessionId: Int = 0

    private var currentSpeechRate = 1.0f
    private var currentPitch = 1.0f
    private var currentVolume = 1.0f
    private var currentPan = 0.0f
    private var useSsml = false
    private var ssmlPauseMs = 300

    private val _initState = MutableStateFlow(false)
    val initState: StateFlow<Boolean> = _initState.asStateFlow()

    private val _speechRateFlow = MutableStateFlow(1.0f)
    val speechRateFlow: StateFlow<Float> = _speechRateFlow.asStateFlow()

    var onUtteranceStart: ((utteranceId: String) -> Unit)? = null
    var onUtteranceDone: ((utteranceId: String) -> Unit)? = null
    var onUtteranceError: ((utteranceId: String) -> Unit)? = null
    var onRangeStart: ((utteranceId: String, start: Int, end: Int) -> Unit)? = null

    data class VoiceInfo(
        val name: String,
        val locale: Locale,
        val quality: Int,
        val latency: Int,
        val requiresNetwork: Boolean,
        val features: Set<String>,
        val isNotInstalled: Boolean,
        /** Rough estimated download size in MB (Android TTS does not expose exact size). */
        val estimatedSizeMb: Int
    )

    /**
     * Estimates the approximate download size of a TTS voice package.
     *
     * Android's TTS API does not expose the real file size of voice data,
     * so this is a heuristic based on quality level and whether it's a Neural voice.
     */
    private fun estimateVoiceSizeMb(quality: Int, features: Set<String>, requiresNetwork: Boolean): Int {
        if (requiresNetwork) return 0  // Cloud voice, nothing to download
        val isNeural = features.any {
            it.contains("neural", ignoreCase = true) ||
                it.contains("wavenet", ignoreCase = true) ||
                it.contains("natural", ignoreCase = true)
        }
        return when {
            isNeural && quality >= 500 -> 180   // Neural2 / WaveNet offline: ~150-200 MB
            isNeural && quality >= 400 -> 100   // High Neural: ~80-120 MB
            quality >= 500 -> 80                // Very high standard: ~60-100 MB
            quality >= 400 -> 40                // High standard: ~30-50 MB
            quality >= 300 -> 25                // Normal: ~20-30 MB
            else -> 15                          // Low: ~10-20 MB
        }
    }

    private var currentEnginePackage: String? = null

    fun initialize(enginePackage: String? = null, onReady: () -> Unit = {}) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audioSessionId == 0) {
            audioSessionId = audioManager.generateAudioSessionId()
        }

        currentEnginePackage = enginePackage

        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                _initState.value = true

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String) {
                        onUtteranceStart?.invoke(utteranceId)
                    }

                    override fun onDone(utteranceId: String) {
                        onUtteranceDone?.invoke(utteranceId)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String) {
                        onUtteranceError?.invoke(utteranceId)
                    }

                    override fun onRangeStart(
                        utteranceId: String,
                        start: Int,
                        end: Int,
                        frame: Int
                    ) {
                        this@TtsEngine.onRangeStart?.invoke(utteranceId, start, end)
                    }
                })

                onReady()
            } else {
                Log.e(TAG, "TTS initialization failed with status: $status")
                _initState.value = false
            }
        }

        tts = if (enginePackage != null) {
            TextToSpeech(context, listener, enginePackage)
        } else {
            TextToSpeech(context, listener)
        }
    }

    fun getAudioSessionId(): Int = audioSessionId

    fun getInstalledEngines(): List<TextToSpeech.EngineInfo> {
        return tts?.engines ?: emptyList()
    }

    fun getCurrentEnginePackage(): String? = currentEnginePackage ?: tts?.defaultEngine

    fun promptVoiceDownload(): Intent {
        return Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    fun synthesizeToFile(text: String, utteranceId: String, file: File): Boolean {
        if (!isInitialized) return false
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, currentVolume)
            putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, currentPan)
        }
        val result = tts?.synthesizeToFile(text, params, file, utteranceId)
        return result == TextToSpeech.SUCCESS
    }

    fun getVoiceQualityLabel(quality: Int): String = when {
        quality >= Voice.QUALITY_VERY_HIGH -> "Очень высокое"
        quality >= Voice.QUALITY_HIGH -> "Высокое"
        quality >= Voice.QUALITY_NORMAL -> "Обычное"
        quality >= Voice.QUALITY_LOW -> "Низкое"
        else -> "Очень низкое"
    }

    fun getLatencyLabel(latency: Int): String = when {
        latency <= Voice.LATENCY_LOW -> "Быстрый"
        latency <= Voice.LATENCY_NORMAL -> "Обычный"
        else -> "Медленный"
    }

    // --- Voice enumeration ---

    fun getAvailableVoices(): List<VoiceInfo> {
        val voices = tts?.voices ?: return emptyList()
        return voices.map { voice ->
            val features = voice.features ?: emptySet()
            val isNotInstalled = features.contains(
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED
            )
            val needsNetwork = voice.isNetworkConnectionRequired
            VoiceInfo(
                name = voice.name,
                locale = voice.locale,
                quality = voice.quality,
                latency = voice.latency,
                requiresNetwork = needsNetwork,
                features = features,
                isNotInstalled = isNotInstalled,
                estimatedSizeMb = if (isNotInstalled)
                    estimateVoiceSizeMb(voice.quality, features, needsNetwork)
                else 0
            )
        }.sortedWith(
            compareBy<VoiceInfo> { it.locale.displayLanguage }
                .thenBy { it.isNotInstalled }       // installed first
                .thenByDescending { it.quality }
                .thenBy { it.latency }
        )
    }

    fun getAvailableLocales(): List<Locale> {
        return tts?.availableLanguages?.toList()?.sortedBy { it.displayName } ?: emptyList()
    }

    fun setVoice(voiceName: String): Boolean {
        val voice = tts?.voices?.find { it.name == voiceName }
        return if (voice != null) {
            tts?.voice = voice
            true
        } else false
    }

    fun setLanguage(locale: Locale): Int {
        return tts?.setLanguage(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
    }

    fun getCurrentVoice(): Voice? = tts?.voice

    // --- Current parameter getters (for UI state restoration) ---

    fun getCurrentSpeechRate(): Float = currentSpeechRate
    fun getCurrentPitch(): Float = currentPitch
    fun getCurrentVolume(): Float = currentVolume
    fun getCurrentPan(): Float = currentPan
    fun getCurrentSsmlEnabled(): Boolean = useSsml
    fun getCurrentSsmlPauseMs(): Int = ssmlPauseMs

    // --- Speech parameters ---

    fun setSpeechRate(rate: Float) {
        currentSpeechRate = rate.coerceIn(0.1f, 4.0f)
        tts?.setSpeechRate(currentSpeechRate)
        _speechRateFlow.value = currentSpeechRate
    }

    fun setPitch(pitch: Float) {
        currentPitch = pitch.coerceIn(0.1f, 2.0f)
        tts?.setPitch(currentPitch)
    }

    fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0.0f, 1.0f)
    }

    fun setPan(pan: Float) {
        currentPan = pan.coerceIn(-1.0f, 1.0f)
    }

    fun setSsmlEnabled(enabled: Boolean) {
        useSsml = enabled
    }

    fun setSsmlPauseMs(ms: Int) {
        ssmlPauseMs = ms.coerceIn(0, 5000)
    }

    // --- Speaking ---

    fun speak(text: String, utteranceId: String, queueMode: Int = TextToSpeech.QUEUE_ADD) {
        if (!isInitialized) return

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, currentVolume)
            putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, currentPan)
            putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, audioSessionId)
        }

        val textToSpeak = if (useSsml && ssmlPauseMs > 0) {
            SsmlBuilder.wrapWithPauses(text, ssmlPauseMs)
        } else {
            text
        }

        tts?.speak(textToSpeak, queueMode, params, utteranceId)
    }

    fun stop() {
        tts?.stop()
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    // --- Text chunking for TTS ---

    fun chunkText(text: String, maxChunkSize: Int = 2000): List<TextChunk> {
        val sentences = SsmlBuilder.splitIntoSentences(text)
        val chunks = mutableListOf<TextChunk>()
        var currentChunkText = StringBuilder()
        var currentChunkStart = 0
        var charOffset = 0

        for (sentence in sentences) {
            if (currentChunkText.length + sentence.length > maxChunkSize && currentChunkText.isNotEmpty()) {
                chunks.add(TextChunk(
                    text = currentChunkText.toString(),
                    charOffset = currentChunkStart
                ))
                currentChunkStart = charOffset
                currentChunkText = StringBuilder()
            }

            if (currentChunkText.isNotEmpty()) {
                currentChunkText.append(" ")
                charOffset++
            }
            currentChunkText.append(sentence)
            charOffset += sentence.length
        }

        if (currentChunkText.isNotEmpty()) {
            chunks.add(TextChunk(
                text = currentChunkText.toString(),
                charOffset = currentChunkStart
            ))
        }

        return chunks
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        _initState.value = false
    }

    data class TextChunk(
        val text: String,
        val charOffset: Int
    )

    companion object {
        private const val TAG = "TtsEngine"
    }
}
