package com.abook.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.abook.data.db.entity.SleepScheduleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SleepScheduleDao {

    @Query("SELECT * FROM sleep_schedules LIMIT 1")
    fun getSchedule(): Flow<SleepScheduleEntity?>

    @Query("SELECT * FROM sleep_schedules WHERE enabled = 1 LIMIT 1")
    suspend fun getActiveSchedule(): SleepScheduleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(schedule: SleepScheduleEntity): Long

    @Update
    suspend fun update(schedule: SleepScheduleEntity)
}
