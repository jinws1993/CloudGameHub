package com.cloudgamehub.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cloudgamehub.ui.screen.*

object Routes {
    const val CONNECT = "connect"
    const val PICK_FOLDER = "pick_folder"
    const val SCAN = "scan"
    const val LIBRARY = "library"
    const val DETAIL = "detail/{id}"
    const val SETTINGS = "settings"
    const val DOWNLOADS = "downloads"

    fun detail(id: Long) = "detail/$id"
}

/**
 * 单 App 的导航流程:
 *
 * ```
 * 启动 → 有 115 cookie? ── 否 → 连接页(扫码/Cookie)
 *                     └─ 是 ─→ 库 ─┬→ 详情 ─→ 🎮 游玩
 *                                   ├→ 扫描(选目录 + 刮削)
 *                                   ├→ 下载管理
 *                                   └→ 设置
 * ```
 */
@Composable
fun CloudGameHubApp(startWithConnect: Boolean = false) {
    val nav: NavHostController = rememberNavController()

    NavHost(
        navController = nav,
        startDestination = if (startWithConnect) Routes.CONNECT else Routes.LIBRARY,
    ) {
        composable(Routes.CONNECT) {
            ConnectScreen(
                onConnected = {
                    nav.navigate(Routes.LIBRARY) { popUpTo(Routes.CONNECT) { inclusive = true } }
                }
            )
        }

        composable(Routes.PICK_FOLDER) {
            FolderPickScreen(
                onBack = { nav.popBackStack() },
                onPicked = {
                    nav.navigate(Routes.SCAN) { popUpTo(Routes.PICK_FOLDER) { inclusive = true } }
                },
            )
        }

        composable(Routes.SCAN) {
            val rootVm: RootViewModel = hiltViewModel()
            val rootPath by rootVm.rootPath.collectAsStateWithLifecycle()
            ScanScreen(
                rootPath = rootPath,
                onBack = { nav.popBackStack() },
                onOpenLibrary = {
                    nav.navigate(Routes.LIBRARY) { popUpTo(Routes.LIBRARY) { inclusive = true } }
                },
            )
        }

        composable(Routes.LIBRARY) {
            LibraryScreen(
                onOpenDetail = { nav.navigate(Routes.detail(it)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenDownloads = { nav.navigate(Routes.DOWNLOADS) },
                onOpenConnect = { nav.navigate(Routes.CONNECT) },
                onRescan = { nav.navigate(Routes.PICK_FOLDER) },
            )
        }

        composable(Routes.DETAIL) { entry ->
            val id = entry.arguments?.getString("id")?.toLongOrNull() ?: 0L
            GameDetailScreen(gameId = id, onBack = { nav.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onLogin115 = { nav.navigate(Routes.CONNECT) },
            )
        }

        composable(Routes.DOWNLOADS) {
            DownloadsScreen(onBack = { nav.popBackStack() })
        }
    }
}
