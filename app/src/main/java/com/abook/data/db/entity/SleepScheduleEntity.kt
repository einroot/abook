package com.abook.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sleep_schedules")
data class SleepScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val enabled: Boolean = false,
    val triggerTimeHour: Int = 23,
    val triggerTimeMinute: Int = 0,
    val durationMinutes: Int = 30,
    val daysOfWeek: String = "1,2,3,4,5,6,7",
    val fadeOutEnabled: Boolean = true
)
