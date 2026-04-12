package com.abook.ui.navigation

sealed class Screen(val route: String) {
    data object Library : Screen("library")
    data object Player : Screen("player/{bookId}?seekChapter={seekChapter}&seekOffset={seekOffset}") {
        fun createRoute(bookId: String, seekChapter: Int = -1, seekOffset: Int = -1): String =
            "player/$bookId?seekChapter=$seekChapter&seekOffset=$seekOffset"
    }
    data object VoiceSettings : Screen("voice_settings")
    data object Settings : Screen("settings")
    data object Bookmarks : Screen("bookmarks?bookId={bookId}") {
        fun createRoute(bookId: String? = null): String =
            if (bookId != null) "bookmarks?bookId=$bookId" else "bookmarks"
    }
    data object ChapterList : Screen("chapters/{bookId}") {
        fun createRoute(bookId: String) = "chapters/$bookId"
    }
}
