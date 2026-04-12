package com.abook.ui.library

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abook.data.db.dao.BookDao
import com.abook.data.db.entity.BookEntity
import com.abook.data.db.entity.ChapterEntity
import com.abook.data.parser.BookParser
import com.abook.data.parser.EpubParser
import com.abook.data.parser.Fb2Parser
import com.abook.data.parser.ParsedBook
import com.abook.data.parser.PdfParser
import com.abook.data.parser.TxtParser
import com.abook.domain.model.BookFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookDao: BookDao
) : ViewModel() {

    enum class SortMode { RECENT, TITLE, AUTHOR, DATE_ADDED }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _sortMode = MutableStateFlow(SortMode.RECENT)
    val sortMode: StateFlow<SortMode> = _sortMode

    val books: StateFlow<List<BookEntity>> = combine(
        bookDao.getAllBooks(),
        _searchQuery,
        _sortMode
    ) { allBooks, query, sort ->
        val filtered = if (query.isBlank()) allBooks else allBooks.filter { book ->
            book.title.contains(query, ignoreCase = true) ||
                book.author.contains(query, ignoreCase = true)
        }
        when (sort) {
            SortMode.RECENT -> filtered.sortedByDescending { it.lastOpenedAt ?: it.addedAt }
            SortMode.TITLE -> filtered.sortedBy { it.title }
            SortMode.AUTHOR -> filtered.sortedBy { it.author.ifBlank { "\uFFFF" } }  // blank authors last
            SortMode.DATE_ADDED -> filtered.sortedByDescending { it.addedAt }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun setSortMode(mode: SortMode) { _sortMode.value = mode }

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError

    fun clearImportError() { _importError.value = null }

    fun importBook(uri: Uri, contentResolver: ContentResolver) {
        viewModelScope.launch(Dispatchers.IO) {
            _isImporting.value = true
            try {
                val fileName = extractFileName(uri, contentResolver)
                val format = detectFormat(fileName)
                val parser: BookParser = when (format) {
                    BookFormat.FB2 -> Fb2Parser()
                    BookFormat.EPUB -> EpubParser()
                    BookFormat.PDF -> PdfParser()
                    BookFormat.TXT -> TxtParser()
                }
                val stream = contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open file")
                val parsed = stream.use { parser.parse(it, fileName) }
                saveParsedBook(uri, format, parsed)
            } catch (e: Exception) {
                e.printStackTrace()
                _importError.value = "Ошибка импорта: ${e.message}"
            } finally {
                _isImporting.value = false
            }
        }
    }

    private fun extractFileName(uri: Uri, contentResolver: ContentResolver): String {
        // Try to get the display name from ContentResolver query
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
            } ?: uri.lastPathSegment ?: "unknown"
        } catch (_: Exception) {
            uri.lastPathSegment ?: "unknown"
        }
    }

    private fun detectFormat(fileName: String): BookFormat = when {
        fileName.endsWith(".fb2", ignoreCase = true) -> BookFormat.FB2
        fileName.endsWith(".epub", ignoreCase = true) -> BookFormat.EPUB
        fileName.endsWith(".pdf", ignoreCase = true) -> BookFormat.PDF
        else -> BookFormat.TXT
    }

    private suspend fun saveParsedBook(uri: Uri, format: BookFormat, parsed: ParsedBook) {
        val bookId = UUID.randomUUID().toString()
        val coverPath = parsed.coverImageBytes?.let { saveCoverImage(bookId, it) }

        bookDao.insertBook(
            BookEntity(
                id = bookId,
                title = parsed.title,
                author = parsed.author,
                filePath = uri.toString(),
                format = format.name,
                coverPath = coverPath,
                totalChapters = parsed.chapters.size,
                addedAt = System.currentTimeMillis(),
                lastOpenedAt = null
            )
        )

        var offset = 0L
        val entities = parsed.chapters.mapIndexed { i, ch ->
            val entity = ChapterEntity(
                bookId = bookId,
                index = i,
                title = ch.title,
                textContent = ch.textContent,
                charOffset = offset
            )
            offset += ch.textContent.length
            entity
        }
        bookDao.insertChapters(entities)
    }

    private fun saveCoverImage(bookId: String, bytes: ByteArray): String? {
        return try {
            val dir = File(context.filesDir, "covers")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "$bookId.jpg")
            file.writeBytes(bytes)
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Temporary fallback for formats without a proper parser yet.
     */
    private suspend fun importAsPlainText(
        uri: Uri,
        contentResolver: ContentResolver,
        fileName: String,
        format: BookFormat
    ) {
        val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
        val chapters = fallbackSplitByPatterns(text)
        val parsed = ParsedBook(
            title = fileName.substringBeforeLast("."),
            author = "",
            chapters = chapters
        )
        saveParsedBook(uri, format, parsed)
    }

    private fun fallbackSplitByPatterns(text: String): List<com.abook.data.parser.ParsedChapter> {
        if (text.isBlank()) return listOf(com.abook.data.parser.ParsedChapter("Глава 1", text))
        val pattern = Regex("""(?m)^(?:Глава|Chapter|ГЛАВА|CHAPTER)\s+\d+[.\s].*$""")
        val matches = pattern.findAll(text).toList()
        if (matches.isEmpty()) {
            val chunks = text.chunked(5000)
            return chunks.mapIndexed { i, c ->
                com.abook.data.parser.ParsedChapter("Часть ${i + 1}", c)
            }
        }
        val chapters = mutableListOf<com.abook.data.parser.ParsedChapter>()
        if (matches.first().range.first > 0) {
            val preface = text.substring(0, matches.first().range.first).trim()
            if (preface.isNotBlank()) {
                chapters.add(com.abook.data.parser.ParsedChapter("Вступление", preface))
            }
        }
        for (i in matches.indices) {
            val title = matches[i].value.trim()
            val start = matches[i].range.last + 1
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
            val content = text.substring(start, end).trim()
            if (content.isNotBlank()) chapters.add(com.abook.data.parser.ParsedChapter(title, content))
        }
        return chapters.ifEmpty { listOf(com.abook.data.parser.ParsedChapter("Глава 1", text)) }
    }

    fun deleteBook(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            bookDao.deleteBook(bookId)
        }
    }
}
