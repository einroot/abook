package com.abook.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.abook.data.db.entity.ListeningSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StatsDao {

    @Insert
    suspend fun insertSession(session: ListeningSessionEntity): Long

    @Query("SELECT * FROM listening_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<ListeningSessionEntity>>

    @Query("SELECT SUM(endTime - startTime) FROM listening_sessions")
    fun getTotalListeningTime(): Flow<Long?>

    @Query("SELECT COUNT(*) FROM listening_sessions WHERE bookId = :bookId")
    suspend fun getSessionCount(bookId: String): Int

    @Query("SELECT SUM(endTime - startTime) FROM listening_sessions WHERE startTime >= :sinceEpoch")
    suspend fun getListeningTimeSince(sinceEpoch: Long): Long?
}
