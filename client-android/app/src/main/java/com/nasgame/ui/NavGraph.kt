package com.nasgame.ui

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.hilt.navigation.compose.hiltViewModel
import com.nasgame.ui.screen.*

object Routes {
    const val LOGIN = "login"
    const val LIBRARY = "library"
    const val DETAIL = "detail/{id}"
    const val SETTINGS = "settings"
    const val STREAM = "stream/{id}"

    fun detail(id: Long) = "detail/$id"
    fun stream(id: Long) = "stream/$id"
}

@Composable
fun NavGraph(nav: NavHostController) {
    val vm: RootViewModel = hiltViewModel()
    val loggedIn by vm.isLoggedIn.collectAsStateWithLifecycle()

    NavHost(navController = nav, startDestination = if (loggedIn) Routes.LIBRARY else Routes.LOGIN) {
        composable(Routes.LOGIN) {
            LoginScreen(onLoggedIn = { nav.navigate(Routes.LIBRARY) { popUpTo(Routes.LOGIN) { inclusive = true } } })
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(
                onOpenDetail = { nav.navigate(Routes.detail(it)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.DETAIL) { entry ->
            val id = entry.arguments?.getString("id")?.toLongOrNull() ?: 0L
            GameDetailScreen(
                gameId = id,
                onBack = { nav.popBackStack() },
                onStream = { nav.navigate(Routes.stream(id)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.STREAM) { entry ->
            val id = entry.arguments?.getString("id")?.toLongOrNull() ?: 0L
            StreamScreen(gameId = id, onBack = { nav.popBackStack() })
        }
    }
}
