package com.abook.domain.model

data class PlaybackState(
    val isPlaying: Boolean = false,
    val bookId: String? = null,
    val bookTitle: String = "",
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val totalChapters: Int = 0,
    val charOffsetInChapter: Int = 0,
    val chapterLength: Int = 0,
    val totalBookChars: Long = 0,
    val currentBookCharOffset: Long = 0,
    val currentWordStart: Int = 0,
    val currentWordEnd: Int = 0,
    val currentChapterText: String = "",
    val coverPath: String? = null
) {
    val chapterProgress: Float
        get() = if (chapterLength > 0) charOffsetInChapter.toFloat() / chapterLength else 0f

    val bookProgress: Float
        get() = if (totalBookChars > 0) currentBookCharOffset.toFloat() / totalBookChars else 0f
}

data class SleepTimerState(
    val isActive: Boolean = false,
    val remainingSeconds: Int = 0,
    val isFadingOut: Boolean = false
) {
    val remainingMinutes: Int get() = remainingSeconds / 60
    val remainingSecondsInMinute: Int get() = remainingSeconds % 60

    val displayTime: String
        get() = "%d:%02d".format(remainingMinutes, remainingSecondsInMinute)
}
