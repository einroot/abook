package com.abook.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.abook.data.db.dao.BookDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class NavViewModel @Inject constructor(
    bookDao: BookDao
) : ViewModel() {
    val lastPlayedBookId: StateFlow<String?> = bookDao.getLastOpenedBookId()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
