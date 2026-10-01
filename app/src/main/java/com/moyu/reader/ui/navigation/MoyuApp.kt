package com.moyu.reader.ui.navigation

import com.moyu.reader.ui.theme.moyuPalette

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.moyu.reader.data.prefs.ReaderSettings
import com.moyu.reader.ui.MoyuViewModelFactory
import com.moyu.reader.ui.SettingsViewModel
import com.moyu.reader.ui.screens.ImportScreen
import com.moyu.reader.ui.screens.NotesScreen
import com.moyu.reader.ui.screens.ReaderScreen
import com.moyu.reader.ui.screens.SearchScreen
import com.moyu.reader.ui.screens.SettingsScreen
import com.moyu.reader.ui.screens.ShelfScreen
import com.moyu.reader.ui.screens.StatsScreen
import com.moyu.reader.ui.theme.moyuPalette

/**
 * 应用导航图。
 *
 * 结构上刻意分两层：
 *   - **标签页**（书架 / 统计 / 笔记 / 设置）放在同一个 NavHost 里，底部有导航栏；
 *   - **全屏页**（阅读器 / 搜索 / 导入）覆盖在上层，没有底部导航。
 *
 * 为什么不把阅读器也做成普通目的地：阅读器是全屏沉浸的，
 * 它会自行管理状态栏、返回手势与常亮，放在带底部栏的层级里会互相干扰。
 */
object Routes {
    const val SHELF = "shelf"
    const val STATS = "stats"
    const val NOTES = "notes"
    const val SETTINGS = "settings"
    const val READER = "reader/{bookId}"
    const val SEARCH = "search?bookId={bookId}"
    const val IMPORT = "import"

    fun reader(bookId: String) = "reader/$bookId"

    fun search(bookId: String? = null) =
        if (bookId == null) "search" else "search?bookId=$bookId"
}

@Composable
fun MoyuApp(
    settings: ReaderSettings,
    settingsViewModel: SettingsViewModel,
    factory: MoyuViewModelFactory,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // 阅读器是全屏页：它显示时底部导航必须隐藏，否则会与阅读器的底部工具栏重叠
    val showBottomBar = currentRoute in setOf(Routes.SHELF, Routes.STATS, Routes.NOTES, Routes.SETTINGS)

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Routes.SHELF,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Routes.SHELF) {
                ShelfScreen(
                    factory = factory,
                    onOpenBook = { bookId -> navController.navigate(Routes.reader(bookId)) },
                    onOpenSearch = { bookId -> navController.navigate(Routes.search(bookId)) },
                    onOpenImport = { navController.navigate(Routes.IMPORT) },
                    onOpenStats = { navController.navigate(Routes.STATS) },
                    onOpenNotes = { navController.navigate(Routes.NOTES) },
                )
            }

            composable(Routes.STATS) {
                StatsScreen(
                    factory = factory,
                    onBack = { navController.popBackStack() },
                    onOpenBook = { bookId -> navController.navigate(Routes.reader(bookId)) },
                )
            }

            composable(Routes.NOTES) {
                NotesScreen(
                    factory = factory,
                    bookId = null,
                    onBack = { navController.popBackStack() },
                    onJump = { bookId, chapterIndex, chapterOffset ->
                        navController.navigate(Routes.reader(bookId))
                    },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.READER) { entry ->
                val bookId = entry.arguments?.getString("bookId").orEmpty()
                ReaderScreen(
                    factory = factory,
                    bookId = bookId,
                    onExit = {
                        navController.popBackStack()
                    },
                    onOpenSearch = { id -> navController.navigate(Routes.search(id)) },
                    onOpenNotes = { id -> navController.navigate(Routes.NOTES) },
                )
            }

            composable(Routes.SEARCH) { entry ->
                val bookId = entry.arguments?.getString("bookId")?.takeIf { it.isNotBlank() }
                SearchScreen(
                    factory = factory,
                    bookId = bookId,
                    onBack = { navController.popBackStack() },
                    onJump = { id, _, _ ->
                        // 搜索结果点击后直接进入阅读器；阅读器会从数据库恢复最后位置，
                        // 因此这里不需要额外传递章节偏移（避免深层导航参数传递的复杂性）。
                        navController.navigate(Routes.reader(id))
                    },
                )
            }

            composable(Routes.IMPORT) {
                ImportScreen(
                    factory = factory,
                    onBack = { navController.popBackStack() },
                )
            }
        }

        AnimatedVisibility(
            visible = showBottomBar,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            MoyuBottomBar(
                currentRoute = currentRoute,
                onSelect = { route ->
                    if (currentRoute != route) {
                        navController.navigate(route) {
                            // 标签页之间切换不堆栈，避免返回键在标签间反复回退
                            popUpTo(Routes.SHELF) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
            )
        }
    }
}

/** 底部导航栏。 */
@Composable
private fun MoyuBottomBar(
    currentRoute: String?,
    onSelect: (String) -> Unit,
) {
    val palette = moyuPalette()
    val items = listOf(
        Triple(Routes.SHELF, "书架", Icons.AutoMirrored.Filled.MenuBook),
        Triple(Routes.STATS, "统计", Icons.Filled.BarChart),
        Triple(Routes.NOTES, "笔记", Icons.Filled.EditNote),
        Triple(Routes.SETTINGS, "设置", Icons.Filled.Settings),
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.divider),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.surface)
                .height(58.dp)
                .padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { (route, label, icon) ->
                BottomBarItem(
                    label = label,
                    icon = icon,
                    active = currentRoute == route,
                    onClick = { onSelect(route) },
                )
            }
        }
    }
}

@Composable
private fun BottomBarItem(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
) {
    val palette = moyuPalette()
    val tint = if (active) palette.primary else palette.textSecondary
    Column(
        modifier = Modifier
            .fillMaxWidth(0.25f)
            .height(56.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
