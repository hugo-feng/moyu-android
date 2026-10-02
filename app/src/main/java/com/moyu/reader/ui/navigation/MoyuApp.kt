package com.moyu.reader.ui.navigation

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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.History
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
import com.moyu.reader.ui.MoyuViewModelFactory
import com.moyu.reader.ui.SettingsViewModel
import com.moyu.reader.ui.safeDrawingBottomPadding
import com.moyu.reader.ui.screens.BookDetailScreen
import com.moyu.reader.ui.screens.HistoryScreen
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
    /** 书库：本机**全部**导入的书。应用启动后的第一个页面。 */
    const val LIBRARY = "library"

    /** 书架：只放用户主动「加入书架」的书。 */
    const val SHELF = "shelf"

    const val STATS = "stats"
    const val SETTINGS = "settings"

    /**
     * 阅读历史。
     *
     * 它取代了原先的全局「笔记」标签。笔记没有消失 ——
     * 仍在阅读器的「本书笔记」面板里（NotesViewModel.bookFilter = 本书），
     * 那条路径不经过这里，因此删掉全局入口不影响它。
     */
    const val HISTORY = "history"

    /**
     * 阅读器路由带可选的跳转目标。
     *
     * 为什么必须带上（而不是只传 bookId）：搜索命中与全局笔记的**全部意义**
     * 就是跳到那一句原文。早先这两个入口都只 navigate(bookId)，
     * 而阅读器只会从数据库恢复「上次读到的位置」—— 于是点了搜索结果却停在
     * 上次的位置，功能等于失效。这里把章号与章内偏移作为查询参数传进去。
     */
    const val READER = "reader/{bookId}?chapter={chapter}&offset={offset}"

    const val SEARCH = "search?bookId={bookId}"
    const val IMPORT = "import"

    /** @param chapterIndex 要跳到的章（-1 表示沿用上次位置） */
    fun reader(bookId: String, chapterIndex: Int = -1, chapterOffset: Int = 0) =
        "reader/$bookId?chapter=$chapterIndex&offset=$chapterOffset"

    /** 单本书的笔记页。 */
    const val BOOK_NOTES = "bookNotes/{bookId}"

    fun bookNotes(bookId: String) = "bookNotes/$bookId"

    /**
     * 书籍详情页。
     *
     * 书库里点一本书先到这里，而不是直接进阅读器 ——
     * 详情页承担「加入书架 / 目录 / 笔记 / 删除 / 开始阅读」这些决策，
     * 是打通「书库 → 书架」的关键一步。
     */
    const val BOOK_DETAIL = "bookDetail/{bookId}"

    fun bookDetail(bookId: String) = "bookDetail/$bookId"

    fun search(bookId: String? = null) =
        if (bookId == null) "search" else "search?bookId=$bookId"
}

