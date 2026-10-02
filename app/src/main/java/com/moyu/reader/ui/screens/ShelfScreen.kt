package com.moyu.reader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.moyu.reader.data.model.Book
import com.moyu.reader.data.model.BookFormat
import com.moyu.reader.data.prefs.ShelfLayout
import com.moyu.reader.ui.MoyuViewModelFactory
import com.moyu.reader.ui.ShelfGroup
import com.moyu.reader.ui.ShelfStats
import com.moyu.reader.ui.ShelfViewModel
import com.moyu.reader.ui.components.EmptyState
import com.moyu.reader.ui.components.IconAction
import com.moyu.reader.ui.components.MoyuPrimaryButton
import com.moyu.reader.ui.components.MoyuTextButton
import com.moyu.reader.ui.components.ThinProgressBar
import com.moyu.reader.ui.safeBottom
import com.moyu.reader.ui.safeTop
import com.moyu.reader.ui.theme.moyuPalette
import java.io.File

/**
 * 书架页 —— 应用首页。
 *
 * 信息层级（自上而下，与主流阅读 App 一致）：问候语与概览 → 继续阅读 → 工具栏 → 筛选 → 书籍网格。
 * 「继续阅读」放在最显眼的位置是因为它是最高频的动作：
 * 用户打开阅读器的绝大多数时候就是想接着上次的地方读。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ShelfScreen(
    factory: MoyuViewModelFactory,
    onOpenBook: (String) -> Unit,
    onOpenSearch: (String?) -> Unit,
    onOpenImport: () -> Unit,
    onOpenStats: () -> Unit,
    /**
     * 打开某本书的笔记页。
     *
     * 参数从「无」改成「bookId」：全局笔记标签已被阅读历史取代，
     * 笔记改为**按书**查看。书架列表里每行的「笔记」按钮需要告诉
     * 目标页面是哪本书，否则会打开一个空页面。
     */
    onOpenNotes: (String) -> Unit,
    /**
     * 当前页面是「书库」还是「书架」。
     *
     * 书库 = 全部导入的书；书架 = 用户主动加入的那些。
     * 两者共用这一个 Composable：列表形态、筛选、排序、分组、
     * 单本操作完全一致，唯一差别是标题与「加入/移出书架」那一个动作。
     */
    libraryMode: Boolean = false,
) {
    val viewModel: ShelfViewModel = viewModel(factory = factory)

    // 由页面决定进入哪种模式。用 LaunchedEffect 而不是构造参数：
    // ViewModel 是导航层级共享的，切换标签时它不会重建，
    // 必须在进入页面时显式告知当前是哪一边。
    LaunchedEffect(libraryMode) { viewModel.setLibraryMode(libraryMode) }
    val onToggleShelf: (com.moyu.reader.data.model.Book) -> Unit = { viewModel.toggleInShelf(it) }

    val items by viewModel.items.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val shelfCount by viewModel.shelfCount.collectAsStateWithLifecycle()
    // 「继续阅读」卡片已从书库页移除（属于统计/历史的职责），
    // 因此这里不再订阅 continueReading。
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    // 筛选标签已移除：分组改用卡片钻入（见 groupCards / openGroup）。
    // 「全部 / 在读 / 未读 / 已读完」那排标签按用户要求删掉了 ——
    // 原话是「书架只应该显示书」。
    val groupCards by viewModel.groupCards.collectAsStateWithLifecycle()
    val openGroupName by viewModel.openedGroupName.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()

    val palette = moyuPalette()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.surface)
            /**
             * 顶部让开状态栏。
             *
             * 应用用 `enableEdgeToEdge()` 全屏绘制（沉浸式），因此**每个页面
             * 都必须自己让开系统栏**。书架是所有页面里唯一没有顶栏的
             * （内容直接顶到屏幕最上面），最容易漏掉这一步 ——
             * 用户反馈「书库完全没有适配全面屏的顶部状态栏保护」正是如此：
             * 「书库」标题压在状态栏上。
             *
             * 用 `windowInsetsPadding(safeDrawing)` 而不是只加一个顶部 dp：
             * 它同时覆盖状态栏、刘海/挖孔区与横屏时的侧边，且不同机型
             * 高度不同，硬编码的数值在别的机器上一定不准。
             */
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(
            top = 6.dp,
            bottom = 80.dp + safeBottom,
        ),
    ) {
        /**
         * 书架模式**不显示顶部区块**。
         *
         * 用户的原话是「书架只应该显示书，乱七八糟的删掉，不应该直接克隆主页的布局」。
         * 原来两个模式共用同一套顶部（问候语 + 三个统计数字 + 继续阅读卡 +
         * 排序筛选标签 + 新建分组），于是书架页看起来就是主页的复制品，
         * 而它真正该做的事只有一件：把书架上的书列出来。
         *
         * 那套顶部属于**书库**（进入应用后的第一个页面，承担「总览」职责）：
         * 统计数字与统计入口留在那里，书架页保持干净。
         */
        if (libraryMode) {
            /**
             * 书库顶部只有两样东西：标题栏（含统计入口）与搜索框。
             *
             * 按用户要求删掉了：
             *   - 三个统计数字（书籍数 / 已读完 / 本周时长）—— 「这些都应该在统计里显示」
             *   - 「继续阅读」卡片 —— 同样属于统计与历史
             *   - 导入按钮 —— 「导入按钮只出现在设置里」
             *
             * 统计入口保留在右上角的箭头上：它是一个**入口**而不是数据本身，
             * 删掉的话统计页就没有任何可达路径了（底栏的统计标签也已移除）。
             */
            item {
                LibraryTopBar(
                    greeting = viewModel.greeting(),
                    totalBooks = stats.total,
                    shelfCount = shelfCount,
                    onOpenStats = onOpenStats,
                )
            }

            item {
                LibrarySearchBar(onClick = { onOpenSearch(null) })
            }
        } else {
            item {
                ShelfTitleRow(
                    title = openGroupName ?: "书架",
                    subtitle = if (openGroupName != null) {
                        "${items.size} 本书 · 来自分组"
                    } else {
                        "${items.size} 本书"
                    },
                    onBack = if (openGroupName != null) {
                        { viewModel.closeGroup() }
                    } else {
                        null
                    },
                )
            }
        }

        /**
         * 分组卡片（仅书架、且未钻入任何分组时）。
         *
         * 形态向番茄小说学习：书架首页先给结构，而不是铺满几百本书的封面。
         * 每张卡片里露 4 本缩略封面 + 分组名 + 本数，点进去才是这个分组的书。
         *
         * 「全部」永远是第一张 —— 它保证用户随时能看到所有书，
         * 也提供了一个「我刚从分组里出来」的落脚点。
         */
        if (!libraryMode && openGroupName == null && groupCards.isNotEmpty()) {
            item {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val gap = 12.dp
                    val horizontalPadding = 28.dp
                    val available = maxWidth - horizontalPadding
                    val minColumn = 148.dp
                    val columns = (((available + gap) / (minColumn + gap)).toInt()).coerceIn(2, 3)
                    val groupColumnWidth = (available - gap * (columns - 1)) / columns

                    /**
                     * 缩略图高度用**固定值**，不再按列宽与宽高比推算。
                     *
                     * ## 为什么放弃推算
                     *
                     * 之前是 `(列宽 − 内边距) / 2 × 4.3/3`，看起来合理，
                     * 但真机上分组卡只显示出一块色块，**名称与「共 N 本」被裁掉**。
                     * 推算链路太长（列宽 → 减内边距 → 除 2 → 乘宽高比），
                     * 其中任何一步与实际渲染不符，结果就整体偏大、把文字挤出卡片。
                     *
                     * 分组卡里的缩略图只是「这个分组大概有什么书」的示意，
                     * 不需要精确的封面比例。给一个固定的 46.dp 高度：
                     * 两行共 96.dp，加上名称与本数约 40.dp，
                     * 卡片总高约 150.dp —— 无论屏幕多宽都不会溢出。
                     */
                    val thumbHeight = 46.dp

                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        maxItemsInEachRow = columns,
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        groupCards.forEach { group ->
                            GroupCard(
                                group = group,
                                thumbHeight = thumbHeight,
                                onClick = { viewModel.openGroup(group.id) },
                                onDelete = { viewModel.deleteGroup(group.id) },
                                onRename = { newName -> viewModel.renameGroup(group.id, newName) },
                                modifier = Modifier.width(groupColumnWidth),
                            )
                        }
                        NewGroupCard(
                            thumbsHeight = thumbHeight * 2 + 4.dp,
                            onCreate = { name -> viewModel.createGroup(name) },
                            modifier = Modifier.width(groupColumnWidth),
                        )
                    }
                }
            }
        }

        /**
         * 是否处在「分组总览」这一层。
         *
         * 用户明确要求：书架首页**只有分组卡片，点进去才看书**。
         * 因此总览下不再往下走到书籍网格 —— 下面的三个分支
         * （空状态 / 网格 / 列表）全部只在「书库」或「已钻入某个分组」时执行。
         *
         * 这也顺带解决了「分组卡下面又平铺一遍书」的重复感：
         * 「全部」卡片本身就已经代表所有书了。
         */
        val atGroupOverview = !libraryMode && openGroupName == null && groupCards.isNotEmpty()

        if (!atGroupOverview && items.isEmpty()) {
            item {
                EmptyState(
                    icon = if (libraryMode) Icons.AutoMirrored.Filled.MenuBook else Icons.Filled.BookmarkBorder,
                    title = if (libraryMode) "书库还空着" else "书架还空着",
                    description = if (libraryMode) {
                        // 导入入口已移到设置页；这里同时说明去哪儿导入，
                        // 否则用户会停在空页面上找不到下一步
                        "在「设置 → 导入本地书籍」里把 TXT / EPUB / PDF 加进来，或先加载一本示例书。"
                    } else {
                        "书架只放你主动加入的书。去「书库」里挑一本，点「加入书架」它就会出现在这里。"
                    },
                    action = {
                        // 书库为空时给两个起步动作；书架为空时只需要一句指引 ——
                        // 书架的清空是用户自己造成的，给按钮反而显得啰嗦
                        if (libraryMode) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                MoyuPrimaryButton(
                                    text = "导入本地书籍",
                                    icon = Icons.Filled.Upload,
                                    onClick = onOpenImport,
                                )
                                MoyuTextButton(
                                    text = if (importing) "正在准备示例书…" else "加载示例书",
                                    onClick = { viewModel.loadSampleBook() },
                                    enabled = !importing,
                                )
                            }
                        }
                    },
                )
            }
        } else if (!atGroupOverview && settings.shelfLayout == ShelfLayout.GRID) {
            /**
             * 网格布局。
             *
             * 这里不用 LazyVerticalGrid，原因是一个真实的缺陷：
             * 之前把它嵌在 LazyColumn 的一个 item 里，为了让它「撑开」而硬编码了高度
             * `((books/3 + 1) * 200).dp` 并禁用它的滚动 —— 那个公式同时假设了「3 列」
             * 和「每行 200dp」，两个都不成立（实际每行约 237dp，窄屏只有 2 列）。
             * 于是容器高度小于内容高度，**最后一排书根本不会被组合出来，看不见也点不到**。
             *
             * FlowRow 会自然撑到内容高度，不需要任何高度预测；书架的书籍数量在几十本量级，
             * 一次性组合的开销完全可以接受（真正需要懒加载的是成千上万条的列表）。
             */
            item {
                /**
                 * 列数按可用宽度算，而不是写死。
                 *
                 * 每列最小 104dp（与原来的 GridCells.Adaptive 同口径），
                 * 再按 14dp 间隔推算最多能放几列；至少 1 列，避免极窄屏算出 0。
                 * 这样 2 列 / 3 列 / 4 列都能正确适配，也不会再有「算错列数」的问题。
                 */
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val gap = 14.dp
                    val available = maxWidth - 28.dp // 左右各 14dp 内边距
                    val minColumn = 104.dp
                    val columns = (((available + gap) / (minColumn + gap)).toInt()).coerceAtLeast(1)

                    /**
                     * **把每列的宽度算出来，显式传给封面。**
                     *
                     * 这是「只有色块、书名看不见」的根因修复。
                     *
                     * 之前给封面的是 `Modifier.weight(1f)` + 内部 `aspectRatio`：
                     * `aspectRatio` 需要有**确定的宽度约束**才能算出高度，
                     * 而 FlowRow 的子项在测量时宽度还是「未定」，
                     * 它便退化成 Pinterest 模式（用高度反推宽度），
                     * 算出一个接近正方形的块。
                     *
                     * 那个方块比正常封面（3:4.3）高得多，把下面的书名与格式角标
                     * **挤出了卡片容器** —— 用户看到的就是「一个纯色矩形，什么字都没有」。
                     *
                     * 现在直接算：可用宽度减去所有间隔，再平分。
                     * 宽度确定之后 `height(封面宽 × 4.3/3)` 也就确定了，
                     * 高度不再依赖任何隐式测量。
                     */
                    val gapTotal = gap * (columns - 1)
                    val columnWidth = (available - gapTotal) / columns
                    val coverHeight = columnWidth * 4.3f / 3f

                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        maxItemsInEachRow = columns,
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        // 注意这里用 forEach 而不是 LazyListScope.items：
                        // 网格分支里的局部变量 `items` 会遮蔽那个扩展函数。
                        items.forEach { item ->
                            BookGridCard(
                                item = item,
                                onClick = { onOpenBook(item.book.id) },
                                onToggleShelf = { onToggleShelf(item.book) },
                                groups = groups,
                                onAssignGroup = { gid -> viewModel.assignToGroup(item.book, gid) },
                                columnWidth = columnWidth,
                                coverHeight = coverHeight,
                            )
                        }
                    }
                }
            }
        } else if (!atGroupOverview) {
            items(items, key = { it.book.id }) { item ->
                BookListRow(
                    item = item,
                    onClick = { onOpenBook(item.book.id) },
                    onNotes = { onOpenNotes(item.book.id) },
                    onToggleShelf = { onToggleShelf(item.book) },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * 书库顶栏：问候语 + 「书库」标题 + 一行附注 + 统计入口。
 *
 * ## 为什么这里是这副样子
 *
 * 用户明确要求：「书库主页不应该显示阅读分钟、继续阅读、已读完、书籍数等等，
 * 这些都应该在统计里显示」。
 *
 * 因此这一栏**不再显示任何统计数字**，只留：
 *   - 问候语（时间感，成本极低）
 *   - 标题「书库」
 *   - 一行附注：总共几本、其中几本在书架 —— 这是「我在哪、有多少」的定位信息，
 *     不是统计指标；书库页需要它，否则用户不知道自己在看全部还是子集
 *   - 右上角箭头：进统计页。**必须保留这个入口** ——
 *     底栏的统计标签已按用户要求移除，删掉它就再也没有路径能进统计页了
 */
@Composable
private fun LibraryTopBar(
    greeting: String,
    totalBooks: Int,
    shelfCount: Int,
    onOpenStats: () -> Unit,
) {
    val palette = moyuPalette()
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = greeting,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                modifier = Modifier.weight(1f),
            )
            IconAction(
                icon = Icons.Filled.ChevronRight,
                contentDescription = "阅读统计",
                onClick = onOpenStats,
            )
        }
        Text(
            text = "书库",
            style = MaterialTheme.typography.headlineLarge,
            color = palette.text,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = "本机全部书籍 · 共 $totalBooks 本（$shelfCount 本已在书架）",
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * 书库页的搜索栏。
 *
 * 用户要求「书库主页的搜索栏应该在书库页的顶部」——
 * 它现在就在标题正下方，是一个看起来像输入框的按钮（点击进入搜索页）。
 *
 * 做成「假输入框」而不是直接放一个 TextField：真正的搜索需要整页结果列表
 * （跨书、跨章、带上下文），内联在书库页里放不下。
 * 这样既在视觉上占住了顶部的显眼位置，又不牺牲搜索结果的展示空间。
 */
@Composable
private fun LibrarySearchBar(onClick: () -> Unit) {
    val palette = moyuPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(palette.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = palette.textSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "搜索书名、作者或全书内容",
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary,
        )
    }
}


@Composable
private fun StatCell(value: String, label: String) {
    val palette = moyuPalette()
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = palette.primary,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary,
        )
    }
}


