package com.abook.sync

import com.abook.data.db.dao.BookDao
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exports library metadata (books + reading positions) to JSON string.
 * Text content of chapters is NOT exported (to keep size manageable).
 */
class LibraryExporter(private val bookDao: BookDao) {

    suspend fun exportToJson(books: List<com.abook.data.db.entity.BookEntity>): String {
        val array = JSONArray()
        for (book in books) {
            val obj = JSONObject().apply {
                put("id", book.id)
                put("title", book.title)
                put("author", book.author)
                put("format", book.format)
                put("totalChapters", book.totalChapters)
                put("addedAt", book.addedAt)
                put("lastOpenedAt", book.lastOpenedAt ?: JSONObject.NULL)
            }
            val position = bookDao.getPosition(book.id)
            if (position != null) {
                obj.put("position", JSONObject().apply {
                    put("chapterIndex", position.chapterIndex)
                    put("charOffsetInChapter", position.charOffsetInChapter)
                    put("updatedAt", position.updatedAt)
                })
            }
            array.put(obj)
        }
        return JSONObject().apply {
            put("version", 1)
            put("exportedAt", System.currentTimeMillis())
            put("books", array)
        }.toString(2)
    }

    fun exportToCsv(books: List<com.abook.data.db.entity.BookEntity>): String {
        val sb = StringBuilder("id;title;author;format;chapters;addedAt;lastOpenedAt\n")
        for (b in books) {
            sb.append("${csvEscape(b.id)};")
                .append("${csvEscape(b.title)};")
                .append("${csvEscape(b.author)};")
                .append("${csvEscape(b.format)};")
                .append("${b.totalChapters};")
                .append("${b.addedAt};")
                .append("${b.lastOpenedAt ?: ""}\n")
        }
        return sb.toString()
    }

    /** Escape CSV field: wrap in quotes, double any existing quotes */
    private fun csvEscape(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}
