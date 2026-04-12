package com.abook.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.abook.data.db.entity.VoiceProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VoiceProfileDao {

    @Query("SELECT * FROM voice_profiles ORDER BY isDefault DESC, name ASC")
    fun getAllProfiles(): Flow<List<VoiceProfileEntity>>

    @Query("SELECT * FROM voice_profiles WHERE id = :id")
    suspend fun getProfile(id: Long): VoiceProfileEntity?

    @Query("SELECT * FROM voice_profiles WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultProfile(): VoiceProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: VoiceProfileEntity): Long

    @Update
    suspend fun update(profile: VoiceProfileEntity)

    @Delete
    suspend fun delete(profile: VoiceProfileEntity)
}
