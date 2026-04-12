package com.abook.service

import com.abook.data.db.dao.StatsDao
import com.abook.data.db.entity.ListeningSessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Tracks listening sessions. Call startSession when playback begins
 * and endSession when playback pauses/stops.
 */
class StatsTracker(
    private val statsDao: StatsDao,
    private val scope: CoroutineScope
) {
    private var sessionStart: Long = 0
    private var sessionBookId: String? = null
    private var sessionStartOffset: Long = 0
    private var currentOffset: Long = 0

    fun startSession(bookId: String, currentBookCharOffset: Long) {
        sessionStart = System.currentTimeMillis()
        sessionBookId = bookId
        sessionStartOffset = currentBookCharOffset
        currentOffset = currentBookCharOffset
    }

    fun updateOffset(offset: Long) {
        currentOffset = offset
    }

    fun endSession() {
        val bookId = sessionBookId ?: return
        val endTime = System.currentTimeMillis()
        if (endTime - sessionStart < 5000) {
            // Ignore very short sessions
            sessionBookId = null
            return
        }
        val chars = (currentOffset - sessionStartOffset).toInt().coerceAtLeast(0)
        scope.launch(Dispatchers.IO) {
            statsDao.insertSession(
                ListeningSessionEntity(
                    bookId = bookId,
                    startTime = sessionStart,
                    endTime = endTime,
                    charsRead = chars
                )
            )
        }
        sessionBookId = null
    }
}
