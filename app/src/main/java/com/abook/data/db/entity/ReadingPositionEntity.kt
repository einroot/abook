package com.abook.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reading_positions")
data class ReadingPositionEntity(
    @PrimaryKey val bookId: String,
    val chapterIndex: Int,
    val charOffsetInChapter: Int,
    val updatedAt: Long
)
