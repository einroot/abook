package com.abook.ui.chapters

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abook.data.db.dao.BookDao
import com.abook.data.db.entity.ChapterEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChapterListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bookDao: BookDao
) : ViewModel() {

    private val bookId: String = savedStateHandle["bookId"] ?: ""

    private val _chapters = MutableStateFlow<List<ChapterEntity>>(emptyList())
    val chapters: StateFlow<List<ChapterEntity>> = _chapters.asStateFlow()

    private val _currentChapterIndex = MutableStateFlow(0)
    val currentChapterIndex: StateFlow<Int> = _currentChapterIndex.asStateFlow()

    init {
        viewModelScope.launch {
            _chapters.value = bookDao.getChapters(bookId)
            val position = bookDao.getPosition(bookId)
            _currentChapterIndex.value = position?.chapterIndex ?: 0
        }
    }
}