private fun readingProgressLabel(item: com.moyu.reader.data.model.ShelfItem): String = when {
    item.finished -> "已读完"
    item.unread -> "未开始 · ${item.book.chapterCount} 章"
    else -> "${(item.percent * 100).toInt()}% · 共 ${item.book.chapterCount} 章"
}

/**
 * 书库页的次要操作行：搜索 + 导入。
 *
 * 布局切换与排序**不在这里** —— 它们已移到设置页。
 * 那两个是「设一次就不动」的偏好，每次进书库都看到一排标签
 * 只会让页面显得杂乱（用户的原话是「这太难看了」）。
 */


/** 书架页的标题行：一个标题 + 一个数量，必要时带返回。 */
@Composable
private fun ShelfTitleRow(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)? = null,
) {
    val palette = moyuPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 钻入分组后才显示返回：分组总览是这一栏的根，没有可返回的地方
        if (onBack != null) {
            IconAction(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回分组列表",
                onClick = onBack,
            )
        }
        Column(modifier = Modifier.padding(start = if (onBack != null) 0.dp else 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = palette.text,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * 分组卡片：4 本缩略封面 + 名称 + 本数。
 *
 * 形态参考番茄小说：用「一组书的缩略图」表达一个分组，
 * 比一行文字标签更容易一眼分辨 —— 用户认封面比认名字快。
 *
 * ## 长按管理
 *
 * 长按弹出菜单：重命名 / 删除。「全部」这个内置分组不可改名删除
 * （它是所有书的总入口，删掉就再也看不到全部书了）。
 *
 * 尺寸由外层算好传进来：封面用固定宽高，不依赖 aspectRatio ——
 * 那在宽度约束未定时会退化成正方形，把名称与本数挤出卡片。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun GroupCard(
    group: ShelfGroup,
    thumbHeight: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
    onRename: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = moyuPalette()
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var newName by remember(group.name) { mutableStateOf(group.name) }

    /** 「全部」是内置分组：不提供改名与删除。 */
    val manageable = group.id != ShelfViewModel.ALL_GROUP_ID

    Box {
        Column(
            modifier = modifier
                .clip(RoundedCornerShape(14.dp))
                .background(palette.card)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { if (manageable) menuOpen = true },
                )
                .padding(8.dp),
        ) {
            // 2 列缩略图。用 Row/Column 手动排而不是 LazyVerticalGrid：
            // 只有 4 个固定格子，嵌套一个可滚动容器只会带来测量问题。
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(2) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(2) { col ->
                            val book = group.previewBooks.getOrNull(row * 2 + col)
                            GroupThumb(
                                book = book,
                                height = thumbHeight,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            // 名称与本数。它们必须在缩略图**之下**、且卡片高度足够容纳 ——
            // 之前因为缩略图高度被算大，这两行被挤出了卡片，用户看到的就是
            // 「只有一个色块，什么字都没有」。
            Spacer(Modifier.height(6.dp))

            if (renaming) {
                androidx.compose.material3.OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    MoyuTextButton(
                        text = "保存",
                        enabled = newName.isNotBlank(),
                        onClick = {
                            onRename(newName.trim())
                            renaming = false
                        },
                    )
                    MoyuTextButton(text = "取消", onClick = { renaming = false })
                }
            } else {
                Row(
                    modifier = Modifier.padding(top = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (group.color != null) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(RoundedCornerShape(50))
                                .background(parseHexColor(group.color) ?: palette.primary),
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = "共 ${group.count} 本",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                    maxLines = 1,
                )
            }
        }

        if (menuOpen) {
            androidx.compose.material3.DropdownMenu(
                expanded = true,
                onDismissRequest = { menuOpen = false },
            ) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("重命名", style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        renaming = true
                        menuOpen = false
                    },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Text(
                            "删除分组",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFD9584A),
                        )
                    },
                    onClick = {
                        onDelete?.invoke()
                        menuOpen = false
                    },
                )
                Text(
                    text = "删除分组不会删书",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** 分组卡片里的一个小缩略封面；[book] 为 null 时画一个空格子占位。 */
@Composable
private fun GroupThumb(
    book: com.moyu.reader.data.model.Book?,
    height: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val palette = moyuPalette()
    // 高度由外层按列宽算好传入 —— 不再用 aspectRatio。
    // 在 Row + weight 里 aspectRatio 拿不到确定宽度，会退化成正方形，
    // 于是两行缩略图把分组名与本数挤出卡片（用户看到的「只有色块」）。
    val base = modifier
        .height(height)
        .clip(RoundedCornerShape(4.dp))
    Box(
        modifier = if (book != null) {
            base.background(coverGradient(book.title))
        } else {
            base.background(palette.divider)
        },
    ) {
        if (book != null) {
            val file = book.coverPath?.let { File(it) }
            var failed by remember(book.coverPath) { mutableStateOf(false) }
            if (file != null && file.exists() && !failed) {
                AsyncImage(
                    model = file,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = { failed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** 「新建分组」卡片：点开后在原位变成输入框，不弹对话框。 */
@Composable
private fun NewGroupCard(
    /** 与 GroupCard 的缩略图区等高，两种卡片在同一行里才对得齐。 */
    thumbsHeight: androidx.compose.ui.unit.Dp,
    onCreate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = moyuPalette()
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(palette.card)
            .clickable(enabled = !creating) { creating = true }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 高度直接给定值（缩略图两行 + 中间间隔），与 GroupCard 严丝合缝。
        // 原先用 aspectRatio(2f/1.46f)，同样有「宽度未定就退化」的问题。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(thumbsHeight),
            contentAlignment = Alignment.Center,
        ) {
            if (creating) {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("分组名", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = palette.textSecondary,
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        if (creating) {
            Row(
                modifier = Modifier.padding(top = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MoyuTextButton(
                    text = "创建",
                    enabled = name.isNotBlank(),
                    onClick = {
                        onCreate(name)
                        name = ""
                        creating = false
                    },
                )
                MoyuTextButton(text = "取消", onClick = { creating = false; name = "" })
            }
        } else {
            Text(
                text = "新建分组",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                modifier = Modifier.padding(top = 7.dp),
            )
        }
    }
}

/** 把 `#RRGGBB` 解析成 Color；解析不了返回 null（由调用方兜底）。 */
private fun parseHexColor(hex: String): Color? = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: IllegalArgumentException) {
    null
}


/**
 * 网格布局下的书籍卡片。
 *
 * 长按弹出「移到分组」菜单 —— 这是把书放进分组的入口。
 * 之所以用长按而不是在卡片上再加一个按钮：书架页要保持干净
 * （用户明确要求「只显示书」），而移动分组是低频动作，
 * 藏在长按里既不影响观感，也不需要额外的界面空间。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookGridCard(
    item: com.moyu.reader.data.model.ShelfItem,
    onClick: () -> Unit,
    onToggleShelf: () -> Unit,
    /** 由外层 BoxWithConstraints 算好的确定宽度 —— 不再依赖 aspectRatio 去猜。 */
    columnWidth: androidx.compose.ui.unit.Dp,
    coverHeight: androidx.compose.ui.unit.Dp,
    groups: List<com.moyu.reader.data.model.BookGroup> = emptyList(),
    onAssignGroup: (String?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette = moyuPalette()
    var showGroupMenu by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .width(columnWidth)
            .clip(RoundedCornerShape(10.dp))
            /**
             * 长按弹分组菜单，点击进书。
             *
             * 用 `combinedClickable` 而不是在 Column 上叠一层 pointerInput：
             * 后者会与内部的「加入书架」按钮抢事件，导致按钮偶尔点不动。
             */
            .combinedClickable(
                onClick = onClick,
                onLongClick = { if (groups.isNotEmpty()) showGroupMenu = true },
            )
            .padding(2.dp),
    ) {
        if (showGroupMenu) {
            androidx.compose.material3.DropdownMenu(
                expanded = true,
                onDismissRequest = { showGroupMenu = false },
            ) {
                Text(
                    text = "移到分组",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
                groups.forEach { g ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(g.name, style = MaterialTheme.typography.bodySmall) },
                        onClick = {
                            onAssignGroup(g.id)
                            showGroupMenu = false
                        },
                    )
                }
                if (item.book.groupId != null) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Text(
                                "移出分组",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFD9584A),
                            )
                        },
                        onClick = {
                            onAssignGroup(null)
                            showGroupMenu = false
                        },
                    )
                }
            }
        }
        BookCover(
            book = item.book,
            percent = item.percent,
            finished = item.finished,
            unread = item.unread,
            // 宽度与高度都由外层算好，封面不再自己推尺寸
            width = columnWidth,
            fixedHeight = coverHeight,
        )
        Text(
            text = item.book.title,
            style = MaterialTheme.typography.bodySmall,
            color = palette.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp, start = 1.dp, end = 1.dp),
        )
        Text(
            text = if (item.unread) item.book.author else item.lastReadLabel,
            style = MaterialTheme.typography.labelSmall,
            color = palette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp, start = 1.dp, end = 1.dp),
        )
        /**
         * 「加入书架 / 移出书架」。
         *
         * 网格卡片的宽度有限，因此这里用**文字按钮**而不是图标 ——
         * 图标看不出一本书当前在不在书架里，而这两个动作的差别恰恰是状态。
         * 已加入时按钮文案变成「已在书架」，一眼能看出当前状态。
         */
        Text(
            text = if (item.book.inShelf) "已在书架" else "加入书架",
            style = MaterialTheme.typography.labelSmall,
            color = if (item.book.inShelf) palette.textSecondary else palette.primary,
            maxLines = 1,
            modifier = Modifier
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(5.dp))
                .clickable(onClick = onToggleShelf)
                .padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

/** 列表布局下的行。 */
@Composable
private fun BookListRow(
    item: com.moyu.reader.data.model.ShelfItem,
    onClick: () -> Unit,
    onNotes: () -> Unit,
    onToggleShelf: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = moyuPalette()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.card)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(book = item.book, percent = item.percent, finished = item.finished, width = 50.dp)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = item.book.title,
                style = MaterialTheme.typography.titleSmall,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${item.book.author} · ${item.book.chapterCount} 章",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
            if (item.percent > 0f) {
                ThinProgressBar(
                    progress = item.percent,
                    modifier = Modifier.padding(top = 7.dp),
                )
                Text(
                    text = if (item.finished) "已读完" else "${(item.percent * 100).toInt()}% · ${item.lastReadLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Text(
                    text = "未开始",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        IconAction(
            icon = if (item.book.inShelf) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
            contentDescription = if (item.book.inShelf) "移出书架" else "加入书架",
            onClick = onToggleShelf,
        )
        IconAction(
            icon = Icons.Filled.Search,
            contentDescription = "本书笔记",
            onClick = onNotes,
        )
    }
}

/**
 * 书籍封面。
 *
 * 有真实封面图（EPUB 提取或用户设置）就显示图片；
 * 否则用**书名哈希**生成一个稳定的渐变书脊封面。
 * 用哈希而不是随机：同一本书每次渲染的颜色必须一致，
 * 否则滚动列表时封面颜色会跳变，观感很差。
 */
@Composable
fun BookCover(
    book: Book,
    percent: Float = 0f,
    finished: Boolean = false,
    unread: Boolean = false,
    width: androidx.compose.ui.unit.Dp? = 104.dp,
    /**
     * 封面的确定高度。
     *
     * ## 为什么必须能指定高度
     *
     * `aspectRatio` 要有**确定的宽度约束**才能算出高度。但在 `weight(1f)`
     * 或 `FlowRow` 的子项里，宽度约束在测量时还没定下来，
     * `aspectRatio` 便退化成 Pinterest 模式（用高度反推宽度），
     * 算出一个接近正方形的块 ——
     * 用户看到的就是「只有色块」，而且这个方块把下面的书名、格式角标
     * **挤出了容器**，所以书名也看不见。
     *
     * 调用方若能给出确定高度（详情页、分组缩略图），传进来最可靠；
     * 不传时才回退到 aspectRatio。
     */
    fixedHeight: androidx.compose.ui.unit.Dp? = null,
    modifier: Modifier = Modifier,
) {
    val palette = moyuPalette()
    val shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 10.dp, bottomEnd = 10.dp)

    // 尺寸修饰符按「宽度先定、高度再定」的顺序拼。
    // 关键：**不要在宽度已确定时再叠 fillMaxWidth()** ——
    // 那会把 width 的约束覆盖掉，等于白设。
    val sizeModifier = if (fixedHeight != null) {
        Modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .height(fixedHeight)
    } else {
        Modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
    }

    Box(
        modifier = modifier
            .then(sizeModifier)
            .then(if (fixedHeight == null) Modifier.aspectRatio(3f / 4.3f) else Modifier)
            .clip(shape)
            .background(coverGradient(book.title)),
    ) {
        val coverFile = book.coverPath?.let { File(it) }

        /**
         * 封面图是否真的能显示。
         *
         * `coverFile.exists()` **不足以**说明能显示 —— 文件可能存在但内容
         * 不是有效图片（写了一半、被别的程序截断、扩展名对不上格式）。
         * 这时 Coil 解码失败，`AsyncImage` 什么也不画，用户看到的就是
         * 底下的渐变 —— 也就是「只有一个色块，什么字都没有」。
         *
         * 因此把加载失败也当作「没有封面」处理：`onError` 里把状态置为
         * 加载失败，于是改画带书名的程序生成封面。宁可显示一个朴素的书脊，
         * 也不要显示一个没有辨识度的纯色矩形。
         */
        var coverFailed by remember(book.coverPath) { mutableStateOf(false) }
        val showCoverImage = coverFile != null && coverFile.exists() && !coverFailed

        if (showCoverImage) {
            AsyncImage(
                model = coverFile,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { coverFailed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (!showCoverImage) {
            Text(
                text = book.title.take(9),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.94f),
                fontWeight = FontWeight.Medium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 11.dp, top = 11.dp, end = 26.dp),
            )
            Text(
                text = when (book.format) {
                    BookFormat.TXT -> "TXT"
                    BookFormat.EPUB -> "EPUB"
                    BookFormat.PDF -> "PDF"
                },
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 10.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.22f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }

        if (unread) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 7.dp, end = 7.dp)
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFD9584A)),
            )
        }

        if (percent > 0f && !finished) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.Black.copy(alpha = 0.28f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(percent.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.92f)),
                )
            }
        }

        if (finished) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "已读完",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
    }
}

/**
 * 由书名生成稳定的渐变。
 *
 * 色相限制在暖色到青绿的区间（20°~200°），并压低饱和度，
 * 保证与纸感主题协调，而不会出现刺眼的荧光色。
 */
fun coverGradient(title: String): Brush {
    var hash = 17
    for (ch in title) hash = hash * 31 + ch.code
    val hue1 = 20 + (Math.abs(hash) % 180)
    val hue2 = (hue1 + 28) % 360
    return Brush.linearGradient(
        listOf(
            Color.hsl(hue1.toFloat(), 0.32f, 0.42f),
            Color.hsl(hue2.toFloat(), 0.28f, 0.30f),
        )
    )
}

