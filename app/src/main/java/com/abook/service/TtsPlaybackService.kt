package com.abook.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.session.MediaButtonReceiver
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import com.abook.ABookApplication
import com.abook.MainActivity
import com.abook.R
import com.abook.data.db.dao.BookDao
import com.abook.data.db.dao.StatsDao
import com.abook.data.db.entity.ReadingPositionEntity
import com.abook.domain.model.PlaybackState
import com.abook.domain.model.SleepTimerState
import com.abook.service.textprocessing.TextProcessor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class TtsPlaybackService : Service() {

    @Inject lateinit var bookDao: BookDao
    @Inject lateinit var statsDao: StatsDao

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var ttsEngine: TtsEngine
    private lateinit var audioEffects: AudioEffectsManager
    private lateinit var sleepTimerManager: SleepTimerManager
    private lateinit var statsTracker: StatsTracker
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private var volumeBeforeDuck: Float = 1.0f
    private var currentBookId: String? = null
    private var currentChapterIndex: Int = 0
    private var chapters: List<com.abook.data.db.entity.ChapterEntity> = emptyList()
    private var currentChunks: List<TtsEngine.TextChunk> = emptyList()
    private var currentChunkIndex: Int = 0

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    val sleepTimerState: StateFlow<SleepTimerState>
        get() = sleepTimerManager.state

    val speechRateFlow: StateFlow<Float>
        get() = ttsEngine.speechRateFlow

    inner class LocalBinder : Binder() {
        fun getService(): TtsPlaybackService = this@TtsPlaybackService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        ttsEngine = TtsEngine(this)
        audioEffects = AudioEffectsManager()
        sleepTimerManager = SleepTimerManager(this, serviceScope)
        statsTracker = StatsTracker(statsDao, serviceScope)

        sleepTimerManager.onVolumeChange = { volume ->
            ttsEngine.setVolume(volume)
        }
        sleepTimerManager.onTimerExpired = {
            pause()
        }

        setupMediaSession()

        ttsEngine.initialize {
            audioEffects.initialize(ttsEngine.getAudioSessionId())
        }

        ttsEngine.onUtteranceDone = { utteranceId ->
            onUtteranceDone(utteranceId)
        }

        ttsEngine.onRangeStart = { utteranceId, start, end ->
            onRangeStart(utteranceId, start, end)
        }

        ttsEngine.onUtteranceError = { utteranceId ->
            Log.e(TAG, "Utterance error: $utteranceId")
        }
    }

    // --- Playback control ---

    fun playBook(bookId: String, chapterIndex: Int = 0, charOffset: Int = 0) {
        // Idempotency: if already showing this book, don't restart playback.
        // The UI re-triggers playBook on navigation re-entry, which would
        // otherwise interrupt ongoing speech.
        if (currentBookId == bookId && chapters.isNotEmpty()) {
            // Already loaded. Just ensure MediaSession/notification are refreshed.
            updateMediaSession()
            updateNotification()
            return
        }

        serviceScope.launch {
            val book = bookDao.getBook(bookId) ?: return@launch
            chapters = bookDao.getChapters(bookId)
            if (chapters.isEmpty()) return@launch

            currentBookId = bookId
            currentChapterIndex = chapterIndex.coerceIn(0, chapters.size - 1)

            bookDao.updateLastOpened(bookId, System.currentTimeMillis())

            // Use original text lengths for global offset tracking (totalBookChars)
            // since we only need rough proportions for book-level progress bar.
            val totalChars = chapters.sumOf { it.textContent.length.toLong() }
            val currentGlobalOffset = chapters.take(currentChapterIndex)
                .sumOf { it.textContent.length.toLong() } + charOffset

            val currentChapter = chapters.getOrNull(currentChapterIndex)
            val processedLen = currentChapter?.let {
                TextProcessor.DEFAULT.process(it.textContent).length
            } ?: 0
            currentProcessedTextLength = processedLen

            _playbackState.value = PlaybackState(
                isPlaying = true,
                bookId = bookId,
                bookTitle = book.title,
                chapterIndex = currentChapterIndex,
                chapterTitle = currentChapter?.title.orEmpty(),
                totalChapters = chapters.size,
                charOffsetInChapter = charOffset,
                chapterLength = processedLen,
                totalBookChars = totalChars,
                currentBookCharOffset = currentGlobalOffset,
                currentChapterText = currentChapter?.textContent.orEmpty(),
                coverPath = book.coverPath
            )

            requestAudioFocus()
            startForeground(NOTIFICATION_ID, buildNotification())
            statsTracker.startSession(bookId, currentGlobalOffset)
            speakChapter(currentChapterIndex, charOffset)
        }
    }

    // Length of the processed text for the current chapter — used to keep
    // chapterLength in PlaybackState consistent with chunk offsets.
    private var currentProcessedTextLength: Int = 0

    /**
     * Speak the chapter starting EXACTLY at startCharOffset.
     *
     * Important: chunks are built from the whole chapter text, but the first
     * spoken chunk is trimmed to begin at startCharOffset so the forward/back
     * buttons seek precisely instead of snapping to chunk boundaries.
     */
    private fun speakChapter(chapterIndex: Int, startCharOffset: Int = 0) {
        ttsEngine.stop()

        val chapter = chapters.getOrNull(chapterIndex) ?: return
        val processedText = TextProcessor.DEFAULT.process(chapter.textContent)
        currentProcessedTextLength = processedText.length
        currentChunks = ttsEngine.chunkText(processedText)

        // Update chapterLength to match processed text so progress bar and
        // seek clamping are consistent with the offsets from onRangeStart.
        _playbackState.value = _playbackState.value.copy(
            chapterLength = processedText.length
        )

        if (currentChunks.isEmpty()) {
            updateMediaSession()
            return
        }

        // Find the chunk containing startCharOffset
        val containingIdx = currentChunks.indexOfFirst {
            it.charOffset + it.text.length > startCharOffset
        }.let { if (it < 0) currentChunks.size - 1 else it }

        currentChunkIndex = containingIdx

        var anyQueued = false
        for (i in containingIdx until currentChunks.size) {
            val chunk = currentChunks[i]
            val (speakText, speakOffset) = if (i == containingIdx) {
                // Trim the first chunk so it starts exactly at startCharOffset
                val localStart = (startCharOffset - chunk.charOffset).coerceIn(0, chunk.text.length)
                val trimmed = chunk.text.substring(localStart)
                if (trimmed.isBlank()) continue
                trimmed to (chunk.charOffset + localStart)
            } else {
                chunk.text to chunk.charOffset
            }
            val utteranceId = "$currentBookId:$chapterIndex:$speakOffset"
            ttsEngine.speak(speakText, utteranceId)
            anyQueued = true
        }

        // If nothing was queued (offset past end of text), auto-advance to next chapter
        if (!anyQueued && _playbackState.value.isPlaying) {
            if (currentChapterIndex < chapters.size - 1) {
                currentChapterIndex++
                updateChapterState()
                speakChapter(currentChapterIndex)
                return
            } else {
                pause()
            }
        }

        updateMediaSession()
    }

    fun pause() {
        ttsEngine.stop()
        _playbackState.value = _playbackState.value.copy(isPlaying = false)
        updateMediaSession()
        updateNotification()
        savePosition()
        statsTracker.endSession()
        abandonAudioFocus()
    }

    fun resume() {
        val state = _playbackState.value
        if (state.bookId != null) {
            _playbackState.value = state.copy(isPlaying = true)
            requestAudioFocus()
            state.bookId?.let { statsTracker.startSession(it, state.currentBookCharOffset) }
            speakChapter(state.chapterIndex, state.charOffsetInChapter)
            updateNotification()
        }
    }

    fun nextChapter() {
        if (chapters.isEmpty()) return
        if (currentChapterIndex < chapters.size - 1) {
            val wasPlaying = _playbackState.value.isPlaying
            ttsEngine.stop()
            currentChapterIndex++
            updateChapterState()
            if (wasPlaying) speakChapter(currentChapterIndex)
        }
    }

    fun prevChapter() {
        if (chapters.isEmpty()) return
        if (currentChapterIndex > 0) {
            val wasPlaying = _playbackState.value.isPlaying
            ttsEngine.stop()
            currentChapterIndex--
            updateChapterState()
            if (wasPlaying) speakChapter(currentChapterIndex)
        }
    }

    fun seekToChapter(index: Int) {
        if (index in chapters.indices) {
            val wasPlaying = _playbackState.value.isPlaying
            ttsEngine.stop()
            currentChapterIndex = index
            updateChapterState()
            if (wasPlaying) speakChapter(currentChapterIndex)
        }
    }

    fun seekByCharOffset(offsetDelta: Int) {
        if (chapters.isEmpty()) return
        val state = _playbackState.value
        val newOffset = (state.charOffsetInChapter + offsetDelta).coerceIn(0, state.chapterLength)
        // Scale processed offset back to original space for book-level progress
        val originalLen = chapters.getOrNull(currentChapterIndex)?.textContent?.length?.coerceAtLeast(1) ?: 1
        val processedLen = currentProcessedTextLength.coerceAtLeast(1)
        val scaledOffset = newOffset.toLong() * originalLen / processedLen
        val globalOffset = chapters.take(currentChapterIndex)
            .sumOf { it.textContent.length.toLong() } + scaledOffset

        _playbackState.value = state.copy(
            charOffsetInChapter = newOffset,
            currentBookCharOffset = globalOffset
        )

        if (state.isPlaying) {
            speakChapter(currentChapterIndex, newOffset)
        } else {
            ttsEngine.stop()
        }
    }

    fun seekToAbsoluteCharOffset(offset: Int) {
        if (chapters.isEmpty()) return
        val state = _playbackState.value
        val clamped = offset.coerceIn(0, state.chapterLength)
        val originalLen = chapters.getOrNull(currentChapterIndex)?.textContent?.length?.coerceAtLeast(1) ?: 1
        val processedLen = currentProcessedTextLength.coerceAtLeast(1)
        val scaledOffset = clamped.toLong() * originalLen / processedLen
        val globalOffset = chapters.take(currentChapterIndex)
            .sumOf { it.textContent.length.toLong() } + scaledOffset

        _playbackState.value = state.copy(
            charOffsetInChapter = clamped,
            currentBookCharOffset = globalOffset
        )

        if (state.isPlaying) {
            speakChapter(currentChapterIndex, clamped)
        } else {
            ttsEngine.stop()
        }
    }

    fun seekToBookProgress(progress: Float) {
        if (chapters.isEmpty()) return
        val wasPlaying = _playbackState.value.isPlaying
        ttsEngine.stop()

        val totalChars = chapters.sumOf { it.textContent.length.toLong() }
        val target = (progress * totalChars).toLong().coerceIn(0, totalChars)
        var acc = 0L
        for ((i, ch) in chapters.withIndex()) {
            if (acc + ch.textContent.length >= target) {
                val localOffsetOriginal = (target - acc).toInt()
                currentChapterIndex = i
                updateChapterState()

                // Convert original-text offset to processed-text offset
                val originalLen = ch.textContent.length.coerceAtLeast(1)
                val processedLen = currentProcessedTextLength.coerceAtLeast(1)
                val localOffsetProcessed = (localOffsetOriginal.toLong() * processedLen / originalLen).toInt()

                val globalOffset = chapters.take(i).sumOf { c -> c.textContent.length.toLong() } + localOffsetOriginal
                _playbackState.value = _playbackState.value.copy(
                    charOffsetInChapter = localOffsetProcessed,
                    currentBookCharOffset = globalOffset
                )
                if (wasPlaying) speakChapter(i, localOffsetProcessed)
                return
            }
            acc += ch.textContent.length
        }
    }

    fun seekBackOneSentence() {
        if (chapters.isEmpty()) return
        val state = _playbackState.value
        val chapter = chapters.getOrNull(currentChapterIndex) ?: return
        val text = chapter.textContent
        val cur = state.charOffsetInChapter
        if (cur <= 0) return
        // Find last sentence-ending punctuation before current position
        val searchEnd = (cur - 1).coerceAtLeast(0)
        val searchText = text.substring(0, searchEnd)
        val match = Regex("""[.!?…]\s""").findAll(searchText).toList().lastOrNull()
        val newOffset = match?.let { it.range.last + 1 } ?: 0
        seekToAbsoluteCharOffset(newOffset)
    }

    fun getCurrentChapterText(): String =
        chapters.getOrNull(currentChapterIndex)?.textContent.orEmpty()

    /**
     * Re-speak from the current position with updated TTS parameters.
     * Called from VoiceSettings sliders so that speech rate/pitch/volume/pan
     * changes are heard immediately without waiting for the next chunk.
     * No-op if not currently playing.
     */
    fun resyncPlayback() {
        val state = _playbackState.value
        if (!state.isPlaying) return
        if (chapters.isEmpty()) return
        speakChapter(currentChapterIndex, state.charOffsetInChapter)
    }

    private fun updateChapterState() {
        val chapter = chapters.getOrNull(currentChapterIndex) ?: return
        val globalOffset = chapters.take(currentChapterIndex).sumOf { it.textContent.length.toLong() }
        // Pre-compute processed length for consistent progress tracking
        val processedLen = TextProcessor.DEFAULT.process(chapter.textContent).length
        currentProcessedTextLength = processedLen

        _playbackState.value = _playbackState.value.copy(
            chapterIndex = currentChapterIndex,
            chapterTitle = chapter.title,
            charOffsetInChapter = 0,
            chapterLength = processedLen,
            currentBookCharOffset = globalOffset,
            currentChapterText = chapter.textContent
        )
        updateMediaSession()
        updateNotification()
    }

    // --- Callbacks ---

    private fun onUtteranceDone(utteranceId: String) {
        val parts = utteranceId.split(":")
        if (parts.size < 3) return

        val chapterIdx = parts[1].toIntOrNull() ?: return
        val charOffset = parts[2].toIntOrNull() ?: return

        // Determine if this was the last chunk of the chapter.
        //
        // After seek, the first chunk's utteranceId contains a trimmed offset
        // (e.g. 500) which doesn't match the original chunk's charOffset (e.g. 0).
        // So we can't rely on exact match. Instead, check if charOffset falls
        // within or past the last chunk's range.
        val lastChunk = currentChunks.lastOrNull()
        val isLastChunk = if (lastChunk != null) {
            charOffset >= lastChunk.charOffset
        } else {
            true // no chunks → treat as last
        }

        // Also confirm TTS is no longer speaking (queue empty)
        val queueEmpty = !ttsEngine.isSpeaking()

        if (isLastChunk && queueEmpty) {
            // Move to next chapter
            if (currentChapterIndex < chapters.size - 1) {
                currentChapterIndex++
                updateChapterState()
                speakChapter(currentChapterIndex)
            } else {
                // Book finished
                pause()
            }
        }

        savePosition()
    }

    private fun onRangeStart(utteranceId: String, start: Int, end: Int) {
        val parts = utteranceId.split(":")
        if (parts.size < 3) return
        val chunkCharOffset = parts[2].toIntOrNull() ?: return

        val globalChapterOffset = chunkCharOffset + start
        val globalChapterEnd = chunkCharOffset + end

        // Scale processed-text offset to original-text space for book-level progress.
        // totalBookChars uses original lengths, so book progress must be in the same space.
        val originalLen = chapters.getOrNull(currentChapterIndex)?.textContent?.length ?: 1
        val processedLen = currentProcessedTextLength.coerceAtLeast(1)
        val scaledChapterOffset = (globalChapterOffset.toLong() * originalLen / processedLen)
        val globalBookOffset = chapters.take(currentChapterIndex)
            .sumOf { it.textContent.length.toLong() } + scaledChapterOffset

        _playbackState.value = _playbackState.value.copy(
            charOffsetInChapter = globalChapterOffset,
            currentBookCharOffset = globalBookOffset,
            currentWordStart = globalChapterOffset,
            currentWordEnd = globalChapterEnd
        )
        statsTracker.updateOffset(globalBookOffset)
    }

    // --- TTS settings access ---

    fun getTtsEngine(): TtsEngine = ttsEngine
    fun getAudioEffectsManager(): AudioEffectsManager = audioEffects

    fun reinitializeTts(enginePackage: String) {
        // Preserve current parameters so they survive engine switch
        val savedRate = ttsEngine.getCurrentSpeechRate()
        val savedPitch = ttsEngine.getCurrentPitch()
        val savedVolume = ttsEngine.getCurrentVolume()
        val savedPan = ttsEngine.getCurrentPan()
        val savedSsmlEnabled = ttsEngine.getCurrentSsmlEnabled()
        val savedSsmlPause = ttsEngine.getCurrentSsmlPauseMs()

        ttsEngine.stop()
        ttsEngine.shutdown()
        ttsEngine = TtsEngine(this)
        ttsEngine.onUtteranceDone = { id -> onUtteranceDone(id) }
        ttsEngine.onRangeStart = { id, s, e -> onRangeStart(id, s, e) }
        ttsEngine.onUtteranceError = { id -> Log.e(TAG, "Utterance error: $id") }
        ttsEngine.initialize(enginePackage) {
            // Restore saved parameters on the fresh engine
            ttsEngine.setSpeechRate(savedRate)
            ttsEngine.setPitch(savedPitch)
            ttsEngine.setVolume(savedVolume)
            ttsEngine.setPan(savedPan)
            ttsEngine.setSsmlEnabled(savedSsmlEnabled)
            ttsEngine.setSsmlPauseMs(savedSsmlPause)
            audioEffects.initialize(ttsEngine.getAudioSessionId())
        }
    }

    // --- Sleep timer ---

    fun startSleepTimer(minutes: Int) {
        sleepTimerManager.start(minutes, ttsEngine.getCurrentVolume())
    }

    fun extendSleepTimer(minutes: Int = 15) {
        sleepTimerManager.extend(minutes)
    }

    fun cancelSleepTimer() {
        sleepTimerManager.cancel()
    }

    // --- Position persistence ---

    private fun savePosition() {
        val state = _playbackState.value
        val bookId = state.bookId ?: return
        serviceScope.launch(Dispatchers.IO) {
            bookDao.savePosition(
                ReadingPositionEntity(
                    bookId = bookId,
                    chapterIndex = state.chapterIndex,
                    charOffsetInChapter = state.charOffsetInChapter,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    // --- Audio focus ---

    private fun requestAudioFocus(): Boolean {
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS -> pause()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        // Save actual volume before ducking so it can be restored
                        volumeBeforeDuck = ttsEngine.getCurrentVolume()
                        ttsEngine.setVolume(volumeBeforeDuck * 0.3f)
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        // Restore the user's volume, not always 1.0
                        ttsEngine.setVolume(volumeBeforeDuck)
                    }
                }
            }
            .build()

        audioFocusRequest = focusRequest
        return audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    // --- MediaSession ---

    private fun setupMediaSession() {
        val mediaButtonReceiver = ComponentName(this, MediaButtonReceiver::class.java)
        mediaSession = MediaSessionCompat(this, "ABookMediaSession", mediaButtonReceiver, null).apply {
            @Suppress("DEPRECATION")
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onStop() {
                    pause()
                    stopSelf()
                }
                override fun onSkipToNext() = nextChapter()
                override fun onSkipToPrevious() = prevChapter()
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    // Let the default handler route PLAY_PAUSE to onPlay/onPause
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Route media button intents through MediaButtonReceiver
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        return handleCommand(intent, startId)
    }

    private fun handleCommand(intent: Intent?, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> resume()
            ACTION_PAUSE -> pause()
            ACTION_STOP -> {
                pause()
                stopSelf()
            }
            ACTION_NEXT_CHAPTER -> nextChapter()
            ACTION_PREV_CHAPTER -> prevChapter()
            ACTION_START_SLEEP_TIMER -> {
                val duration = intent.getIntExtra(SleepTimerAlarmReceiver.EXTRA_DURATION_MINUTES, 30)
                startSleepTimer(duration)
            }
        }
        return START_STICKY
    }

    private fun updateMediaSession() {
        val state = _playbackState.value
        val pbState = if (state.isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP
                )
                .setState(pbState, state.charOffsetInChapter.toLong(), 1.0f)
                .build()
        )

        val coverBitmap: Bitmap? = state.coverPath?.let { path ->
            try {
                if (File(path).exists()) BitmapFactory.decodeFile(path) else null
            } catch (_: Exception) { null }
        }

        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.chapterTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, state.bookTitle)
                .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, state.totalChapters.toLong())
                .putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, (state.chapterIndex + 1).toLong())
                .apply { if (coverBitmap != null) putBitmap(MediaMetadataCompat.METADATA_KEY_ART, coverBitmap) }
                .build()
        )
    }

    // --- Notification ---

    private fun buildNotification(): Notification {
        val state = _playbackState.value

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = if (state.isPlaying) {
            NotificationCompat.Action(
                R.drawable.ic_notif_pause, "Пауза",
                buildServicePendingIntent(ACTION_PAUSE, 1)
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_notif_play, "Воспроизвести",
                buildServicePendingIntent(ACTION_PLAY, 1)
            )
        }

        val coverBitmap: Bitmap? = state.coverPath?.let { path ->
            try {
                if (File(path).exists()) BitmapFactory.decodeFile(path) else null
            } catch (_: Exception) { null }
        }

        val progressPercent = (state.bookProgress * 100).toInt()

        return NotificationCompat.Builder(this, ABookApplication.CHANNEL_PLAYBACK)
            .setContentTitle(state.bookTitle)
            .setContentText(state.chapterTitle)
            .setSubText("$progressPercent%")
            .setSmallIcon(R.drawable.ic_notif_small)
            .apply { if (coverBitmap != null) setLargeIcon(coverBitmap) }
            .setContentIntent(contentIntent)
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_notif_prev, "Назад",
                    buildServicePendingIntent(ACTION_PREV_CHAPTER, 0)
                )
            )
            .addAction(playPauseAction)
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_notif_next, "Вперёд",
                    buildServicePendingIntent(ACTION_NEXT_CHAPTER, 2)
                )
            )
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_notif_stop, "Стоп",
                    buildServicePendingIntent(ACTION_STOP, 3)
                )
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(state.isPlaying)
            .setSilent(true)
            .build()
    }

    private fun buildServicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, TtsPlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateNotification() {
        val notification = buildNotification()
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        savePosition()
        // Clear callbacks to prevent stale closure access after destruction
        sleepTimerManager.onVolumeChange = null
        sleepTimerManager.onTimerExpired = null
        ttsEngine.onUtteranceStart = null
        ttsEngine.onUtteranceDone = null
        ttsEngine.onUtteranceError = null
        ttsEngine.onRangeStart = null
        sleepTimerManager.release()
        audioEffects.release()
        ttsEngine.shutdown()
        mediaSession.release()
        abandonAudioFocus()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TtsPlaybackService"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY = "com.abook.action.PLAY"
        const val ACTION_PAUSE = "com.abook.action.PAUSE"
        const val ACTION_STOP = "com.abook.action.STOP"
        const val ACTION_NEXT_CHAPTER = "com.abook.action.NEXT_CHAPTER"
        const val ACTION_PREV_CHAPTER = "com.abook.action.PREV_CHAPTER"
        const val ACTION_START_SLEEP_TIMER = "com.abook.action.START_SLEEP_TIMER"
    }
}
