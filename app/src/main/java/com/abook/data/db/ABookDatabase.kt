package com.abook.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.abook.data.db.dao.BookDao
import com.abook.data.db.dao.BookmarkDao
import com.abook.data.db.dao.SleepScheduleDao
import com.abook.data.db.dao.StatsDao
import com.abook.data.db.dao.VoiceProfileDao
import com.abook.data.db.entity.BookEntity
import com.abook.data.db.entity.BookmarkEntity
import com.abook.data.db.entity.ChapterEntity
import com.abook.data.db.entity.ListeningSessionEntity
import com.abook.data.db.entity.ReadingPositionEntity
import com.abook.data.db.entity.SleepScheduleEntity
import com.abook.data.db.entity.VoiceProfileEntity

@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        ReadingPositionEntity::class,
        VoiceProfileEntity::class,
        SleepScheduleEntity::class,
        BookmarkEntity::class,
        ListeningSessionEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class ABookDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun voiceProfileDao(): VoiceProfileDao
    abstract fun sleepScheduleDao(): SleepScheduleDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun statsDao(): StatsDao
}
