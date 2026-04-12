package com.abook.ui.voicesettings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(
    viewModel: VoiceSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    var showSaveDialog by remember { mutableStateOf(false) }
    var showVoicePicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки голоса") },
                actions = {
                    IconButton(onClick = { viewModel.previewVoice() }) {
                        Icon(Icons.Default.VolumeUp, contentDescription = "Прослушать")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // --- Profiles section ---
            SectionHeader("Профили голоса")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                profiles.forEach { profile ->
                    Card(
                        modifier = Modifier.clickable { viewModel.loadProfile(profile) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (profile.id == state.activeProfileId) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(profile.name, style = MaterialTheme.typography.labelLarge)
                            if (!profile.isDefault) {
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(
                                    onClick = { viewModel.deleteProfile(profile) },
                                    modifier = Modifier.padding(0.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Удалить",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
                FilledTonalButton(onClick = { showSaveDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Сохранить")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // --- Core TTS parameters ---
            SectionHeader("Основные параметры")

            LabeledSlider(
                label = "Скорость речи",
                value = state.speechRate,
                onValueChange = { viewModel.setSpeechRate(it) },
                valueRange = 0.1f..4.0f,
                displayValue = "%.1fx".format(state.speechRate),
                onValueChangeFinished = { viewModel.applyLivePlaybackChanges() }
            )

            LabeledSlider(
                label = "Высота тона",
                value = state.pitch,
                onValueChange = { viewModel.setPitch(it) },
                valueRange = 0.1f..2.0f,
                displayValue = "%.1f".format(state.pitch),
                onValueChangeFinished = { viewModel.applyLivePlaybackChanges() }
            )

            LabeledSlider(
                label = "Громкость",
                value = state.volume,
                onValueChange = { viewModel.setVolume(it) },
                valueRange = 0.0f..1.0f,
                displayValue = "${(state.volume * 100).toInt()}%",
                onValueChangeFinished = { viewModel.applyLivePlaybackChanges() }
            )

            LabeledSlider(
                label = "Панорама (L/R)",
                value = state.pan,
                onValueChange = { viewModel.setPan(it) },
                valueRange = -1.0f..1.0f,
                displayValue = when {
                    state.pan < -0.1f -> "L %.0f%%".format(-state.pan * 100)
                    state.pan > 0.1f -> "R %.0f%%".format(state.pan * 100)
                    else -> "Центр"
                },
                onValueChangeFinished = { viewModel.applyLivePlaybackChanges() }
            )

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // --- Voice selection ---
            SectionHeader("Выбор голоса")

            FilledTonalButton(
                onClick = { showVoicePicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PlayCircle, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(state.selectedVoiceName ?: "Выбрать голос")
            }

            if (showVoicePicker) {
                val savedLanguageFilter by viewModel.voiceLanguageFilter.collectAsState()
                VoicePickerDialog(
                    voices = state.availableVoices,
                    selectedVoiceName = state.selectedVoiceName,
                    initialLanguageFilter = savedLanguageFilter,
                    onLanguageFilterChange = { viewModel.setVoiceLanguageFilter(it) },
                    onDismiss = { showVoicePicker = false },
                    onSelect = {
                        viewModel.selectVoice(it)
                        showVoicePicker = false
                    },
                    onDownloadRequest = {
                        viewModel.downloadVoiceData()
                        showVoicePicker = false
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // --- Equalizer ---
            SectionHeader("Эквалайзер")

            state.equalizerInfo?.let { eqInfo ->
                // Presets
                if (eqInfo.presetNames.isNotEmpty()) {
                    var showPresets by remember { mutableStateOf(false) }
                    FilledTonalButton(
                        onClick = { showPresets = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (eqInfo.currentPreset >= 0 && eqInfo.currentPreset < eqInfo.presetNames.size) {
                                eqInfo.presetNames[eqInfo.currentPreset]
                            } else "Свой пресет"
                        )
                    }
                    DropdownMenu(
                        expanded = showPresets,
                        onDismissRequest = { showPresets = false }
                    ) {
                        eqInfo.presetNames.forEachIndexed { index, name ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    viewModel.setEqualizerPreset(index)
                                    showPresets = false
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Band sliders
                for (band in 0 until eqInfo.numberOfBands) {
                    val freq = eqInfo.bandFrequencies[band]
                    val level = eqInfo.bandLevels.getOrElse(band) { 0 }
                    val freqLabel = if (freq >= 1000) "${freq / 1000}кГц" else "${freq}Гц"

                    LabeledSlider(
                        label = freqLabel,
                        value = level.toFloat(),
                        onValueChange = { viewModel.setEqualizerBandLevel(band, it.toInt()) },
                        valueRange = eqInfo.minLevel.toFloat()..eqInfo.maxLevel.toFloat(),
                        displayValue = "${level / 100}дБ"
                    )
                }
            } ?: Text(
                "Эквалайзер недоступен",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // --- Bass Boost ---
            SectionHeader("Усиление басов")
            LabeledSlider(
                label = "Сила",
                value = state.bassBoostStrength.toFloat(),
                onValueChange = { viewModel.setBassBoost(it.toInt()) },
                valueRange = 0f..1000f,
                displayValue = "${state.bassBoostStrength / 10}%"
            )

            Spacer(modifier = Modifier.height(16.dp))

            // --- Virtualizer ---
            SectionHeader("Виртуализатор (пространственный звук)")
            LabeledSlider(
                label = "Сила",
                value = state.virtualizerStrength.toFloat(),
                onValueChange = { viewModel.setVirtualizer(it.toInt()) },
                valueRange = 0f..1000f,
                displayValue = "${state.virtualizerStrength / 10}%"
            )

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // --- SSML ---
            SectionHeader("Паузы между предложениями (SSML)")

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Включить паузы")
                Switch(
                    checked = state.useSsml,
                    onCheckedChange = { viewModel.setSsmlEnabled(it) }
                )
            }

            if (state.useSsml) {
                LabeledSlider(
                    label = "Пауза",
                    value = state.ssmlPauseMs.toFloat(),
                    onValueChange = { viewModel.setSsmlPauseMs(it.toInt()) },
                    valueRange = 0f..5000f,
                    displayValue = "${state.ssmlPauseMs}мс"
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Preview button
            FilledTonalButton(
                onClick = { viewModel.previewVoice() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.VolumeUp, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Предпрослушка")
            }

            Spacer(modifier = Modifier.height(32.dp))
        }

        // Save profile dialog
        if (showSaveDialog) {
            var profileName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showSaveDialog = false },
                title = { Text("Сохранить профиль") },
                text = {
                    OutlinedTextField(
                        value = profileName,
                        onValueChange = { profileName = it },
                        label = { Text("Название") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (profileName.isNotBlank()) {
                                viewModel.saveProfile(profileName)
                                showSaveDialog = false
                            }
                        }
                    ) { Text("Сохранить") }
                },
                dismissButton = {
                    TextButton(onClick = { showSaveDialog = false }) { Text("Отмена") }
                }
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    displayValue: String,
    onValueChangeFinished: (() -> Unit)? = null
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                displayValue,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            onValueChangeFinished = { onValueChangeFinished?.invoke() },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
