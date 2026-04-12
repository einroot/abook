package com.abook.di

import android.content.Context
import androidx.room.Room
import com.abook.data.db.ABookDatabase
import com.abook.data.db.dao.BookDao
import com.abook.data.db.dao.BookmarkDao
import com.abook.data.db.dao.SleepScheduleDao
import com.abook.data.db.dao.VoiceProfileDao
import com.abook.data.preferences.AppPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ABookDatabase {
        return Room.databaseBuilder(
            context,
            ABookDatabase::class.java,
            "abook.db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideBookDao(db: ABookDatabase): BookDao = db.bookDao()

    @Provides
    fun provideVoiceProfileDao(db: ABookDatabase): VoiceProfileDao = db.voiceProfileDao()

    @Provides
    fun provideSleepScheduleDao(db: ABookDatabase): SleepScheduleDao = db.sleepScheduleDao()

    @Provides
    fun provideBookmarkDao(db: ABookDatabase): BookmarkDao = db.bookmarkDao()

    @Provides
    fun provideStatsDao(db: ABookDatabase): com.abook.data.db.dao.StatsDao = db.statsDao()

    @Provides
    @Singleton
    fun provideAppPreferences(@ApplicationContext context: Context): AppPreferences =
        AppPreferences(context)
}
