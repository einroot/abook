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
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Binder
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.session.MediaButtonReceiver
import android.util.Log
import android.view.KeyEvent
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // Watchdog: timestamp of the last TTS progress event (onStart / onRangeStart /
    // onDone / onError). Used to detect a frozen state — if playback state claims
    // isPlaying=true but TTS has made no progress for a long time, we force-advance
    // to the next chapter. This is a last-resort safety net that ensures playback
    // CANNOT silently freeze, regardless of parser output or TTS engine quirks.
    private var lastTtsProgressAt: Long = 0L
    private var watchdogJob: Job? = null

    // Silent AudioTrack played from our process to register as the real
    // "audio producer" with Android's media routing system. Without it,
    // Android TTS plays through the com.google.android.tts process and the
    // audio gets attributed to Google TTS, not to our app — our MediaSession
    // is shown PLAYING but we lose headset button priority to whatever app
    // previously held audio focus.
    private var silentAudioTrack: AudioTrack? = null
    private var silentAudioJob: Job? = null

    // Tracks the in-flight speakChapter() coroutine so pause() / stop() can
    // cancel it. Without cancellation, text processing finished after pause
    // and queued fresh utterances — TTS kept talking with isPlaying=false.
    private var currentSpeakJob: Job? = null

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
            markTtsProgress()
            onUtteranceDone(utteranceId)
        }

        ttsEngine.onUtteranceStart = {
            markTtsProgress()
        }

        ttsEngine.onRangeStart = { utteranceId, start, end ->
            markTtsProgress()
            onRangeStart(utteranceId, start, end)
        }

        ttsEngine.onUtteranceError = { utteranceId ->
            Log.e(TAG, "Utterance error: $utteranceId")
            markTtsProgress()
            // Treat error same as done — advance to next chunk/chapter
            // so playback doesn't silently freeze on TTS failures
            onUtteranceDone(utteranceId)
        }
    }

    /**
     * Mark that TTS just made progress (start / range / done / error). The
     * watchdog uses this to detect a frozen state.
     */
    private fun markTtsProgress() {
        lastTtsProgressAt = System.currentTimeMillis()
    }

    /**
     * Start the stall-watchdog. Safe to call multiple times — a running job
     * is reused. Stops automatically when isPlaying becomes false.
     */
    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        markTtsProgress()
        watchdogJob = serviceScope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                val state = _playbackState.value
                if (!state.isPlaying) continue
                val sinceProgress = System.currentTimeMillis() - lastTtsProgressAt
                if (sinceProgress < WATCHDOG_STALL_MS) continue

                Log.w(
                    TAG,
                    "Watchdog: no TTS progress for ${sinceProgress}ms on chapter " +
                        "$currentChapterIndex. Forcing advance."
                )
                markTtsProgress() // reset so we don't loop-fire instantly
                if (currentChapterIndex < chapters.size - 1) {
                    currentChapterIndex++
                    updateChapterState()
                    speakChapter(currentChapterIndex)
                } else {
                    pause()
                }
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    // --- Silent audio anchor ---

    /**
     * Start a silent AudioTrack from THIS process. Android's media routing
     * asks "which app is currently producing audio?" and picks that app's
     * MediaSession. If only the Google TTS process is producing audio, we
     * lose — TTS is attributed to com.google.android.tts, not to us.
     *
     * A silent track at zero volume is inaudible but counts as our process
     * playing an AudioTrack, which pins us as the active audio producer
     * across the whole playback session (including pauses).
     */
    private fun startSilentAudioAnchor() {
        if (silentAudioTrack != null || silentAudioJob?.isActive == true) return
        // Build + play the AudioTrack off the main thread — AudioTrack.Builder
        // construction and open can block for ~100-200 ms on some devices,
        // which manifests as the Play button being "stuck" right after tap.
        silentAudioJob = serviceScope.launch(Dispatchers.IO) {
            try {
                val sampleRate = 44100
                val bufSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.setVolume(0f)
                track.play()
                silentAudioTrack = track
                Log.d(TAG, "Silent audio anchor started")

                val silence = ShortArray(bufSize / 2)
                while (isActive) {
                    val t = silentAudioTrack ?: break
                    try {
                        val written = t.write(silence, 0, silence.size)
                        if (written < 0) break
                    } catch (_: Exception) {
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Silent audio anchor failed", e)
            }
        }
    }

    private fun stopSilentAudioAnchor() {
        silentAudioJob?.cancel()
        silentAudioJob = null
        try {
            silentAudioTrack?.stop()
            silentAudioTrack?.release()
        } catch (_: Exception) {}
        silentAudioTrack = null
        Log.d(TAG, "Silent audio anchor stopped")
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
            // Wait for TTS engine to be ready before attempting to speak.
            // Without this, speak() silently drops text and user sees
            // "playing" state but hears nothing.
            ttsEngine.initState.first { it }

            val book = bookDao.getBook(bookId) ?: return@launch
            chapters = bookDao.getChapters(bookId)
            if (chapters.isEmpty()) return@launch

            currentBookId = bookId
            currentChapterIndex = chapterIndex.coerceIn(0, chapters.size - 1)

            bookDao.updateLastOpened(bookId, System.currentTimeMillis())

            val totalChars = chapters.sumOf { it.textContent.length.toLong() }

            val currentChapter = chapters.getOrNull(currentChapterIndex)
            val processedLen = currentChapter?.let {
                TextProcessor.DEFAULT.process(it.textContent).length
            } ?: 0
            currentProcessedTextLength = processedLen

            // charOffset is in processed-text space (from saved position).
            // Scale to original space for consistent book-level progress.
            val originalLen = currentChapter?.textContent?.length?.coerceAtLeast(1) ?: 1
            val pLen = processedLen.coerceAtLeast(1)
            val scaledCharOffset = charOffset.toLong() * originalLen / pLen
            val currentGlobalOffset = chapters.take(currentChapterIndex)
                .sumOf { it.textContent.length.toLong() } + scaledCharOffset

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
            // Anchor ourselves as an audio producer in the system so headset
            // button routing picks us. Stays on through pause/resume.
            startSilentAudioAnchor()
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
        // Cancel any previous in-flight speakChapter coroutine — if it
        // hadn't reached the tts.speak() loop yet, it would queue fresh
        // utterances after we've stopped, defeating pause/seek.
        currentSpeakJob?.cancel()
        ttsEngine.stop()

        val chapter = chapters.getOrNull(chapterIndex) ?: return

        // TextProcessor (regex-heavy) and chunkText can take 100–300ms on a
        // long chapter. Running them on the main thread makes the Play/Pause
        // button feel frozen after every resume. Do the heavy work on
        // Dispatchers.Default, then hop back to Main to queue to TTS and
        // update state.
        currentSpeakJob = serviceScope.launch {
            val processedText: String
            val chunks: List<TtsEngine.TextChunk>
            withContext(Dispatchers.Default) {
                processedText = TextProcessor.DEFAULT.process(chapter.textContent)
                chunks = ttsEngine.chunkText(processedText)
            }

            // If we were cancelled / paused while processing the text, bail
            // out before touching TTS so pause actually sticks.
            if (!isActive) return@launch
            if (!_playbackState.value.isPlaying) return@launch

            currentProcessedTextLength = processedText.length
            currentChunks = chunks
            _playbackState.update { it.copy(chapterLength = processedText.length) }

            if (currentChunks.isEmpty()) {
                // Empty chapter (e.g., FB2 section with only <title> and no content,
                // or a divider chapter). Without this auto-advance, playback would
                // silently freeze on the empty chapter with no way forward.
                if (_playbackState.value.isPlaying) {
                    if (chapterIndex < chapters.size - 1) {
                        currentChapterIndex = chapterIndex + 1
                        updateChapterState()
                        speakChapter(currentChapterIndex)
                        return@launch
                    } else {
                        pause()
                        return@launch
                    }
                }
                updateMediaSession()
                return@launch
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

            if (anyQueued) startWatchdog()

            // If nothing was queued (offset past end of text), auto-advance to next chapter
            if (!anyQueued && _playbackState.value.isPlaying) {
                if (currentChapterIndex < chapters.size - 1) {
                    currentChapterIndex++
                    updateChapterState()
                    speakChapter(currentChapterIndex)
                    return@launch
                } else {
                    pause()
                }
            }

            updateMediaSession()
        }
    }

    fun pause() {
        // Cancel any pending speakChapter coroutine FIRST — otherwise its
        // post-processing tail might call ttsEngine.speak() right after we
        // set isPlaying=false, making pause ineffective.
        currentSpeakJob?.cancel()
        currentSpeakJob = null
        ttsEngine.stop()
        stopWatchdog()
        _playbackState.update { it.copy(isPlaying = false) }
        updateMediaSession()
        updateNotification()
        savePosition()
        statsTracker.endSession()
        // DO NOT abandon audio focus on pause. Holding focus during pause is
        // how Spotify/YouTube Music/Pocket Casts keep headset buttons pointed
        // at themselves. Releasing it here hands priority to the "last active
        // audio app" heuristic, which is exactly what lets residual/dead
        // sessions intercept the next headset Play press. Focus is released
        // only on explicit stop/destroy.
    }

    fun resume() {
        val state = _playbackState.value
        val bookId = state.bookId ?: return
        if (chapters.isEmpty()) return  // No book loaded

        // If TTS not yet initialized, wait for it then resume
        if (!ttsEngine.initState.value) {
            serviceScope.launch {
                ttsEngine.initState.first { it }
                doResume(bookId, state)
            }
            return
        }
        doResume(bookId, state)
    }

    private fun doResume(bookId: String, state: PlaybackState) {
        _playbackState.update { it.copy(isPlaying = true) }
        requestAudioFocus()
        statsTracker.startSession(bookId, state.currentBookCharOffset)
        speakChapter(state.chapterIndex, state.charOffsetInChapter)
        updateNotification()
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
        val wasPlaying = _playbackState.value.isPlaying
        val currentOffset = _playbackState.value.charOffsetInChapter

        // Music player behavior: if more than 3% into the chapter, first go to
        // beginning of current chapter. Only on second press go to previous.
        if (currentOffset > (_playbackState.value.chapterLength * 0.03)) {
            ttsEngine.stop()
            updateChapterState()  // resets charOffsetInChapter to 0
            if (wasPlaying) speakChapter(currentChapterIndex)
        } else if (currentChapterIndex > 0) {
            ttsEngine.stop()
            currentChapterIndex--
            updateChapterState()
            if (wasPlaying) speakChapter(currentChapterIndex)
        }
        // If at chapter 0, offset 0 → nothing to do (already at the very start)
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

        _playbackState.update {
            it.copy(charOffsetInChapter = newOffset, currentBookCharOffset = globalOffset)
        }

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

        _playbackState.update {
            it.copy(charOffsetInChapter = clamped, currentBookCharOffset = globalOffset)
        }

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
                _playbackState.update {
                    it.copy(charOffsetInChapter = localOffsetProcessed, currentBookCharOffset = globalOffset)
                }
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

        _playbackState.update {
            it.copy(
                chapterIndex = currentChapterIndex,
                chapterTitle = chapter.title,
                charOffsetInChapter = 0,
                chapterLength = processedLen,
                currentBookCharOffset = globalOffset,
                currentChapterText = chapter.textContent
            )
        }
        updateMediaSession()
        updateNotification()
    }

    // --- Callbacks ---

    private var resumeAfterPreview = false

    fun setResumeAfterPreview(shouldResume: Boolean) {
        resumeAfterPreview = shouldResume
    }

    private fun onUtteranceDone(utteranceId: String) {
        // Handle preview completion — auto-resume book if it was playing
        if (utteranceId == "preview") {
            if (resumeAfterPreview) {
                resumeAfterPreview = false
                resume()
            }
            return
        }

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

        _playbackState.update {
            it.copy(
                charOffsetInChapter = globalChapterOffset,
                currentBookCharOffset = globalBookOffset,
                currentWordStart = globalChapterOffset,
                currentWordEnd = globalChapterEnd
            )
        }
        statsTracker.updateOffset(globalBookOffset)
    }

    // --- TTS settings access ---

    fun getTtsEngine(): TtsEngine = ttsEngine
    fun getAudioEffectsManager(): AudioEffectsManager = audioEffects

    fun reinitializeTts(enginePackage: String, onReady: (() -> Unit)? = null) {
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
        ttsEngine.onUtteranceError = { id ->
            Log.e(TAG, "Utterance error: $id")
            onUtteranceDone(id)
        }
        ttsEngine.initialize(enginePackage) {
            // Restore saved parameters on the fresh engine
            ttsEngine.setSpeechRate(savedRate)
            ttsEngine.setPitch(savedPitch)
            ttsEngine.setVolume(savedVolume)
            ttsEngine.setPan(savedPan)
            ttsEngine.setSsmlEnabled(savedSsmlEnabled)
            ttsEngine.setSsmlPauseMs(savedSsmlPause)
            audioEffects.initialize(ttsEngine.getAudioSessionId())
            onReady?.invoke()
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
        // Build an explicit PendingIntent for MediaButtonReceiver. On Android
        // 12+ the legacy ComponentName form is unreliable — modern routing
        // requires a PendingIntent. Also required for media buttons to reach
        // us when the service is already running in the background.
        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            setClass(this@TtsPlaybackService, MediaButtonReceiver::class.java)
        }
        val mediaButtonPi = PendingIntent.getBroadcast(
            this, 0, mediaButtonIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSessionCompat(this, "ABookMediaSession").apply {
            // Preferred way to tell the system where to deliver media buttons
            // when our session is inactive or the service is not running.
            setMediaButtonReceiver(mediaButtonPi)

            @Suppress("DEPRECATION")
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    Log.d(TAG, "MediaSession.onPlay")
                    if (currentBookId == null || chapters.isEmpty()) {
                        resumeLastPlayedBook()
                    } else {
                        resume()
                    }
                }
                override fun onPause() {
                    Log.d(TAG, "MediaSession.onPause")
                    pause()
                }
                override fun onStop() {
                    Log.d(TAG, "MediaSession.onStop")
                    pause()
                    // Do NOT stopSelf() — keep the session alive so the next
                    // headset Play press still reaches us.
                }
                override fun onSkipToNext() {
                    Log.d(TAG, "MediaSession.onSkipToNext")
                    nextChapter()
                }
                override fun onSkipToPrevious() {
                    Log.d(TAG, "MediaSession.onSkipToPrevious")
                    prevChapter()
                }
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    // Decode the KeyEvent ourselves first — on some devices
                    // (seen on a few Samsung and Chinese OEM builds)
                    // super.onMediaButtonEvent() silently fails to dispatch
                    // PLAY_PAUSE to onPlay/onPause when the session's
                    // PlaybackState hasn't fully synced. Handling the event
                    // manually removes that failure mode entirely.
                    val keyEvent: KeyEvent? =
                        mediaButtonEvent?.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    Log.d(TAG, "onMediaButtonEvent: key=${keyEvent?.keyCode} action=${keyEvent?.action}")

                    if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                        val isPlaying = _playbackState.value.isPlaying
                        when (keyEvent.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                            KeyEvent.KEYCODE_HEADSETHOOK -> {
                                if (isPlaying) pause()
                                else if (currentBookId == null || chapters.isEmpty()) resumeLastPlayedBook()
                                else resume()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                if (currentBookId == null || chapters.isEmpty()) resumeLastPlayedBook()
                                else resume()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                pause()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_STOP -> {
                                pause()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                                nextChapter()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                prevChapter()
                                return true
                            }
                        }
                    }
                    // Fallback: let super try if we didn't match the key.
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })
            isActive = true
        }
        // Publish an initial PlaybackState immediately — without it Bluetooth
        // stacks on some Android versions ignore the session.
        updateMediaSession()
    }

    /**
     * Load the most-recently-played book from DB and resume it. Used when a
     * headset / Bluetooth Play is pressed on a fresh service instance where
     * nothing has been loaded yet.
     */
    private fun resumeLastPlayedBook() {
        serviceScope.launch {
            try {
                val book = bookDao.getLastOpenedBook() ?: return@launch
                val pos = bookDao.getPosition(book.id)
                playBook(
                    book.id,
                    pos?.chapterIndex ?: 0,
                    pos?.charOffsetInChapter ?: 0
                )
            } catch (e: Exception) {
                Log.e(TAG, "resumeLastPlayedBook failed", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // CRITICAL: When started via startForegroundService() (from MediaButtonReceiver
        // or SleepTimerAlarmReceiver), we MUST call startForeground() within 5 seconds
        // or Android kills the process. Do it immediately for ALL intents to be safe.
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
            // May fail if notification channel not ready; non-fatal for non-foreground starts
        }

        Log.d(TAG, "onStartCommand action=${intent?.action} extras=${intent?.extras?.keySet()}")

        // Route media button intents through MediaButtonReceiver. This decodes
        // the KeyEvent and calls mediaSession.controller.dispatchMediaButtonEvent
        // which ends up in our MediaSessionCompat.Callback.
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            val keyEvent: KeyEvent? = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            Log.d(TAG, "MEDIA_BUTTON intent: keyCode=${keyEvent?.keyCode} action=${keyEvent?.action}")
            val dispatched = MediaButtonReceiver.handleIntent(mediaSession, intent)
            Log.d(TAG, "MediaButtonReceiver.handleIntent -> $dispatched")
        }
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

        // Re-assert active state — on some Android builds MediaSession can drop
        // to inactive after audio focus loss, which would stop headset buttons
        // from routing to us.
        if (!mediaSession.isActive) mediaSession.isActive = true

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SEEK_TO
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

        // Transport-control PendingIntents go through MediaButtonReceiver so
        // Android ties this notification to our MediaSession. That elevates
        // our session's priority when the user presses Play on a headset —
        // without this link, another recently-used media app can intercept.
        val playPauseAction = if (state.isPlaying) {
            NotificationCompat.Action(
                R.drawable.ic_notif_pause, "Пауза",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_PLAY_PAUSE
                )
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_notif_play, "Воспроизвести",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_PLAY_PAUSE
                )
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
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    )
                )
            )
            .addAction(playPauseAction)
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_notif_next, "Вперёд",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    )
                )
            )
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_notif_stop, "Стоп",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this, PlaybackStateCompat.ACTION_STOP
                    )
                )
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
                    // Hook Stop action for MediaStyle's swipe-to-dismiss and
                    // the compact "X" button. Ties the cancellation to our
                    // session for Android's media UI.
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(
                        MediaButtonReceiver.buildMediaButtonPendingIntent(
                            this, PlaybackStateCompat.ACTION_STOP
                        )
                    )
            )
            // Keep the notification ongoing whenever a book is loaded — not
            // only while playing. If the user swipes it away during pause,
            // the service leaves foreground, the MediaSession goes inactive
            // and headset buttons stop routing to us (other apps win).
            .setOngoing(state.bookId != null)
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
        currentSpeakJob?.cancel()
        currentSpeakJob = null
        sleepTimerManager.release()
        audioEffects.release()
        ttsEngine.shutdown()
        stopSilentAudioAnchor()
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

        // Watchdog: if TTS hasn't produced any progress event for this long while
        // playback is supposed to be active, force-advance to the next chapter.
        // This is a last-resort safety net so playback CANNOT silently freeze,
        // regardless of parser output, TTS engine quirks, or content edge-cases.
        private const val WATCHDOG_STALL_MS = 20_000L
        private const val WATCHDOG_INTERVAL_MS = 5_000L
    }
}
