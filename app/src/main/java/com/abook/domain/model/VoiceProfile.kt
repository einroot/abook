package com.abook.domain.model

data class VoiceProfile(
    val id: Long = 0,
    val name: String,
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val volume: Float = 1.0f,
    val pan: Float = 0.0f,
    val voiceName: String? = null,
    val locale: String? = null,
    val equalizerPreset: Int = -1,
    val equalizerBandLevels: List<Int> = emptyList(),
    val bassBoostStrength: Int = 0,
    val virtualizerStrength: Int = 0,
    val useSsml: Boolean = false,
    val ssmlPauseBetweenSentencesMs: Int = 300,
    val isDefault: Boolean = false
)
