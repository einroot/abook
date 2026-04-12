package com.abook.ui.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abook.data.db.dao.BookDao
import com.abook.data.db.dao.BookmarkDao
import com.abook.data.db.entity.BookmarkEntity
import com.abook.data.preferences.AppPreferences
import com.abook.domain.model.PlaybackState
import com.abook.domain.model.SleepTimerState
import com.abook.service.TtsPlaybackService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookDao: BookDao,
    private val bookmarkDao: BookmarkDao,
    appPreferences: AppPreferences
) : ViewModel() {

    val seekShortSeconds: StateFlow<Int> = appPreferences.seekShortSeconds
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences.DEFAULT_SEEK_SHORT)
    val seekLongSeconds: StateFlow<Int> = appPreferences.seekLongSeconds
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences.DEFAULT_SEEK_LONG)

    private var service: TtsPlaybackService? = null
    private var isBound = false

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _sleepTimerState = MutableStateFlow(SleepTimerState())
    val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState.asStateFlow()

    private val _speechRate = MutableStateFlow(1.0f)
    val speechRate: StateFlow<Float> = _speechRate.asStateFlow()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? TtsPlaybackService.LocalBinder ?: return
            service = localBinder.getService()
            isBound = true

            viewModelScope.launch {
                service?.playbackState?.collect { state ->
                    _playbackState.value = state
                }
            }
            viewModelScope.launch {
                service?.sleepTimerState?.collect { state ->
                    _sleepTimerState.value = state
                }
            }
            viewModelScope.launch {
                service?.speechRateFlow?.collect { rate ->
                    _speechRate.value = rate
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
        }
    }

    init {
        bindService()
    }

    private fun bindService() {
        val intent = Intent(context, TtsPlaybackService::class.java)
        context.startService(intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun playBook(bookId: String) {
        viewModelScope.launch {
            val position = bookDao.getPosition(bookId)
            service?.playBook(
                bookId = bookId,
                chapterIndex = position?.chapterIndex ?: 0,
                charOffset = position?.charOffsetInChapter ?: 0
            )
        }
    }

    fun togglePlayPause() {
        val state = _playbackState.value
        if (state.isPlaying) {
            service?.pause()
        } else {
            service?.resume()
        }
    }

    fun nextChapter() = service?.nextChapter()
    fun prevChapter() = service?.prevChapter()
    fun seekToChapter(index: Int) = service?.seekToChapter(index)

    fun seekByCharOffset(delta: Int) = service?.seekByCharOffset(delta)
    fun seekToCharOffset(offset: Int) = service?.seekToAbsoluteCharOffset(offset)

    /**
     * Seek by a time duration in seconds. At base speech rate ~180 WPM
     * we have ~15 characters per second (avg word 5 chars + 1 space).
     * Positive seconds seek forward, negative — backward.
     */
    fun seekBySeconds(seconds: Int) {
        val chars = seconds * 15
        service?.seekByCharOffset(chars)
    }
    fun seekToBookProgress(progress: Float) = service?.seekToBookProgress(progress)
    fun seekBackOneSentence() = service?.seekBackOneSentence()

    fun startSleepTimer(minutes: Int) = service?.startSleepTimer(minutes)
    fun extendSleepTimer() = service?.extendSleepTimer()
    fun cancelSleepTimer() = service?.cancelSleepTimer()

    fun addBookmark(label: String = "") {
        val state = _playbackState.value
        val bookId = state.bookId ?: return
        viewModelScope.launch {
            bookmarkDao.insert(
                BookmarkEntity(
                    bookId = bookId,
                    chapterIndex = state.chapterIndex,
                    charOffsetInChapter = state.charOffsetInChapter,
                    label = label.ifBlank { "${state.chapterTitle} (${state.charOffsetInChapter})" },
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Estimate remaining time based on the current speech rate.
     *
     * Base speed is approximately 230 words per minute for Google TTS at 1.0×.
     * Average word length is ~5.5 characters (incl. spaces/punct, biased to Russian).
     * The current TTS speech rate is read live from the service so the estimate
     * updates immediately when the user changes the slider in voice settings.
     */
    fun estimateRemainingTime(): String {
        val state = _playbackState.value
        val remainingChars = state.totalBookChars - state.currentBookCharOffset
        if (remainingChars <= 0) return "0м"
        val rate = _speechRate.value.coerceAtLeast(0.1f)
        val baseWpm = 230f
        val effectiveWpm = baseWpm * rate
        val charsPerMinute = effectiveWpm * 5.5f
        val minutes = (remainingChars / charsPerMinute).toInt()
        val hours = minutes / 60
        val mins = minutes % 60
        return if (hours > 0) "${hours}ч ${mins}м" else "${mins}м"
    }

    override fun onCleared() {
        if (isBound) {
            context.unbindService(connection)
            isBound = false
        }
        super.onCleared()
    }
}
