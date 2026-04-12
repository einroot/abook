package com.abook.ui.bookmarks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.abook.data.db.entity.BookmarkEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookmarksScreen(
    initialFilterBookId: String? = null,
    onBack: () -> Unit,
    onNavigateToBookmark: (bookId: String, chapterIndex: Int, charOffset: Int) -> Unit,
    viewModel: BookmarksViewModel = hiltViewModel()
) {
    val bookmarks by viewModel.allBookmarks.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()
    val filterBookId by viewModel.filterBookId.collectAsState()

    LaunchedEffect(initialFilterBookId) {
        viewModel.setFilterBookId(initialFilterBookId)
    }

    var showSortMenu by remember { mutableStateOf(false) }
    var editingBookmark by remember { mutableStateOf<BookmarkEntity?>(null) }
    var deletingBookmark by remember { mutableStateOf<BookmarkEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (filterBookId != null) "Закладки книги"
                        else "Все закладки"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (filterBookId != null) {
                        TextButton(onClick = { viewModel.setFilterBookId(null) }) {
                            Text("Все")
                        }
                    }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "Сортировка")
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            SortMenuItem(
                                "Сначала новые",
                                BookmarksViewModel.SortMode.NEWEST,
                                sortMode
                            ) { viewModel.setSortMode(it); showSortMenu = false }
                            SortMenuItem(
                                "Сначала старые",
                                BookmarksViewModel.SortMode.OLDEST,
                                sortMode
                            ) { viewModel.setSortMode(it); showSortMenu = false }
                            SortMenuItem(
                                "По книгам",
                                BookmarksViewModel.SortMode.BY_BOOK,
                                sortMode
                            ) { viewModel.setSortMode(it); showSortMenu = false }
                            SortMenuItem(
                                "По главам",
                                BookmarksViewModel.SortMode.BY_CHAPTER,
                                sortMode
                            ) { viewModel.setSortMode(it); showSortMenu = false }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.setSearchQuery(it) },
                label = { Text("Поиск по меткам и книгам") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true
            )

            if (bookmarks.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Bookmark,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Закладок ещё нет",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "На экране плеера нажмите ⭐, чтобы отметить место",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                Text(
                    "Всего: ${bookmarks.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp,
                        vertical = 6.dp
                    )
                ) {
                    items(bookmarks, key = { it.bookmark.id }) { item ->
                        BookmarkRow(
                            item = item,
                            showBookTitle = filterBookId == null,
                            onClick = {
                                onNavigateToBookmark(
                                    item.bookmark.bookId,
                                    item.bookmark.chapterIndex,
                                    item.bookmark.charOffsetInChapter
                                )
                            },
                            onEdit = { editingBookmark = item.bookmark },
                            onDelete = { deletingBookmark = item.bookmark },
                            onFilterByBook = {
                                viewModel.setFilterBookId(item.bookmark.bookId)
                            }
                        )
                    }
                }
            }
        }
    }

    editingBookmark?.let { bm ->
        BookmarkEditDialog(
            initialLabel = bm.label,
            onDismiss = { editingBookmark = null },
            onSave = { newLabel ->
                viewModel.updateLabel(bm, newLabel)
                editingBookmark = null
            }
        )
    }

    deletingBookmark?.let { bm ->
        AlertDialog(
            onDismissRequest = { deletingBookmark = null },
            title = { Text("Удалить закладку?") },
            text = { Text("«${bm.label}» — это действие нельзя отменить.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteBookmark(bm)
                        deletingBookmark = null
                    }
                ) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingBookmark = null }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun SortMenuItem(
    label: String,
    mode: BookmarksViewModel.SortMode,
    current: BookmarksViewModel.SortMode,
    onClick: (BookmarksViewModel.SortMode) -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(
                label,
                fontWeight = if (current == mode) FontWeight.Bold else FontWeight.Normal,
                color = if (current == mode) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
        },
        onClick = { onClick(mode) }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkRow(
    item: BookmarkWithBook,
    showBookTitle: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onFilterByBook: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val dateFmt = remember { SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuExpanded = true }
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Bookmark,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.bookmark.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (showBookTitle && item.book != null) {
                    Text(
                        text = item.book.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { onFilterByBook() }
                    )
                }
                Text(
                    text = "Глава ${item.bookmark.chapterIndex + 1} • " +
                        "поз. ${item.bookmark.charOffsetInChapter} • " +
                        dateFmt.format(Date(item.bookmark.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Меню")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Перейти") },
                        onClick = {
                            menuExpanded = false
                            onClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Изменить метку") },
                        leadingIcon = {
                            Icon(Icons.Default.Edit, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        }
                    )
                    if (showBookTitle) {
                        DropdownMenuItem(
                            text = { Text("Все из этой книги") },
                            onClick = {
                                menuExpanded = false
                                onFilterByBook()
                            }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text("Удалить", color = MaterialTheme.colorScheme.error)
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun BookmarkEditDialog(
    initialLabel: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var label by remember { mutableStateOf(initialLabel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Метка закладки") },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Текст метки") },
                singleLine = false,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (label.isNotBlank()) onSave(label.trim())
                }
            ) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}
