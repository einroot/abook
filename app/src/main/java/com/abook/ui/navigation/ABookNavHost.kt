package com.abook.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.abook.ui.bookmarks.BookmarksScreen
import com.abook.ui.chapters.ChapterListScreen
import com.abook.ui.library.LibraryScreen
import com.abook.ui.player.PlayerScreen
import com.abook.ui.settings.SettingsScreen
import com.abook.ui.voicesettings.VoiceSettingsScreen
import kotlinx.coroutines.launch

data class BottomNavItem(
    val label: String,
    val route: String,
    val icon: @Composable () -> Unit
)

@Composable
fun ABookNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val navViewModel: NavViewModel = hiltViewModel()
    val lastPlayedBookId by navViewModel.lastPlayedBookId.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val bottomNavItems = listOf(
        BottomNavItem("Библиотека", Screen.Library.route) {
            Icon(Icons.Default.LibraryBooks, contentDescription = "Библиотека")
        },
        BottomNavItem("Плеер", "player_tab") {
            Icon(Icons.Default.PlayCircle, contentDescription = "Плеер")
        },
        BottomNavItem("Голос", Screen.VoiceSettings.route) {
            Icon(Icons.Default.RecordVoiceOver, contentDescription = "Настройки голоса")
        }
    )

    val showBottomBar = currentRoute == Screen.Library.route ||
        currentRoute == Screen.VoiceSettings.route ||
        currentRoute?.startsWith("player/") == true

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        val selected = when (item.route) {
                            "player_tab" -> currentRoute?.startsWith("player/") == true
                            else -> currentRoute == item.route
                        }
                        NavigationBarItem(
                            icon = item.icon,
                            label = { Text(item.label) },
                            selected = selected,
                            onClick = {
                                when (item.route) {
                                    "player_tab" -> {
                                        val lastId = lastPlayedBookId
                                        if (lastId != null) {
                                            if (currentRoute?.startsWith("player/") != true) {
                                                navController.navigate(Screen.Player.createRoute(lastId)) {
                                                    popUpTo(Screen.Library.route) { inclusive = false }
                                                    launchSingleTop = true
                                                }
                                            }
                                        } else {
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(
                                                    "Откройте книгу из библиотеки"
                                                )
                                            }
                                        }
                                    }
                                    Screen.Library.route -> {
                                        // Pop everything back to the Library root
                                        navController.popBackStack(Screen.Library.route, false)
                                    }
                                    else -> {
                                        if (currentRoute != item.route) {
                                            navController.navigate(item.route) {
                                                popUpTo(Screen.Library.route) { inclusive = false }
                                                launchSingleTop = true
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Screen.Library.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(Screen.Library.route) {
                LibraryScreen(
                    onBookClick = { bookId ->
                        navController.navigate(Screen.Player.createRoute(bookId))
                    },
                    onSettingsClick = {
                        navController.navigate(Screen.Settings.route)
                    },
                    onBookmarksClick = {
                        navController.navigate(Screen.Bookmarks.createRoute(null))
                    }
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }

            composable(
                route = Screen.Player.route,
                arguments = listOf(
                    navArgument("bookId") { type = NavType.StringType },
                    navArgument("seekChapter") {
                        type = NavType.IntType
                        defaultValue = -1
                    },
                    navArgument("seekOffset") {
                        type = NavType.IntType
                        defaultValue = -1
                    }
                )
            ) { backStackEntry ->
                val bookId = backStackEntry.arguments?.getString("bookId") ?: return@composable
                val seekChapter = backStackEntry.arguments?.getInt("seekChapter") ?: -1
                val seekOffset = backStackEntry.arguments?.getInt("seekOffset") ?: -1

                // Observe selectedChapter set by ChapterListScreen
                val savedStateHandle = backStackEntry.savedStateHandle
                val selectedChapterState = savedStateHandle
                    .getStateFlow<Int>("selectedChapter", -1)
                    .collectAsState()
                val selectedChapter = selectedChapterState.value

                PlayerScreen(
                    bookId = bookId,
                    seekToChapterOnLaunch = seekChapter,
                    seekToOffsetOnLaunch = seekOffset,
                    selectedChapter = selectedChapter,
                    onChapterConsumed = { savedStateHandle["selectedChapter"] = -1 },
                    onChapterListClick = {
                        navController.navigate(Screen.ChapterList.createRoute(bookId))
                    },
                    onBookmarksClick = {
                        navController.navigate(Screen.Bookmarks.createRoute(bookId))
                    }
                )
            }

            composable(
                route = Screen.Bookmarks.route,
                arguments = listOf(navArgument("bookId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                })
            ) { backStackEntry ->
                val filterBookId = backStackEntry.arguments?.getString("bookId")
                BookmarksScreen(
                    initialFilterBookId = filterBookId,
                    onBack = { navController.popBackStack() },
                    onNavigateToBookmark = { targetBookId, chapterIndex, charOffset ->
                        navController.navigate(
                            Screen.Player.createRoute(targetBookId, chapterIndex, charOffset)
                        ) {
                            popUpTo(Screen.Library.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                )
            }

            composable(Screen.VoiceSettings.route) {
                VoiceSettingsScreen()
            }

            composable(
                route = Screen.ChapterList.route,
                arguments = listOf(navArgument("bookId") { type = NavType.StringType })
            ) { backStackEntry ->
                val bookId = backStackEntry.arguments?.getString("bookId") ?: return@composable
                ChapterListScreen(
                    bookId = bookId,
                    onChapterClick = { chapterIndex ->
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set("selectedChapter", chapterIndex)
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
