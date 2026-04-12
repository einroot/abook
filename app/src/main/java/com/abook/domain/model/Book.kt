package com.abook.domain.model

enum class BookFormat {
    FB2, EPUB, TXT, PDF
}

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val filePath: String,
    val format: BookFormat,
    val coverPath: String?,
    val totalChapters: Int,
    val addedAt: Long,
    val lastOpenedAt: Long?
)

data class Chapter(
    val index: Int,
    val title: String,
    val textContent: String,
    val charOffset: Long
)
