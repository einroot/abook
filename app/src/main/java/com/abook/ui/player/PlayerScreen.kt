package com.abook.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import kotlin.math.abs

@Composable
private fun SeekButton(
    seconds: Int,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val absSec = abs(seconds)
    val label = when {
        absSec >= 60 && absSec % 60 == 0 -> "${absSec / 60}м"
        absSec >= 60 -> "${absSec / 60}:${"%02d".format(absSec % 60)}"
        else -> "${absSec}с"
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(onClick = onClick) {
            Icon(
                icon,
                contentDescription = if (seconds < 0) "Назад $label" else "Вперёд $label",
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatMinutes(minutes: Int): String = when {
    minutes < 60 -> "$minutes мин"
    minutes % 60 == 0 -> "${minutes / 60} ч"
    else -> "${minutes / 60} ч ${minutes % 60} мин"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(
    bookId: String?,
    onChapterListClick: () -> Unit,
    onBookmarksClick: () -> Unit,
    seekToChapterOnLaunch: Int = -1,
    seekToOffsetOnLaunch: Int = -1,
    selectedChapter: Int = -1,
    onChapterConsumed: () -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val playbackState by viewModel.playbackState.collectAsState()
    val sleepTimerState by viewModel.sleepTimerState.collectAsState()
    val seekShortSec by viewModel.seekShortSeconds.collectAsState()
    val seekLongSec by viewModel.seekLongSeconds.collectAsState()
    val speechRate by viewModel.speechRate.collectAsState()
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    var showAddBookmarkDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(bookId) {
        if (bookId != null) {
            // Wait a tick so the service binding can deliver the current state
            // before we decide whether to (re)start playback.
            kotlinx.coroutines.delay(100)
            if (playbackState.bookId != bookId) {
                viewModel.playBook(bookId)
            }
        }
    }

    LaunchedEffect(selectedChapter) {
        if (selectedChapter >= 0) {
            viewModel.seekToChapter(selectedChapter)
            onChapterConsumed()
        }
    }

    // Bookmark navigation: jump to chapter + offset on first composition
    var bookmarkSeekConsumed by remember(seekToChapterOnLaunch, seekToOffsetOnLaunch) {
        mutableStateOf(false)
    }
    LaunchedEffect(seekToChapterOnLaunch, seekToOffsetOnLaunch, playbackState.bookId) {
        if (!bookmarkSeekConsumed &&
            seekToChapterOnLaunch >= 0 &&
            seekToOffsetOnLaunch >= 0 &&
            playbackState.bookId == bookId
        ) {
            // Wait for chapters to load
            kotlinx.coroutines.delay(200)
            viewModel.seekToChapter(seekToChapterOnLaunch)
            kotlinx.coroutines.delay(100)
            viewModel.seekToCharOffset(seekToOffsetOnLaunch)
            bookmarkSeekConsumed = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = playbackState.bookTitle.ifEmpty { "ABook" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    IconButton(onClick = { showAddBookmarkDialog = true }) {
                        Icon(Icons.Default.BookmarkAdd, contentDescription = "Добавить закладку")
                    }
                    IconButton(onClick = onBookmarksClick) {
                        Icon(Icons.Default.Bookmark, contentDescription = "Список закладок")
                    }
                    IconButton(onClick = onChapterListClick) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Главы")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        // Dragging state for sliders
        var isDraggingChapter by remember { mutableStateOf(false) }
        var dragChapterProgress by remember { mutableFloatStateOf(0f) }
        var isDraggingBook by remember { mutableStateOf(false) }
        var dragBookProgress by remember { mutableFloatStateOf(0f) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .pointerInput(Unit) {
                    var totalDx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDx = 0f },
                        onDragEnd = {
                            val threshold = 100.dp.toPx()
                            if (abs(totalDx) > threshold) {
                                if (totalDx < 0) viewModel.nextChapter()
                                else viewModel.prevChapter()
                            }
                        }
                    ) { _, dx -> totalDx += dx }
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Chapter info
            Text(
                text = playbackState.chapterTitle,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Глава ${playbackState.chapterIndex + 1} из ${playbackState.totalChapters}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Chapter progress — draggable
            Slider(
                value = if (isDraggingChapter) dragChapterProgress else playbackState.chapterProgress,
                onValueChange = {
                    dragChapterProgress = it
                    isDraggingChapter = true
                },
                onValueChangeFinished = {
                    isDraggingChapter = false
                    val target = (dragChapterProgress * playbackState.chapterLength).toInt()
                    viewModel.seekToCharOffset(target)
                },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Глава: ${(playbackState.chapterProgress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Book progress — draggable
            Slider(
                value = if (isDraggingBook) dragBookProgress else playbackState.bookProgress,
                onValueChange = {
                    dragBookProgress = it
                    isDraggingBook = true
                },
                onValueChangeFinished = {
                    isDraggingBook = false
                    viewModel.seekToBookProgress(dragBookProgress)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.tertiary,
                    activeTrackColor = MaterialTheme.colorScheme.tertiary,
                    inactiveTrackColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            )
            // speechRate is read so this Text recomposes when rate changes
            val rateLabel = "%.2fx".format(speechRate)
            Text(
                text = "Книга: ${(playbackState.bookProgress * 100).toInt()}%  •  Осталось: ${viewModel.estimateRemainingTime()}  •  $rateLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Playback controls — 7 buttons in one responsive row
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(
                    onClick = { viewModel.prevChapter() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "Предыдущая глава",
                        modifier = Modifier.size(26.dp)
                    )
                }

                SeekButton(
                    seconds = -seekLongSec,
                    icon = Icons.Default.FastRewind,
                    onClick = { viewModel.seekBySeconds(-seekLongSec) },
                    modifier = Modifier.weight(1f)
                )

                SeekButton(
                    seconds = -seekShortSec,
                    icon = Icons.Default.Replay30,
                    onClick = { viewModel.seekBySeconds(-seekShortSec) },
                    modifier = Modifier.weight(1f)
                )

                // Play/Pause — focal, larger. Uses weight for horizontal space,
                // fixed size for the button itself.
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.weight(1.4f),
                    contentAlignment = Alignment.Center
                ) {
                    FilledTonalIconButton(
                        onClick = { viewModel.togglePlayPause() },
                        modifier = Modifier.size(60.dp)
                    ) {
                        Icon(
                            if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playbackState.isPlaying) "Пауза" else "Воспроизвести",
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                SeekButton(
                    seconds = seekShortSec,
                    icon = Icons.Default.Forward30,
                    onClick = { viewModel.seekBySeconds(seekShortSec) },
                    modifier = Modifier.weight(1f)
                )

                SeekButton(
                    seconds = seekLongSec,
                    icon = Icons.Default.FastForward,
                    onClick = { viewModel.seekBySeconds(seekLongSec) },
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = { viewModel.nextChapter() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "Следующая глава",
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Sleep timer button
            FilledTonalIconButton(
                onClick = {
                    if (sleepTimerState.isActive) {
                        showSleepTimerSheet = true
                    } else {
                        viewModel.startSleepTimer(30)
                    }
                },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.Default.Bedtime,
                    contentDescription = "Таймер сна",
                    tint = if (sleepTimerState.isActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            if (sleepTimerState.isActive) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = sleepTimerState.displayTime,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (sleepTimerState.isFadingOut) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
        }

        // Sleep timer bottom sheet
        if (showSleepTimerSheet) {
            // Custom value persists across sheet open/close while it's in composition
            var customMinutes by remember { mutableStateOf(30) }
            ModalBottomSheet(onDismissRequest = { showSleepTimerSheet = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Таймер сна",
                        style = MaterialTheme.typography.titleLarge
                    )
                    if (sleepTimerState.isActive) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Активен — осталось: ${sleepTimerState.displayTime}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (sleepTimerState.isFadingOut)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))

                    // Big value display + −/+ buttons
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FilledTonalIconButton(
                            onClick = {
                                customMinutes = (customMinutes - 1).coerceAtLeast(1)
                            }
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "−1 минута")
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val displayText = formatMinutes(customMinutes)
                            Text(
                                displayText,
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "минут",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        FilledTonalIconButton(
                            onClick = {
                                customMinutes = (customMinutes + 1).coerceAtMost(480)
                            }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "+1 минута")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Slider 1..240 minutes (4 hours)
                    Slider(
                        value = customMinutes.toFloat(),
                        onValueChange = { customMinutes = it.toInt() },
                        valueRange = 1f..240f,
                        steps = 0,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("1 мин", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("4 часа", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Big start button
                    Button(
                        onClick = {
                            viewModel.startSleepTimer(customMinutes)
                            showSleepTimerSheet = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Bedtime, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Запустить на ${formatMinutes(customMinutes)}")
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        "Быстрые пресеты",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(5, 10, 15, 20, 30, 45, 60, 90, 120).forEach { minutes ->
                            AssistChip(
                                onClick = { customMinutes = minutes },
                                label = { Text("${minutes}м") }
                            )
                        }
                    }

                    if (sleepTimerState.isActive) {
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TextButton(onClick = {
                                viewModel.extendSleepTimer()
                                showSleepTimerSheet = false
                            }) {
                                Text("+15 мин к таймеру")
                            }
                            TextButton(onClick = {
                                viewModel.cancelSleepTimer()
                                showSleepTimerSheet = false
                            }) {
                                Text("Отключить", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        // Add bookmark dialog
        if (showAddBookmarkDialog) {
            val defaultLabel = "${playbackState.chapterTitle} (поз. ${playbackState.charOffsetInChapter})"
            var label by remember { mutableStateOf(defaultLabel) }
            AlertDialog(
                onDismissRequest = { showAddBookmarkDialog = false },
                title = { Text("Добавить закладку") },
                text = {
                    Column {
                        Text(
                            "Текущая позиция будет сохранена в закладки",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it },
                            label = { Text("Метка") },
                            singleLine = false,
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.addBookmark(label.trim())
                            showAddBookmarkDialog = false
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "Закладка добавлена"
                                )
                            }
                        }
                    ) { Text("Сохранить") }
                },
                dismissButton = {
                    TextButton(onClick = { showAddBookmarkDialog = false }) {
                        Text("Отмена")
                    }
                }
            )
        }
    }
}
