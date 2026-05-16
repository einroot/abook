package com.abook.service

import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.content.ContextCompat
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
    private lateinit var mediaSession: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "ABookAutoMediaSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    startPlaybackService(TtsPlaybackService.ACTION_PLAY)
                }

                override fun onPause() {
                    startPlaybackService(TtsPlaybackService.ACTION_PAUSE)
                }

                override fun onStop() {
                    startPlaybackService(TtsPlaybackService.ACTION_STOP)
                }

                override fun onSkipToNext() {
                    startPlaybackService(TtsPlaybackService.ACTION_NEXT_CHAPTER)
                }

                override fun onSkipToPrevious() {
                    startPlaybackService(TtsPlaybackService.ACTION_PREV_CHAPTER)
                }

                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    playFromMediaId(mediaId)
                }

                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    scope.launch {
                        val book = findBookForSearch(query) ?: return@launch
                        startPlaybackService(TtsPlaybackService.ACTION_PLAY_BOOK) {
                            putExtra(TtsPlaybackService.EXTRA_BOOK_ID, book.id)
                        }
                    }
                }
            })
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_STOP
                    )
                    .setState(
                        PlaybackStateCompat.STATE_PAUSED,
                        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                        1.0f
                    )
                    .build()
            )
            isActive = true
        }
        setSessionToken(mediaSession.sessionToken)
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        if (!isTrustedClient(clientPackageName, clientUid)) {
            Log.w(TAG, "Rejecting untrusted media browser client: $clientPackageName/$clientUid")
            return null
        }
        return BrowserRoot(ROOT_ID, null)
    }

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

    private fun isTrustedClient(clientPackageName: String, clientUid: Int): Boolean {
        if (clientPackageName == packageName || clientUid == Process.SYSTEM_UID) return true
        val packagesForUid = packageManager.getPackagesForUid(clientUid)?.toSet() ?: return false
        if (clientPackageName !in packagesForUid) return false

        return clientPackageName in TRUSTED_CLIENT_PACKAGES ||
            clientPackageName.startsWith("com.android.") ||
            clientPackageName.startsWith("android.")
    }

    private fun playFromMediaId(mediaId: String?) {
        when {
            mediaId == null -> startPlaybackService(TtsPlaybackService.ACTION_PLAY)
            mediaId.startsWith(CHAPTER_PREFIX) -> {
                val payload = mediaId.removePrefix(CHAPTER_PREFIX)
                val separator = payload.lastIndexOf(':')
                if (separator <= 0) return
                val bookId = payload.substring(0, separator)
                val chapterIndex = payload.substring(separator + 1).toIntOrNull() ?: 0
                startPlaybackService(TtsPlaybackService.ACTION_PLAY_BOOK) {
                    putExtra(TtsPlaybackService.EXTRA_BOOK_ID, bookId)
                    putExtra(TtsPlaybackService.EXTRA_CHAPTER_INDEX, chapterIndex)
                    putExtra(TtsPlaybackService.EXTRA_CHAR_OFFSET, 0)
                }
            }
            mediaId.startsWith(BOOK_PREFIX) -> {
                val bookId = mediaId.removePrefix(BOOK_PREFIX)
                startPlaybackService(TtsPlaybackService.ACTION_PLAY_BOOK) {
                    putExtra(TtsPlaybackService.EXTRA_BOOK_ID, bookId)
                }
            }
            else -> startPlaybackService(TtsPlaybackService.ACTION_PLAY)
        }
    }

    private suspend fun findBookForSearch(query: String?) =
        bookDao.getAllBooks().first().let { books ->
            val normalized = query.orEmpty().trim()
            if (normalized.isBlank()) {
                books.firstOrNull()
            } else {
                books.firstOrNull { book ->
                    book.title.contains(normalized, ignoreCase = true) ||
                        book.author.contains(normalized, ignoreCase = true)
                } ?: books.firstOrNull()
            }
        }

    private fun startPlaybackService(
        action: String,
        configure: Intent.() -> Unit = {}
    ) {
        val intent = Intent(this, TtsPlaybackService::class.java).apply {
            setAction(action)
            configure()
        }
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (_: Exception) {
            try { startService(intent) } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if (::mediaSession.isInitialized) mediaSession.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AutoMediaBrowser"
        private const val ROOT_ID = "root"
        private const val BOOK_PREFIX = "book:"
        private const val CHAPTER_PREFIX = "chapter:"
        private val TRUSTED_CLIENT_PACKAGES = setOf(
            "com.google.android.projection.gearhead",
            "com.google.android.gms",
            "com.android.car.media"
        )
    }
}
