package com.abook.ui.voicesettings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.abook.service.TtsEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePickerDialog(
    voices: List<TtsEngine.VoiceInfo>,
    selectedVoiceName: String?,
    initialLanguageFilter: String?,
    onLanguageFilterChange: (String?) -> Unit,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onDownloadRequest: () -> Unit = {}
) {
    var query by remember { mutableStateOf("") }
    var selectedLanguageFilter by remember(initialLanguageFilter) {
        mutableStateOf(initialLanguageFilter)
    }

    // All unique display languages
    val languages = remember(voices) {
        voices.map { it.locale.displayLanguage }.distinct().sorted()
    }

    // Filtered voices
    val filtered = remember(voices, query, selectedLanguageFilter) {
        voices.filter { voice ->
            val matchesLanguage = selectedLanguageFilter == null ||
                voice.locale.displayLanguage == selectedLanguageFilter
            val matchesQuery = query.isBlank() ||
                voice.name.contains(query, ignoreCase = true) ||
                voice.locale.displayName.contains(query, ignoreCase = true) ||
                voice.locale.language.contains(query, ignoreCase = true) ||
                voice.locale.country.contains(query, ignoreCase = true) ||
                voice.locale.displayLanguage.contains(query, ignoreCase = true) ||
                voice.locale.displayCountry.contains(query, ignoreCase = true)
            matchesLanguage && matchesQuery
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Выбор голоса") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Закрыть")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Поиск (язык, страна, имя)") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Очистить")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true
                )

                // Language chips row
                Text(
                    "Фильтр по языку (${languages.size}):",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    item {
                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                AssistChip(
                                    onClick = {
                                        selectedLanguageFilter = null
                                        onLanguageFilterChange(null)
                                    },
                                    label = { Text("Все") },
                                    leadingIcon = if (selectedLanguageFilter == null) {
                                        { Icon(Icons.Default.Check, contentDescription = null) }
                                    } else null
                                )
                            }
                            items(languages) { lang ->
                                AssistChip(
                                    onClick = {
                                        val next = if (selectedLanguageFilter == lang) null else lang
                                        selectedLanguageFilter = next
                                        onLanguageFilterChange(next)
                                    },
                                    label = { Text(lang) },
                                    leadingIcon = if (selectedLanguageFilter == lang) {
                                        { Icon(Icons.Default.Check, contentDescription = null) }
                                    } else null
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                Text(
                    "Найдено: ${filtered.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(filtered, key = { _, v -> v.name }) { _, voice ->
                        VoiceRow(
                            voice = voice,
                            isSelected = voice.name == selectedVoiceName,
                            onClick = {
                                if (voice.isNotInstalled) {
                                    onDownloadRequest()
                                } else {
                                    onSelect(voice.name)
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceRow(
    voice: TtsEngine.VoiceInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val containerColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        voice.isNotInstalled -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                isSelected -> {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                voice.isNotInstalled -> {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                voice.requiresNetwork -> {
                    Icon(
                        Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    voice.locale.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    voice.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QualityBadge(voice.quality)
                    if (voice.requiresNetwork) {
                        Text(
                            "• Онлайн",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    } else if (voice.isNotInstalled) {
                        Text(
                            "• Нужно скачать ~${voice.estimatedSizeMb} МБ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Medium
                        )
                    } else {
                        Text(
                            "• Установлен",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QualityBadge(quality: Int) {
    val (label, color) = when {
        quality >= 500 -> "Очень высокое" to MaterialTheme.colorScheme.primary
        quality >= 400 -> "Высокое" to MaterialTheme.colorScheme.primary
        quality >= 300 -> "Обычное" to MaterialTheme.colorScheme.secondary
        else -> "Низкое" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(label, style = MaterialTheme.typography.labelSmall, color = color)
}
