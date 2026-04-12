package com.abook.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "voice_profiles")
data class VoiceProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val volume: Float = 1.0f,
    val pan: Float = 0.0f,
    val voiceName: String? = null,
    val locale: String? = null,
    val equalizerPreset: Int = -1,
    val equalizerBandLevels: String = "",
    val bassBoostStrength: Int = 0,
    val virtualizerStrength: Int = 0,
    val useSsml: Boolean = false,
    val ssmlPauseBetweenSentencesMs: Int = 300,
    val reverbPreset: Int = 0,
    val loudnessGain: Int = 0,
    val isDefault: Boolean = false
)
