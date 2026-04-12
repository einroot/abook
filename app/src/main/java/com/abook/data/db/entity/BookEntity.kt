package com.abook.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val filePath: String,
    val format: String,
    val coverPath: String?,
    val totalChapters: Int,
    val addedAt: Long,
    val lastOpenedAt: Long?
)
