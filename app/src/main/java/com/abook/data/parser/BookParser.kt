package com.abook.data.parser

import java.io.InputStream

data class ParsedBook(
    val title: String,
    val author: String,
    val language: String? = null,
    val description: String? = null,
    val coverImageBytes: ByteArray? = null,
    val chapters: List<ParsedChapter>
)

data class ParsedChapter(
    val title: String,
    val textContent: String
)

interface BookParser {
    suspend fun parse(inputStream: InputStream, fileName: String): ParsedBook
}
