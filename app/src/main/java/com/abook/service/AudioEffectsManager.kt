package com.abook.service

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.util.Log

class AudioEffectsManager {

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var presetReverb: PresetReverb? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    private var audioSessionId: Int = 0

    data class CustomEqPreset(val name: String, val bandLevels: IntArray)

    val customPresets = listOf(
        CustomEqPreset("Голос (чётче)", intArrayOf(300, 0, 0, 300, 500)),
        CustomEqPreset("Аудиокнига", intArrayOf(200, 100, 0, 200, 300)),
        CustomEqPreset("Бас", intArrayOf(500, 300, 0, -100, -200)),
        CustomEqPreset("Высокие", intArrayOf(-200, -100, 0, 300, 500)),
        CustomEqPreset("Нейтральный", intArrayOf(0, 0, 0, 0, 0))
    )

    data class EffectState(
        val equalizerPreset: Int,
        val equalizerBandLevels: List<Int>,
        val bassBoostStrength: Int,
        val virtualizerStrength: Int,
        val reverbPreset: Int,
        val loudnessGain: Int
    )

    data class EqualizerInfo(
        val numberOfBands: Int,
        val minLevel: Short,
        val maxLevel: Short,
        val bandFrequencies: List<Int>,
        val presetNames: List<String>,
        val currentPreset: Int,
        val bandLevels: List<Short>
    )

    fun initialize(sessionId: Int) {
        release()
        audioSessionId = sessionId

        try {
            equalizer = Equalizer(0, sessionId).apply { enabled = true }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create Equalizer", e)
        }

        try {
            bassBoost = BassBoost(0, sessionId).apply { enabled = true }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create BassBoost", e)
        }

        try {
            virtualizer = Virtualizer(0, sessionId).apply { enabled = true }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create Virtualizer", e)
        }

        try {
            presetReverb = PresetReverb(0, sessionId).apply { enabled = true }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create PresetReverb", e)
        }

        try {
            loudnessEnhancer = LoudnessEnhancer(sessionId).apply { enabled = true }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create LoudnessEnhancer", e)
        }
    }

    fun setPresetReverb(preset: Short) {
        try {
            presetReverb?.preset = preset
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set reverb preset", e)
        }
    }

    fun getPresetReverb(): Int {
        return try { presetReverb?.preset?.toInt() ?: 0 } catch (_: Exception) { 0 }
    }

    fun setLoudnessGain(gainMb: Int) {
        try {
            loudnessEnhancer?.setTargetGain(gainMb)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set loudness", e)
        }
    }

    fun getLoudnessGain(): Int {
        return try { loudnessEnhancer?.targetGain?.toInt() ?: 0 } catch (_: Exception) { 0 }
    }

    fun applyCustomPreset(preset: CustomEqPreset) {
        val eq = equalizer ?: return
        val bands = eq.numberOfBands.toInt()
        val levels = preset.bandLevels
        for (i in 0 until bands) {
            val level = levels.getOrElse(i) { 0 }
            try {
                eq.setBandLevel(i.toShort(), level.toShort())
            } catch (_: Exception) {}
        }
    }

    fun getFullState(): EffectState {
        val eq = equalizer
        val levels = if (eq != null) {
            (0 until eq.numberOfBands.toInt()).map { eq.getBandLevel(it.toShort()).toInt() }
        } else emptyList()
        val currentPreset = try { eq?.currentPreset?.toInt() ?: -1 } catch (_: Exception) { -1 }
        return EffectState(
            equalizerPreset = currentPreset,
            equalizerBandLevels = levels,
            bassBoostStrength = getBassBoostStrength(),
            virtualizerStrength = getVirtualizerStrength(),
            reverbPreset = getPresetReverb(),
            loudnessGain = getLoudnessGain()
        )
    }

    fun applyState(state: EffectState) {
        if (state.equalizerPreset >= 0) {
            setEqualizerPreset(state.equalizerPreset)
        } else {
            state.equalizerBandLevels.forEachIndexed { i, level -> setEqualizerBandLevel(i, level) }
        }
        setBassBoostStrength(state.bassBoostStrength)
        setVirtualizerStrength(state.virtualizerStrength)
        setPresetReverb(state.reverbPreset.toShort())
        setLoudnessGain(state.loudnessGain)
    }

    fun getEqualizerInfo(): EqualizerInfo? {
        val eq = equalizer ?: return null
        val bands = eq.numberOfBands.toInt()
        val freqs = (0 until bands).map { eq.getCenterFreq(it.toShort()) / 1000 }
        val presets = (0 until eq.numberOfPresets).map {
            eq.getPresetName(it.toShort())
        }
        val levels = (0 until bands).map { eq.getBandLevel(it.toShort()) }

        return EqualizerInfo(
            numberOfBands = bands,
            minLevel = eq.bandLevelRange[0],
            maxLevel = eq.bandLevelRange[1],
            bandFrequencies = freqs,
            presetNames = presets,
            currentPreset = try { eq.currentPreset.toInt() } catch (_: Exception) { -1 },
            bandLevels = levels
        )
    }

    fun setEqualizerPreset(preset: Int) {
        try {
            equalizer?.usePreset(preset.toShort())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set EQ preset", e)
        }
    }

    fun setEqualizerBandLevel(band: Int, level: Int) {
        try {
            equalizer?.setBandLevel(band.toShort(), level.toShort())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set EQ band level", e)
        }
    }

    fun setBassBoostStrength(strength: Int) {
        try {
            bassBoost?.setStrength(strength.toShort())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set bass boost", e)
        }
    }

    fun getBassBoostStrength(): Int {
        return try {
            bassBoost?.roundedStrength?.toInt() ?: 0
        } catch (_: Exception) { 0 }
    }

    fun setVirtualizerStrength(strength: Int) {
        try {
            virtualizer?.setStrength(strength.toShort())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set virtualizer", e)
        }
    }

    fun getVirtualizerStrength(): Int {
        return try {
            virtualizer?.roundedStrength?.toInt() ?: 0
        } catch (_: Exception) { 0 }
    }

    fun setEnabled(enabled: Boolean) {
        equalizer?.enabled = enabled
        bassBoost?.enabled = enabled
        virtualizer?.enabled = enabled
        presetReverb?.enabled = enabled
        loudnessEnhancer?.enabled = enabled
    }

    fun release() {
        try { equalizer?.release() } catch (_: Exception) {}
        try { bassBoost?.release() } catch (_: Exception) {}
        try { virtualizer?.release() } catch (_: Exception) {}
        try { presetReverb?.release() } catch (_: Exception) {}
        try { loudnessEnhancer?.release() } catch (_: Exception) {}
        equalizer = null
        bassBoost = null
        virtualizer = null
        presetReverb = null
        loudnessEnhancer = null
    }

    companion object {
        private const val TAG = "AudioEffectsManager"
    }
}