@Composable
fun MoyuApp(
    settingsViewModel: SettingsViewModel,
    factory: MoyuViewModelFactory,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // 阅读器是全屏页：它显示时底部导航必须隐藏，否则会与阅读器的底部工具栏重叠
    val showBottomBar = currentRoute in setOf(
        Routes.LIBRARY,
        Routes.SHELF,
        Routes.HISTORY,
        Routes.STATS,
        Routes.SETTINGS,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        /**
         * 导航内容**按底栏高度留出下边距**，而不是让底栏盖在内容上。
         *
         * 早先底栏是覆盖式的（Box 里 align 到底部），列表最后一项会被压在
         * 底栏与手势条下面 —— 用户看到的就是「内容显示不全」。
         * 底栏高度 = 1px 分隔线 + 58dp + 底部安全区，这里保持一致。
         */
        NavHost(
            navController = navController,
            // 启动落在**书库**：导入的书先在这里，用户挑出想读的加入书架
            startDestination = Routes.LIBRARY,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (showBottomBar) {
                        Modifier.safeDrawingBottomPadding().padding(bottom = 59.dp)
                    } else {
                        Modifier
                    },
                ),
        ) {
            composable(Routes.LIBRARY) {
                ShelfScreen(
                    factory = factory,
                    libraryMode = true,
                    // 书库里点书 → 详情页（而不是直接进阅读器）。
                    // 直接进阅读器的话，用户永远没有机会「加入书架」——
                    // 这正是先前「完全没有方法把书籍从书库导入书架内」的原因。
                    onOpenBook = { bookId -> navController.navigate(Routes.bookDetail(bookId)) },
                    onOpenSearch = { bookId -> navController.navigate(Routes.search(bookId)) },
                    onOpenImport = { navController.navigate(Routes.IMPORT) },
                    onOpenStats = { navController.navigate(Routes.STATS) },
                    onOpenNotes = { bookId -> navController.navigate(Routes.bookNotes(bookId)) },
                )
            }

            composable(Routes.SHELF) {
                ShelfScreen(
                    factory = factory,
                    libraryMode = false,
                    // 书架上已经是用户挑过的书，点击直接读，少一次跳转
                    onOpenBook = { bookId -> navController.navigate(Routes.reader(bookId)) },
                    onOpenSearch = { bookId -> navController.navigate(Routes.search(bookId)) },
                    onOpenImport = { navController.navigate(Routes.IMPORT) },
                    onOpenStats = { navController.navigate(Routes.STATS) },
                    onOpenNotes = { bookId -> navController.navigate(Routes.bookNotes(bookId)) },
                )
            }

            composable(
                route = Routes.BOOK_DETAIL,
                arguments = listOf(
                    androidx.navigation.navArgument("bookId") {
                        type = androidx.navigation.NavType.StringType
                    },
                ),
            ) { entry ->
                BookDetailScreen(
                    factory = factory,
                    bookId = entry.arguments?.getString("bookId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onRead = { id -> navController.navigate(Routes.reader(id)) },
                    onOpenToc = { id -> navController.navigate(Routes.reader(id)) },
                    onOpenNotes = { id -> navController.navigate(Routes.bookNotes(id)) },
                )
            }

            composable(Routes.STATS) {
                StatsScreen(
                    factory = factory,
                    onBack = { navController.popBackStack() },
                    onOpenBook = { bookId -> navController.navigate(Routes.reader(bookId)) },
                )
            }

            /**
             * 阅读历史（底部标签第二格）。
             *
             * 取代了原先的全局「笔记」页。笔记仍然存在，见下方 BOOK_NOTES 路由 ——
             * 它是**按书**查看的：从书架长按或阅读器工具栏进入。
             * 这样不会出现「写得出笔记但找不到入口」的情况。
             */
            composable(Routes.HISTORY) {
                HistoryScreen(
                    factory = factory,
                    onOpenBook = { bookId -> navController.navigate(Routes.reader(bookId)) },
                )
            }

            /** 单本书的笔记（书签 + 划线）。从书架卡片菜单进入。 */
            composable(
                route = Routes.BOOK_NOTES,
                arguments = listOf(
                    androidx.navigation.navArgument("bookId") {
                        type = androidx.navigation.NavType.StringType
                    },
                ),
            ) { entry ->
                NotesScreen(
                    factory = factory,
                    bookId = entry.arguments?.getString("bookId"),
                    onBack = { navController.popBackStack() },
                    onJump = { bookId, chapterIndex, chapterOffset ->
                        navController.navigate(Routes.reader(bookId, chapterIndex, chapterOffset))
                    },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onBack = { navController.popBackStack() },
                    onOpenImport = { navController.navigate(Routes.IMPORT) },
                )
            }

            composable(
                route = Routes.READER,
                // 查询参数必须显式声明类型，否则 entry.arguments 里取不到值，
                // 跳转目标会被静默丢弃（表现为「搜索跳转不生效」）。
                arguments = listOf(
                    androidx.navigation.navArgument("bookId") {
                        type = androidx.navigation.NavType.StringType
                    },
                    androidx.navigation.navArgument("chapter") {
                        type = androidx.navigation.NavType.IntType
                        defaultValue = -1
                    },
                    androidx.navigation.navArgument("offset") {
                        type = androidx.navigation.NavType.IntType
                        defaultValue = 0
                    },
                ),
            ) { entry ->
                val bookId = entry.arguments?.getString("bookId").orEmpty()
                val targetChapter = entry.arguments?.getInt("chapter") ?: -1
                val targetOffset = entry.arguments?.getInt("offset") ?: 0
                ReaderScreen(
                    factory = factory,
                    bookId = bookId,
                    // -1 表示「没有指定跳转目标」，阅读器沿用数据库里的上次位置
                    jumpToChapter = targetChapter.takeIf { it >= 0 },
                    jumpToOffset = targetOffset,
                    onExit = {
                        navController.popBackStack()
                    },
                    onOpenSearch = { id -> navController.navigate(Routes.search(id)) },
                )
            }

            composable(Routes.SEARCH) { entry ->
                val bookId = entry.arguments?.getString("bookId")?.takeIf { it.isNotBlank() }
                SearchScreen(
                    factory = factory,
                    bookId = bookId,
                    onBack = { navController.popBackStack() },
                    onJump = { id, chapterIndex, chapterOffset ->
                        // 搜索的全部意义就是跳到那一句：必须把命中位置传进阅读器，
                        // 只传 bookId 会让它停在「上次读到的地方」，功能等于失效。
                        navController.navigate(Routes.reader(id, chapterIndex, chapterOffset))
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
    /**
     * 底部标签。
     *
     * 只有四个：书库 / 书架 / 历史 / 设置。
     *
     * 「统计」不再占标签位 —— 它的入口已放在书库页顶部的箭头处。
     * 统计是低频查看的内容（看趋势而不是每次都用），
     * 占一个常驻标签位不划算，而顶部入口离「我这周读了多少」的
     * 那条数字更近，反而更好找。
     */
    val items = listOf(
        Triple(Routes.LIBRARY, "书库", Icons.AutoMirrored.Filled.MenuBook),
        Triple(Routes.SHELF, "书架", Icons.Filled.Bookmark),
        Triple(Routes.HISTORY, "历史", Icons.Filled.History),
        Triple(Routes.SETTINGS, "设置", Icons.Filled.Settings),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 背景先铺满到屏幕底边（含手势条区域），再把内容抬到安全区之上。
            // 顺序不能反：先 padding 再 background 会在手势条处留一条透明缝。
            .background(palette.surface)
            .safeDrawingBottomPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.divider),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .padding(bottom = 2.dp),
            // 等分由下面每个 item 的 Modifier.weight(1f) 负责，
            // 这里不再叠加 Arrangement.SpaceEvenly / SpaceBetween ——
            // 两者一起用会让间距与权重互相抵消，宽屏上尤其明显。
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { (route, label, icon) ->
                BottomBarItem(
                    label = label,
                    icon = icon,
                    active = currentRoute == route,
                    onClick = { onSelect(route) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 底部标签项。
 *
 * `modifier` 由调用方传入 `Modifier.weight(1f)` —— **必须**这样等分，
 * 不能在内部写 `fillMaxWidth(0.25f)`。
 *
 * 那个写法看起来像「占四分之一宽」，实际是「占**父容器当前可用空间**的 25%」。
 * Row 里前一个 item 已经占掉一部分，后一个的可用空间就变小了，
 * 于是四个标签宽度依次递减（实测约 25% / 19% / 14% / 11%）。
 * 标签数量从 4 个变成 5 个时，分母写死 0.25 还会直接算错。
 */
@Composable
private fun BottomBarItem(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = moyuPalette()
    val tint = if (active) palette.primary else palette.textSecondary
    Column(
        modifier = modifier
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
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
