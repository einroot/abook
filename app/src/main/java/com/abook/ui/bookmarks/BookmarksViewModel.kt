package com.abook.ui.bookmarks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abook.data.db.dao.BookDao
import com.abook.data.db.dao.BookmarkDao
import com.abook.data.db.entity.BookEntity
import com.abook.data.db.entity.BookmarkEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BookmarkWithBook(
    val bookmark: BookmarkEntity,
    val book: BookEntity?
)

@HiltViewModel
class BookmarksViewModel @Inject constructor(
    private val bookmarkDao: BookmarkDao,
    private val bookDao: BookDao
) : ViewModel() {

    enum class SortMode { NEWEST, OLDEST, BY_BOOK, BY_CHAPTER }
    enum class ViewMode { ALL, BY_CURRENT_BOOK }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _sortMode = MutableStateFlow(SortMode.NEWEST)
    val sortMode: StateFlow<SortMode> = _sortMode

    private val _filterBookId = MutableStateFlow<String?>(null)
    val filterBookId: StateFlow<String?> = _filterBookId

    val allBookmarks: StateFlow<List<BookmarkWithBook>> = combine(
        bookmarkDao.getAllBookmarks(),
        bookDao.getAllBooks(),
        _searchQuery,
        _sortMode,
        _filterBookId
    ) { bookmarks, books, query, sort, filterBook ->
        val booksMap = books.associateBy { it.id }
        val joined = bookmarks.map { BookmarkWithBook(it, booksMap[it.bookId]) }

        val filtered = joined
            .filter { filterBook == null || it.bookmark.bookId == filterBook }
            .filter { item ->
                if (query.isBlank()) true
                else {
                    item.bookmark.label.contains(query, ignoreCase = true) ||
                        (item.book?.title?.contains(query, ignoreCase = true) == true)
                }
            }

        when (sort) {
            SortMode.NEWEST -> filtered.sortedByDescending { it.bookmark.createdAt }
            SortMode.OLDEST -> filtered.sortedBy { it.bookmark.createdAt }
            SortMode.BY_BOOK -> filtered.sortedWith(
                compareBy<BookmarkWithBook> { it.book?.title ?: "" }
                    .thenBy { it.bookmark.chapterIndex }
                    .thenBy { it.bookmark.charOffsetInChapter }
            )
            SortMode.BY_CHAPTER -> filtered.sortedWith(
                compareBy<BookmarkWithBook> { it.bookmark.bookId }
                    .thenBy { it.bookmark.chapterIndex }
                    .thenBy { it.bookmark.charOffsetInChapter }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun setSortMode(mode: SortMode) { _sortMode.value = mode }
    fun setFilterBookId(bookId: String?) { _filterBookId.value = bookId }

    fun deleteBookmark(bookmark: BookmarkEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkDao.delete(bookmark)
        }
    }

    fun updateLabel(bookmark: BookmarkEntity, newLabel: String) {
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkDao.update(bookmark.copy(label = newLabel))
        }
    }
}
