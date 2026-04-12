package com.abook.service

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import androidx.media.MediaBrowserServiceCompat
import com.abook.data.db.dao.BookDao
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MediaBrowserServiceCompat for Android Auto integration.
 * Exposes a browsable tree: root → books → chapters.
 */
@AndroidEntryPoint
class AutoMediaBrowserService : MediaBrowserServiceCompat() {

    @Inject lateinit var bookDao: BookDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot = BrowserRoot(ROOT_ID, null)

    override fun onLoadChildren(
        parentId: String,
        result: Result<List<MediaBrowserCompat.MediaItem>>
    ) {
        result.detach()
        scope.launch {
            try {
                val items: List<MediaBrowserCompat.MediaItem> = when {
                    parentId == ROOT_ID -> loadBooks()
                    parentId.startsWith(BOOK_PREFIX) -> loadChapters(parentId.removePrefix(BOOK_PREFIX))
                    else -> emptyList()
                }
                result.sendResult(items)
            } catch (e: Exception) {
                // Client may have disconnected before result was ready
                try { result.sendResult(emptyList()) } catch (_: Exception) {}
            }
        }
    }

    private suspend fun loadBooks(): List<MediaBrowserCompat.MediaItem> {
        val books = try {
            bookDao.getAllBooks().first()
        } catch (_: Exception) {
            emptyList()
        }
        return books.map { book ->
            val desc = MediaDescriptionCompat.Builder()
                .setMediaId("$BOOK_PREFIX${book.id}")
                .setTitle(book.title)
                .setSubtitle(book.author.ifBlank { book.format })
                .build()
            MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
        }
    }

    private suspend fun loadChapters(bookId: String): List<MediaBrowserCompat.MediaItem> {
        val chapters = bookDao.getChapters(bookId)
        return chapters.map { ch ->
            val desc = MediaDescriptionCompat.Builder()
                .setMediaId("$CHAPTER_PREFIX$bookId:${ch.index}")
                .setTitle(ch.title)
                .build()
            MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ROOT_ID = "root"
        private const val BOOK_PREFIX = "book:"
        private const val CHAPTER_PREFIX = "chapter:"
    }
}
